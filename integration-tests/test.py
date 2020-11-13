#!/usr/bin/env python3

from util import startNexus, startSandbox, assertResponse, flushTablesSandbox
from requests import post, get

# Databases
NEXUS_DB="/tmp/test-nexus.sqlite3"
SANDBOX_DB="/tmp/test-sandbox.sqlite3"

# Nexus user details
NEXUS_USERNAME = "person"
NEXUS_PASSWORD = "y"

# EBICS details
EBICS_URL = "http://localhost:5000/ebicsweb"
EBICS_HOST = "HOST01"
EBICS_PARTNER = "PARTNER1"
EBICS_USER = "USER1"
EBICS_VERSION = "H004"

# Subscriber's bank account at the Sandbox
BANK_IBAN = "GB33BUKB20201555555555"
BANK_BIC = "BUKBGB22"
BANK_NAME = "Oliver Smith"
BANK_LABEL = "savings"

def prepareSandbox():
    # make ebics host at sandbox
    assertResponse(
        post(
            "http://localhost:5000/admin/ebics/host",
            json=dict(hostID=EBICS_HOST, ebicsVersion=EBICS_VERSION),
        )
    )
    
    # make new ebics subscriber at sandbox
    assertResponse(
        post(
            "http://localhost:5000/admin/ebics/subscribers",
            json=dict(hostID=EBICS_HOST, partnerID=EBICS_PARTNER, userID=EBICS_USER),
        )
    )
    
    # give a bank account to such subscriber, at sandbox
    assertResponse(
        post(
            "http://localhost:5000/admin/ebics/bank-accounts",
            json=dict(
                subscriber=dict(hostID=EBICS_HOST, partnerID=EBICS_PARTNER, userID=EBICS_USER),
                iban=BANK_IBAN,
                bic=BANK_BIC,
                name=BANK_NAME,
                label=BANK_LABEL
            )
        )
    )

startNexus(NEXUS_DB)
startSandbox(SANDBOX_DB)

prepareSandbox()
print("Services correctly started.")
print("Emptying tables at Sandbox")
flushTablesSandbox(SANDBOX_DB)
