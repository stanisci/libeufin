#!/bin/bash

# Such template sets an env up using the Python CLI.
# The setup goes until exchanging keys with the sandbox.

set -eu

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

########## setup sandbox #############

# make ebics host at sandbox
echo Making a ebics host at the sandbox
sleep 2
./libeufin-cli \
  sandbox \
    make-ebics-host \
      --host-id=$EBICS_HOST_ID \
      $SANDBOX_URL

# activate a ebics subscriber on that host
echo Activating the ebics subscriber at the sandbox
sleep 2
./libeufin-cli \
  sandbox \
    activate-ebics-subscriber \
      --host-id=$EBICS_HOST_ID \
      --partner-id=$EBICS_PARTNER_ID \
      --user-id=$EBICS_USER_ID \
      $SANDBOX_URL

# give a bank account to such user
echo Giving a bank account to such subscriber
./libeufin-cli \
  sandbox \
    associate-bank-account \
      --iban=$IBAN \
      --bic=$BIC \
      --person-name=$PERSON_NAME \
      --account-name=$ACCOUNT_NAME \
      --ebics-user-id=$EBICS_USER_ID \
      --ebics-host-id=$EBICS_HOST_ID \
      --ebics-partner-id=$EBICS_PARTNER_ID \
      $SANDBOX_URL
sleep 2

########## setup nexus #############

# create a user
NEXUS_DATABASE=$(curl -s $NEXUS_BASE_URL/service-config | jq .dbConn | tr -d \" | awk -F: '{print $2}')
echo "Creating a nexus superuser"
libeufin-nexus superuser --db-name $NEXUS_DATABASE --password $NEXUS_PASSWORD $NEXUS_USER &> /dev/null
sleep 2

# create a bank connection
echo Creating a bank connection for such user
./libeufin-cli \
  connections \
    new-ebics-connection \
      --ebics-url $EBICS_BASE_URL \
      --host-id $EBICS_HOST_ID \
      --partner-id $EBICS_PARTNER_ID \
      --ebics-user-id $EBICS_USER_ID \
      $NEXUS_BANK_CONNECTION_NAME > /dev/null
sleep 2

# Bootstrapping such connection.
echo Bootstrapping the bank connection
./libeufin-cli \
  connections sync $NEXUS_BANK_CONNECTION_NAME > /dev/null

# Download bank accounts.
echo Download bank accounts
./libeufin-cli \
  connections download-bank-accounts \
    $NEXUS_BANK_CONNECTION_NAME > /dev/null

# Import bank account for user.
./libeufin-cli \
  connections import-bank-account \
    --offered-account-id=$ACCOUNT_NAME \
    --nexus-bank-account-id=$ACCOUNT_NAME_AT_NEXUS \
      $NEXUS_BANK_CONNECTION_NAME > /dev/null

cat << EOF

Now usable via $(tput bold)libeufin-cli$(tput sgr0):
Bank connection: $(tput bold)$NEXUS_BANK_CONNECTION_NAME$(tput sgr0)
Bank account: $(tput bold)$ACCOUNT_NAME_AT_NEXUS$(tput sgr0)

EOF
