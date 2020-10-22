#!/usr/bin/env python3

from requests import post, get
from subprocess import call, Popen, PIPE
from time import sleep
import os
import socket
import hashlib
import base64

from util import startNexus, startSandbox, CheckJsonTop as T, CheckJsonField as F, assertResponse
from json_checks import (
    checkSandboxEbicsHosts,
    checkSandboxEbicsSubscriber,
    checkSandboxBankAccount,
    checkNewEbicsConnection,
    checkNewUserRequest,
    checkBackupDetails
)

# Steps implemented in this test.
#
# 0 Prepare sandbox.
#  -> (a) Make a EBICS host, (b) make a EBICS subscriber
#     for the test runner, and (c) assign a IBAN to such
#     subscriber.
#
# 1 Prepare nexus.
#  -> (a) Make a Nexus user, (b) make a EBICS subscriber
#     associated to that user

# 2 Save and restore a backup.
# 3 Send INI & HIA to the bank.

# Nexus user details
USERNAME = "person"
PASSWORD = "y"
USER_AUTHORIZATION_HEADER = "basic {}".format(
    base64.b64encode(b"person:y").decode("utf-8")
)

# Admin authentication
ADMIN_AUTHORIZATION_HEADER = "basic {}".format(
    base64.b64encode(b"admin:x").decode("utf-8")
)

# EBICS details
EBICS_URL = "http://localhost:5000/ebicsweb"
HOST_ID = "HOST01"
PARTNER_ID = "PARTNER1"
USER_ID = "USER1"
EBICS_VERSION = "H004"

# Subscriber's bank account
SUBSCRIBER_IBAN = "GB33BUKB20201555555555"
SUBSCRIBER_BIC = "BUKBGB22"
SUBSCRIBER_NAME = "Oliver Smith"
BANK_ACCOUNT_LABEL = "savings"

# Databases
NEXUS_DB="test-nexus.sqlite3"

def fail(msg):
    print(msg)
    exit(1)

startNexus(NEXUS_DB)
startSandbox()

# 0.a
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/host",
        json=checkSandboxEbicsHosts(dict(hostID=HOST_ID, ebicsVersion=EBICS_VERSION)),
    )
)

# 0.b
assertResponse(
    post(
        "http://localhost:5000/admin/ebics/subscribers",
        json=checkSandboxEbicsSubscriber(dict(hostID=HOST_ID, partnerID=PARTNER_ID, userID=USER_ID)),
    )
)

# 0.c

assertResponse(
    post(
        "http://localhost:5000/admin/ebics/bank-accounts",
        json=checkSandboxBankAccount(dict(
            subscriber=dict(hostID=HOST_ID, partnerID=PARTNER_ID, userID=USER_ID),
            iban=SUBSCRIBER_IBAN,
            bic=SUBSCRIBER_BIC,
            name=SUBSCRIBER_NAME,
            label=BANK_ACCOUNT_LABEL,
        )),
    )
)

# 1.a, make a new nexus user.
assertResponse(
    post(
        "http://localhost:5001/users",
        headers=dict(Authorization=ADMIN_AUTHORIZATION_HEADER),
        json=checkNewUserRequest(dict(username=USERNAME, password=PASSWORD))
    )
)

print("creating bank connection")

# 1.b, make a ebics bank connection for the new user.
assertResponse(
    post(
        "http://localhost:5001/bank-connections",
        json=checkNewEbicsConnection(dict(
            name="my-ebics",
            source="new",
            type="ebics",
            data=dict(
                ebicsURL=EBICS_URL, hostID=HOST_ID, partnerID=PARTNER_ID, userID=USER_ID
            ),
        )),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

print("saving a backup copy")

resp = assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics/export-backup",
        json=checkBackupDetails(dict(passphrase="secret")),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

# resp is the backup object.

print("wait 3 seconds before restoring the backup")
sleep(3)

assertResponse(
    post(
        "http://localhost:5001/bank-connections",
        json=dict(name="my-ebics-restored", data=resp.json(), passphrase="secret", source="backup"),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

print("send ini & hia with restored connection")

assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics-restored/ebics/send-ini",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

assertResponse(
    post(
        "http://localhost:5001/bank-connections/my-ebics-restored/ebics/send-hia",
        json=dict(),
        headers=dict(Authorization=USER_AUTHORIZATION_HEADER),
    )
)

print("Test passed!")
