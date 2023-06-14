#!/bin/bash

# Convenience script to setup and run a Sandbox & Nexus
# connected through x-libeufin-bank.
set -eu

# WITH_TASKS=1
WITH_TASKS=0
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

DB_CONN="jdbc:postgresql://localhost/libeufincheck?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory\$FactoryArg&socketFactoryArg=/var/run/postgresql/.s.PGSQL.5432"

export LIBEUFIN_SANDBOX_DB_CONNECTION=$DB_CONN
export LIBEUFIN_NEXUS_DB_CONNECTION=$DB_CONN

echo -n Delete previous data...
libeufin-sandbox reset-tables
libeufin-nexus reset-tables
echo DONE
echo -n Configure the default demobank with MANA...
libeufin-sandbox config --with-signup-bonus --currency MANA default
echo DONE
echo -n Setting the default exchange at Sandbox...
libeufin-sandbox \
  default-exchange \
  "https://exchange.example.com/" \
  "payto://iban/NOTUSED"
echo DONE
echo -n Start the bank...
export LIBEUFIN_SANDBOX_ADMIN_PASSWORD=foo
libeufin-sandbox serve > sandbox.log 2>&1 &
SANDBOX_PID=$!
echo DONE
echo -n Wait for the bank...
curl --max-time 2 --retry-connrefused --retry-delay 1 --retry 10 http://localhost:5000/ &> /dev/null
echo DONE
echo -n Make one superuser at Nexus...
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
echo -n Creating the x-libeufin-bank connection at Nexus...
export LIBEUFIN_NEXUS_USERNAME=test-user
export LIBEUFIN_NEXUS_PASSWORD=x
export LIBEUFIN_NEXUS_URL=http://localhost:5001
# echoing the password to STDIN, as that is a "prompt" option.
libeufin-cli connections new-xlibeufinbank-connection \
  --bank-url "http://localhost:5000/demobanks/default/access-api" \
  --username www \
  --password foo \
  wwwconn
echo DONE
echo -n Connecting the x-libeufin-bank connection...
libeufin-cli connections connect wwwconn
echo DONE
# Importing the bank account under a local name at Nexus.
echo -n Importing the x-libeufin-bank account locally..
libeufin-cli connections import-bank-account \
  --offered-account-id www \
  --nexus-bank-account-id foo-at-nexus wwwconn
echo DONE
echo -n Create the Taler facade at Nexus...
libeufin-cli facades \
  new-taler-wire-gateway-facade \
  --currency TESTKUDOS --facade-name test-facade \
  wwwconn foo-at-nexus
echo DONE
if test 1 = $WITH_TASKS; then
  echo -n Creating submit transactions task..
  libeufin-cli accounts task-schedule \
    --task-type submit \
    --task-name www-payments \
    --task-cronspec "* * *" \
    foo-at-nexus || true
  # Tries every second.  Ask C52
  echo DONE
  echo -n Creating fetch transactions task..
  # Not idempotent, FIXME #7739
  libeufin-cli accounts task-schedule \
    --task-type fetch \
    --task-name www-history \
    --task-cronspec "* * *" \
    --task-param-level statement \
    --task-param-range-type since-last \
    foo-at-nexus || true
  echo DONE
else
  echo NOT creating background tasks!
fi

read -p "Press Enter to terminate..."
