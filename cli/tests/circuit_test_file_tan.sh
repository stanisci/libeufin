#!/bin/bash

# Tests successful cases of the CLI acting
# as the client of the Circuit API.

set -eu

echo TESTING THE CLI SIDE OF THE CIRCUIT API
jq --version &> /dev/null || (echo "'jq' command not found"; exit 77)
curl --version &> /dev/null || (echo "'curl' command not found"; exit 77)

DB_PATH=/tmp/circuit-test.sqlite3
export LIBEUFIN_SANDBOX_DB_CONNECTION=jdbc:sqlite:$DB_PATH

echo -n Delete previous data..
rm -f $DB_PATH
echo DONE
echo -n Configure the default demobank...
libeufin-sandbox config default
echo DONE
echo -n Start the bank...
export LIBEUFIN_SANDBOX_ADMIN_PASSWORD=circuit
libeufin-sandbox serve &> sandbox.log &
SANDBOX_PID=$!
trap "echo -n 'killing the bank (pid $SANDBOX_PID)...'; kill $SANDBOX_PID; wait; echo DONE" EXIT
echo DONE
echo -n Wait for the bank...
curl --max-time 2 --retry-connrefused --retry-delay 1 --retry 10 http://localhost:5000/ &> /dev/null
echo DONE
echo Ask Circuit API /config...
curl http://localhost:5000/demobanks/default/circuit-api/config &> /dev/null
echo DONE
echo -n "Register new account..."
export LIBEUFIN_SANDBOX_USERNAME=admin
export LIBEUFIN_SANDBOX_PASSWORD=circuit
export LIBEUFIN_NEW_CIRCUIT_ACCOUNT_PASSWORD=foo
./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  circuit-register --name eee --username www \
    --cashout-address payto://iban/FIAT --internal-iban LOCAL 
echo DONE
echo -n Reconfigure account specifying a phone number..
# Give phone number.
export LIBEUFIN_SANDBOX_USERNAME=www
export LIBEUFIN_SANDBOX_PASSWORD=foo
./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  circuit-reconfig --cashout-address payto://iban/WWW --phone +999
echo DONE
echo -n Create a cash-out operation...
CASHOUT_RESP=$(./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  circuit-cashout \
    --tan-channel=file \
    --amount-debit=EUR:1 \
    --amount-credit=CHF:0.95
)
echo DONE
echo -n "Extract the cash-out UUID..."
CASHOUT_UUID=$(echo ${CASHOUT_RESP} | jq --raw-output '.uuid')
echo DONE
echo -n Get cash-out details...
RESP=$(./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  circuit-cashout-details \
  --uuid $CASHOUT_UUID
)
OPERATION_STATUS=$(echo $RESP | jq --raw-output '.status')
if ! test "$OPERATION_STATUS" = "PENDING"; then
    echo Unexpected cash-out operation status found: $OPERATION_STATUS
    exit 1
fi
echo DONE
echo -n Abort the cash-out operation...
RESP=$(./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  circuit-cashout-abort \
  --uuid $CASHOUT_UUID
)
echo DONE
echo -n Create another cash-out operation...
CASHOUT_RESP=$(./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  circuit-cashout \
    --tan-channel=file \
    --amount-debit=EUR:1 \
    --amount-credit=CHF:0.95
)
CASHOUT_UUID=$(echo ${CASHOUT_RESP} | jq --raw-output '.uuid')
echo DONE
echo Reading the TAN from /tmp/libeufin-cashout-tan.txt
INPUT_TAN=$(cat /tmp/libeufin-cashout-tan.txt)
echo -n Confirm the last cash-out operation...
./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  circuit-cashout-confirm --uuid $CASHOUT_UUID --tan $INPUT_TAN
echo DONE
# The user now has -1 balance.  Let the bank
# award EUR:1 to them, in order to bring their
# balance to zero.
echo -n Bring the account to 0 balance...
export LIBEUFIN_SANDBOX_USERNAME=admin
export LIBEUFIN_SANDBOX_PASSWORD=circuit
./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  new-transaction \
  --bank-account admin \
  --payto-with-subject "payto://iban/SANDBOXX/LOCAL?message=bring-to-zero" \
  --amount EUR:1
echo DONE
echo -n Delete the account...
export LIBEUFIN_SANDBOX_USERNAME=admin
export LIBEUFIN_SANDBOX_PASSWORD=circuit
./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  circuit-delete-account --username www
echo DONE
