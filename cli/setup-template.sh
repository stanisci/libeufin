#!/bin/bash

# Such template sets an env up using the Python CLI.
# The setup goes until exchanging keys with the sandbox.

set -eu

SQLITE_FILE="/tmp/libeufin-cli-env.sqlite3"
DATABASE_CONN="jdbc:sqlite:$SQLITE_FILE"
CURRENCY="EUR"

# EBICS details.
SANDBOX_URL="http://localhost:5000"
EBICS_HOST_ID=ebicshost
EBICS_PARTNER_ID=ebicspartner
EBICS_USER_ID=ebicsuser
EBICS_BASE_URL="$SANDBOX_URL/ebicsweb"

# A bank account details.
IBAN=LU150102294655243148
BIC=ABNALU2A
PERSON_NAME=z
ACCOUNT_NAME=a
ACCOUNT_NAME_AT_NEXUS="local-$ACCOUNT_NAME"

# A Nexus user details.
NEXUS_USER=u
NEXUS_PASSWORD=p
NEXUS_BANK_CONNECTION_NAME=b

# Exports needed by the CLI.
export NEXUS_BASE_URL="http://localhost:5001/"
export NEXUS_USERNAME=$NEXUS_USER
export NEXUS_PASSWORD=$NEXUS_PASSWORD

echo Remove old database.
rm -f $SQLITE_FILE

echo Start services.
libeufin-nexus serve --db-conn-string=$DATABASE_CONN &> nexus.log &
nexus_pid=$!
libeufin-sandbox serve --db-conn-string=$DATABASE_CONN &> sandbox.log &
sandbox_pid=$!

trap "echo Terminating services.; kill $nexus_pid; kill $sandbox_pid" EXIT

curl -s --retry 5 --retry-connrefused $SANDBOX_URL > /dev/null
curl -s --retry 5 --retry-connrefused $NEXUS_BASE_URL > /dev/null

########## setup sandbox #############

# make ebics host at sandbox
echo Making a ebics host at the sandbox
./bin/libeufin-cli \
  sandbox --sandbox-url=$SANDBOX_URL \
    ebicshost create \
      --host-id=$EBICS_HOST_ID

# activate a ebics subscriber on that host
echo Activating the ebics subscriber at the sandbox
./bin/libeufin-cli \
  sandbox --sandbox-url=$SANDBOX_URL \
    ebicssubscriber create \
      --host-id=$EBICS_HOST_ID \
      --partner-id=$EBICS_PARTNER_ID \
      --user-id=$EBICS_USER_ID

# give a bank account to such user
echo Giving a bank account to such subscriber
./bin/libeufin-cli \
  sandbox --sandbox-url=$SANDBOX_URL \
    ebicsbankaccount create \
      --iban=$IBAN \
      --bic=$BIC \
      --person-name=$PERSON_NAME \
      --account-name=$ACCOUNT_NAME \
      --ebics-user-id=$EBICS_USER_ID \
      --ebics-host-id=$EBICS_HOST_ID \
      --ebics-partner-id=$EBICS_PARTNER_ID \
      --currency=$CURRENCY

########## setup nexus #############

# create a user
echo "Creating a nexus superuser"
libeufin-nexus \
  superuser \
    --db-conn-string=$DATABASE_CONN \
    --password $NEXUS_PASSWORD $NEXUS_USER &> /dev/null

# create a bank connection
echo Creating a bank connection for such user
./bin/libeufin-cli \
  connections \
    new-ebics-connection \
      --ebics-url $EBICS_BASE_URL \
      --host-id $EBICS_HOST_ID \
      --partner-id $EBICS_PARTNER_ID \
      --ebics-user-id $EBICS_USER_ID \
      $NEXUS_BANK_CONNECTION_NAME > /dev/null

# Bootstrapping such connection.
echo Bootstrapping the bank connection
./bin/libeufin-cli \
  connections sync $NEXUS_BANK_CONNECTION_NAME > /dev/null

# Download bank accounts.
echo Download bank accounts
./bin/libeufin-cli \
  connections download-bank-accounts \
    $NEXUS_BANK_CONNECTION_NAME > /dev/null

# Import bank account for user.
./bin/libeufin-cli \
  connections import-bank-account \
    --offered-account-id=$ACCOUNT_NAME \
    --nexus-bank-account-id=$ACCOUNT_NAME_AT_NEXUS \
      $NEXUS_BANK_CONNECTION_NAME > /dev/null

cat << EOF

Connection: $(tput bold)$NEXUS_BANK_CONNECTION_NAME$(tput sgr0)
Account (imported): $(tput bold)$ACCOUNT_NAME_AT_NEXUS$(tput sgr0)

EOF
