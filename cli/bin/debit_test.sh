#!/bin/bash

# Tests successful cases of the CLI acting
# as the client of the Circuit API.

set -eu

echo TESTING DEBIT THRESHOLD
jq --version &> /dev/null || (echo "'jq' command not found"; exit 77)
curl --version &> /dev/null || (echo "'curl' command not found"; exit 77)

DB_PATH=/tmp/debit-test.sqlite3
export LIBEUFIN_SANDBOX_DB_CONNECTION=jdbc:sqlite:$DB_PATH

echo -n Delete previous data..
rm -f $DB_PATH
echo DONE
echo -n Configure the default demobank, with users limit 0...
libeufin-sandbox config --currency MANA default --users-debt-limit=0
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

echo -n "Register new account..."
export LIBEUFIN_SANDBOX_USERNAME=www
export LIBEUFIN_SANDBOX_PASSWORD=foo

./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  register
echo DONE
echo -n "Get the admin's IBAN..."
ADMIN_IBAN=$(echo "SELECT iban FROM BankAccounts WHERE label='admin'" | sqlite3 $DB_PATH)
echo "DONE ($ADMIN_IBAN)"
echo -n "Try to surpass the debit threshold (user pays admin)..."
./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  new-transaction \
  --bank-account www \
  --payto-with-subject "payto://iban/SANDBOXX/${ADMIN_IBAN}?message=go-debit" \
  --amount MANA:99999 &> /dev/null || true
echo DONE

echo -n Check the amount is zero...
RESP=$(libeufin-cli sandbox --sandbox-url http://localhost:5000/ demobank info --bank-account www)
BALANCE=$(echo $RESP | jq -r '.balance.amount')
if [ "$BALANCE" != "MANA:0" ]; then
  echo Debit threshold of MANA:0 not respected.
  exit 1
fi
echo DONE

echo -n "Try to surpass the debit threshold via low-level libeufin-sandbox command..."
libeufin-sandbox \
  make-transaction \
  --credit-account=admin \
  --debit-account=www \
  MANA:9999 "Go debit again."  &> /dev/null || true
echo DONE

echo -n Check the amount is again zero...
RESP=$(libeufin-cli sandbox --sandbox-url http://localhost:5000/ demobank info --bank-account www)
BALANCE=$(echo $RESP | jq -r '.balance.amount')
if [ "$BALANCE" != "MANA:0" ]; then
  echo Debit threshold of MANA:0 not respected via low-level libeufin-sandbox command.
  exit 1
fi
echo DONE
