#!/bin/bash

# Tests successful cases of the CLI acting
# as the client of the Circuit API.

set -eu

echo TESTING THE EMAIL TAN
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
echo -n Starting the bank passing the e-mail TAN option...
export LIBEUFIN_SANDBOX_ADMIN_PASSWORD=circuit
libeufin-sandbox serve \
  --email-tan "../../contrib/libeufin-tan-email.sh" &> sandbox.log &
SANDBOX_PID=$!
# Cleaner:
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
  circuit-register --name eee --username www --email $LIBEUFIN_EMAIL_ADDRESS \
    --cashout-address payto://iban/FIAT --internal-iban LOCAL 
echo DONE
echo -n Create the cash-out operation with the e-mail TAN...
export LIBEUFIN_SANDBOX_USERNAME=www
export LIBEUFIN_SANDBOX_PASSWORD=foo
CASHOUT_RESP=$(./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  circuit-cashout \
    --tan-channel=email \
    --amount-debit=EUR:1 \
    --amount-credit=CHF:0.95
)
CASHOUT_UUID=$(echo ${CASHOUT_RESP} | jq --raw-output '.uuid')
echo DONE
echo -n Checking that cash-out status is 'pending'...
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
read -p "Enter the TAN received via e-mail:" INPUT_TAN
echo -n Confirming the last cash-out operation...
./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  circuit-cashout-confirm --uuid $CASHOUT_UUID --tan $INPUT_TAN
echo DONE
echo -n Checking the balance...
RESP=$(libeufin-cli sandbox --sandbox-url http://localhost:5000/ demobank info --bank-account www)
BALANCE=$(echo $RESP | jq -r '.balance.amount')
INDICATOR=$(echo $RESP | jq -r '.balance.credit_debit_indicator')
echo $BALANCE $INDICATOR
echo DONE
