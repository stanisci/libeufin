#!/bin/bash

# This script injects an initiated payment into its
# table, in order to test the ebics-submit subcommand.

usage() {
  echo "Usage: ./payment_initiation.sh CONFIG_FILE IBAN_CREDITOR PAYMENT_SUBJECT"
  echo
  echo "Pays a fixed amount of 1 CHF to the IBAN_CREDITOR with the PAYMENT_SUBJECT."
  echo "It requires the EBICS keying to be already carried out, see ebics-setup"
  echo "subcommand at libeufin-nexus(1)"
}

# Detecting the help case.
if test "$1" = "--help" -o "$1" = "-h" -o -z ${1:-};
  then usage
  exit
fi

set -eu

CONFIG_FILE=$1
IBAN_CREDITOR=$2
PAYMENT_SUBJECT=$3

# Getting the database connection.
DB_NAME=$(taler-config -c $1 -s nexus-postgres -o config)
echo database: $DB_NAME

# Optionally reading the user-provided request UID.
if test -n "${LIBEUFIN_SUBMIT_REQUEST_UID:-}"
  then SUBMIT_REQUEST_UID="$LIBEUFIN_SUBMIT_REQUEST_UID"
  else SUBMIT_REQUEST_UID=$(uuidgen | cut -c -30)
fi

# Finally inserting the initiated payment into the database.
INSERT_COMMAND="
INSERT INTO libeufin_nexus.initiated_outgoing_transactions
  (amount,
  wire_transfer_subject,
  initiation_time,
  credit_payto_uri,
  request_uid)
  VALUES ((1,0),
  '${PAYMENT_SUBJECT}',
  $(($(date +%s) * 1000000)),
  'payto://iban/POFICHBE/${IBAN_CREDITOR}?receiver-name=Merchant',
  '${SUBMIT_REQUEST_UID}')"

# Only logging errors.
psql $DB_NAME -c "$INSERT_COMMAND" > /dev/null
