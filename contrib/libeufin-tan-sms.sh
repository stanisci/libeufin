#!/bin/sh

# This file is in the public domain.

set -eu

. telesign-secrets # need to be found in the PATH
# Set CUSTOMER_ID and API_KEY

MESSAGE=`cat -`
TMPFILE=`mktemp /tmp/sms-loggingXXXXXX`
PHONE_NUMBER=$(echo $1 | sed 's/^+//') # Telesign refuses the leading +
STATUS=$(curl --request POST \
     --user "$CUSTOMER_ID:$API_KEY" \
     --url https://rest-api.telesign.com/v1/messaging \
     --data "message_type=OTP" \
     --data "message=$MESSAGE" \
     --data "phone_number=$PHONE_NUMBER" \
     -w "%{http_code}" -s -o $TMPFILE)
echo `cat $TMPFILE` >> $HOME/sms.log
rm -f $TMPFILE
case $STATUS in
    200|203|250|290|291|295)
        exit 0;
        ;;
    *)
        exit 1;
        ;;
esac
exit 1
