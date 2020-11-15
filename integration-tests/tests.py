#!/usr/bin/env python3

from requests import post, get, auth
from util import (
    startNexus,
    startSandbox,
    assertResponse,
    flushTablesSandbox,
    flushTablesNexus
)

# Databases
NEXUS_DB="/tmp/test-nexus.sqlite3"
SANDBOX_DB="/tmp/test-sandbox.sqlite3"

# Nexus user details
NEXUS_USERNAME = "person"
NEXUS_PASSWORD = "y"
NEXUS_BANK_CONNECTION="my-ebics"
NEXUS_BANK_LABEL="local-savings"
NEXUS_AUTH = auth.HTTPBasicAuth(
    NEXUS_USERNAME,
    NEXUS_PASSWORD
)

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

def prepareNexus():
    # make a new nexus user.
    assertResponse(
        post(
            "http://localhost:5001/users",
            auth=auth.HTTPBasicAuth("admin", "x"),
            json=dict(username=NEXUS_USERNAME, password=NEXUS_PASSWORD),
        )
    )
    # make a ebics bank connection for the new user.
    assertResponse(
        post(
            "http://localhost:5001/bank-connections",
            json=dict(
                name=NEXUS_BANK_CONNECTION,
                source="new",
                type="ebics",
                data=dict(
                    ebicsURL=EBICS_URL,
                    hostID=EBICS_HOST,
                    partnerID=EBICS_PARTNER,
                    userID=EBICS_USER
                ),
            ),
            auth=NEXUS_AUTH
        )
    )
    # synchronizing the connection
    assertResponse(
        post(
            "http://localhost:5001/bank-connections/my-ebics/connect",
            json=dict(),
            auth=NEXUS_AUTH
        )
    )
    # download offered bank accounts
    assertResponse(
        post(
            "http://localhost:5001/bank-connections/my-ebics/fetch-accounts",
            json=dict(),
            auth=NEXUS_AUTH
        )
    )
    # import one bank account into the Nexus
    assertResponse(
        post(
            "http://localhost:5001/bank-connections/my-ebics/import-account",
            json=dict(
                offeredAccountId=BANK_LABEL,
                nexusBankAccountId=NEXUS_BANK_LABEL
            ),
            auth=NEXUS_AUTH
        )
    )

startNexus(NEXUS_DB)
startSandbox(SANDBOX_DB)

def setup_function():
    prepareSandbox()
    prepareNexus()

def teardown_function():
  flushTablesNexus(NEXUS_DB)
  flushTablesSandbox(SANDBOX_DB)

def test_empty_history():
    resp = assertResponse(
        get(
            f"http://localhost:5001/bank-accounts/{NEXUS_BANK_LABEL}/transactions",
            auth=NEXUS_AUTH
        )
    )
    assert len(resp.json().get("transactions")) == 0
