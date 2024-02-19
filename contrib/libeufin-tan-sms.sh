#!/bin/sh
# This file is in the public domain.
set -eu
# Set AUTH_TOKEN=...

MESSAGE=`cat -`
TMPFILE=`mktemp /tmp/sms-loggingXXXXXX`
PHONE_NUMBER=$(echo $1 | sed 's/^+//') # Telesign refuses the leading +
STATUS=$(curl --request POST \
     --url https://rest-api.telesign.com/v1/messaging \
     --header "authorization: Basic $AUTH_TOKEN" \
     --header 'content-type: application/x-www-form-urlencoded' \
     --data account_livecycle_event=transact \
     --data "message=$MESSAGE" \
     --data message_type=OTP \
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