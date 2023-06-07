#!/bin/bash

# Convenience script to setup and run a Sandbox + Nexus
# EBICS pair, in order to try CLI commands.
set -eu

function exit_cleanup()
{
  echo "Running exit-cleanup"
  for n in `jobs -p`
    do
      kill $n 2> /dev/null || true
    done
    wait || true
    echo "DONE"
}

trap "exit_cleanup" EXIT
echo RUNNING SANDBOX-NEXUS EBICS PAIR
jq --version &> /dev/null || (echo "'jq' command not found"; exit 77)
curl --version &> /dev/null || (echo "'curl' command not found"; exit 77)

DB_PATH=/tmp/libeufin-cli-test.sqlite3
export LIBEUFIN_SANDBOX_DB_CONNECTION=jdbc:sqlite:$DB_PATH

echo -n Delete previous data...
rm -f $DB_PATH
echo DONE
echo -n Configure the default demobank with MANA...
libeufin-sandbox config --with-signup-bonus --currency MANA default
echo DONE
echo -n Start the bank...
export LIBEUFIN_SANDBOX_ADMIN_PASSWORD=foo
libeufin-sandbox serve &> sandbox.log &
SANDBOX_PID=$!
echo DONE
echo -n Wait for the bank...
curl --max-time 2 --retry-connrefused --retry-delay 1 --retry 10 http://localhost:5000/ &> /dev/null
echo DONE
echo -n Make one superuser at Nexus...
export LIBEUFIN_NEXUS_DB_CONNECTION=jdbc:sqlite:$DB_PATH
libeufin-nexus superuser test-user --password x
echo DONE
echo -n Launching Nexus...
libeufin-nexus serve &> nexus.log &
NEXUS_PID=$!
echo DONE
echo -n Waiting for Nexus...
curl --max-time 2 --retry-connrefused --retry-delay 1 --retry 10 http://localhost:5001/ &> /dev/null
echo DONE

echo -n "Register the 'www' Sandbox account..."
export LIBEUFIN_SANDBOX_USERNAME=www
export LIBEUFIN_SANDBOX_PASSWORD=foo
libeufin-cli \
  sandbox --sandbox-url http://localhost:5000/ \
  demobank \
  register
echo DONE
export LIBEUFIN_SANDBOX_USERNAME=admin
export LIBEUFIN_SANDBOX_PASSWORD=foo
echo -n "Create EBICS host at Sandbox..."
libeufin-cli sandbox \
  --sandbox-url http://localhost:5000 \
  ebicshost create --host-id wwwebics
echo OK
echo -n "Create 'www' EBICS subscriber at Sandbox..."
libeufin-cli sandbox \
  --sandbox-url http://localhost:5000 \
  demobank new-ebicssubscriber --host-id wwwebics \
  --user-id wwwebics --partner-id wwwpartner \
  --bank-account www # that's a username _and_ a bank account name
echo OK
export LIBEUFIN_NEXUS_USERNAME=test-user
export LIBEUFIN_NEXUS_PASSWORD=x
export LIBEUFIN_NEXUS_URL=http://localhost:5001
echo -n Creating the EBICS connection at Nexus...
libeufin-cli connections new-ebics-connection \
  --ebics-url "http://localhost:5000/ebicsweb" \
  --host-id wwwebics \
  --partner-id wwwpartner \
  --ebics-user-id wwwebics \
  wwwconn
echo DONE
echo -n Setup EBICS keying...
libeufin-cli connections connect wwwconn > /dev/null
echo OK
echo -n Download bank account name from Sandbox...
libeufin-cli connections download-bank-accounts wwwconn
echo OK
echo -n Importing bank account info into Nexus...
libeufin-cli connections import-bank-account \
  --offered-account-id www \
  --nexus-bank-account-id www-nexus \
  wwwconn
echo OK
echo -n Create the Taler facade at Nexus...
libeufin-cli facades \
  new-taler-wire-gateway-facade \
  --currency TESTKUDOS --facade-name test-facade \
  wwwconn www-nexus
echo OK
echo -n "Ticking, to let statements be generated..."
libeufin-sandbox camt053tick
echo OK
read -p "Press Enter to terminate..."
