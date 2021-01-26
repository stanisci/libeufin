#!/bin/bash

# Such template sets an env up using the Python CLI.
# The setup goes until exchanging keys with the sandbox.

set -eu

SANDBOX_URL="http://localhost:5000"
NEXUS_URL="http://localhost:5001"
SQLITE_FILE="/tmp/libeufin-cli-env.sqlite3"
DATABASE_CONN="jdbc:sqlite:$SQLITE_FILE"
CURRENCY="EUR"

# EBICS details.
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

# Needed env

export LIBEUFIN_NEXUS_URL=$NEXUS_URL \
       LIBEUFIN_NEXUS_USERNAME=$NEXUS_USER \
       LIBEUFIN_NEXUS_PASSWORD=$NEXUS_PASSWORD \
       LIBEUFIN_SANDBOX_URL=$SANDBOX_URL

echo Remove old database.
rm -f $SQLITE_FILE

echo Start services.
libeufin-nexus serve --db-conn-string=$DATABASE_CONN &> nexus.log &
nexus_pid=$!
libeufin-sandbox serve --db-conn-string=$DATABASE_CONN &> sandbox.log &
sandbox_pid=$!

trap "echo Terminating services.; kill $nexus_pid; kill $sandbox_pid" EXIT

curl -s --retry 5 --retry-connrefused $SANDBOX_URL > /dev/null
curl -s --retry 5 --retry-connrefused $NEXUS_URL > /dev/null

########## setup sandbox #############

# make ebics host at sandbox
echo Making a ebics host at the sandbox
libeufin-cli \
  sandbox --sandbox-url=$SANDBOX_URL \
    ebicshost create \
      --host-id=$EBICS_HOST_ID

# activate a ebics subscriber on that host
echo Activating the ebics subscriber at the sandbox
libeufin-cli \
  sandbox --sandbox-url=$SANDBOX_URL \
    ebicssubscriber create \
      --host-id=$EBICS_HOST_ID \
      --partner-id=$EBICS_PARTNER_ID \
      --user-id=$EBICS_USER_ID

# give a bank account to such user
echo Giving a bank account to such subscriber
libeufin-cli \
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
    --password $NEXUS_PASSWORD $NEXUS_USER

# create a bank connection
echo Creating a bank connection for such user
libeufin-cli \
  connections \
    new-ebics-connection \
      --ebics-url $EBICS_BASE_URL \
      --host-id $EBICS_HOST_ID \
      --partner-id $EBICS_PARTNER_ID \
      --ebics-user-id $EBICS_USER_ID \
      $NEXUS_BANK_CONNECTION_NAME

# Bootstrapping such connection.
echo Bootstrapping the bank connection
libeufin-cli \
  connections connect $NEXUS_BANK_CONNECTION_NAME

# Download bank accounts.
echo Download bank accounts
libeufin-cli \
  connections download-bank-accounts \
    $NEXUS_BANK_CONNECTION_NAME

# Import bank account for user.
libeufin-cli \
  connections import-bank-account \
    --offered-account-id=$ACCOUNT_NAME \
    --nexus-bank-account-id=$ACCOUNT_NAME_AT_NEXUS \
      $NEXUS_BANK_CONNECTION_NAME

cat << EOF

Bank connection name: $(tput bold)$NEXUS_BANK_CONNECTION_NAME$(tput sgr0)
Bank account, imported name: $(tput bold)$ACCOUNT_NAME_AT_NEXUS$(tput sgr0)
Bank account, native name: $(tput bold)$ACCOUNT_NAME$(tput sgr0)
env-setter: $(tput bold)export LIBEUFIN_NEXUS_URL=http://localhost:5001/ LIBEUFIN_NEXUS_USERNAME=$NEXUS_USER LIBEUFIN_NEXUS_PASSWORD=$NEXUS_PASSWORD LIBEUFIN_SANDBOX_URL=$SANDBOX_URL$(tput sgr0)

EOF

read -p "Press Enter to terminate the services: "
