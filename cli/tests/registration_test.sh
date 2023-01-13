#!/bin/bash

# Tests successful cases of the CLI acting
# as the client of the Circuit API.

set -eu

echo TESTING ACCESS API REGISTRATION
jq --version &> /dev/null || (echo "'jq' command not found"; exit 77)
curl --version &> /dev/null || (echo "'curl' command not found"; exit 77)

DB_PATH=/tmp/registration-test.sqlite3
export LIBEUFIN_SANDBOX_DB_CONNECTION=jdbc:sqlite:$DB_PATH

echo -n Delete previous data..
rm -f $DB_PATH
echo DONE
echo -n Configure the default demobank, with users limit 0...
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

echo -n "Register new account..."
export LIBEUFIN_SANDBOX_USERNAME=www
export LIBEUFIN_SANDBOX_PASSWORD=foo

./libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  register
echo DONE

echo -n Check the account is found...
curl -u "www:foo" http://localhost:5000/demobanks/default/access-api/accounts/www
echo DONE
