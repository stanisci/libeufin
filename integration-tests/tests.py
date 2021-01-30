#!/usr/bin/env python3

import os
import pytest
import json
from deepdiff import DeepDiff as dd
from subprocess import check_call
from requests import post, get, auth, delete
from time import sleep
from util import (
    startNexus,
    startSandbox,
    assertResponse,
    makeNexusSuperuser,
    dropSandboxTables,
    dropNexusTables,
    assertJsonEqual,
    LibeufinPersona,
    BankingDetails,
    NexusDetails,
    EbicsDetails,
    compileLibeufin
)

# Database
DB = "jdbc:sqlite:/tmp/libeufintestdb"
SANDBOX_URL = "http://localhost:5000"
NEXUS_URL = "http://localhost:5001"

PERSONA = LibeufinPersona(
    banking_details = BankingDetails(SANDBOX_URL),
    nexus_details = NexusDetails(NEXUS_URL),
    ebics_details = EbicsDetails(SANDBOX_URL + "/ebicsweb")
)

os.environ["LIBEUFIN_NEXUS_DB_CONNECTION"] = DB
os.environ["LIBEUFIN_SANDBOX_DB_CONNECTION"] = DB

def prepareSandbox():
    # make ebics host at sandbox
    assertResponse(
        post(
            f"{PERSONA.banking.bank_base_url}/admin/ebics/hosts",
            json=dict(hostID=PERSONA.ebics.host, ebicsVersion=PERSONA.ebics.version),
        )
    )
    # make new ebics subscriber at sandbox
    assertResponse(
        post(
            f"{PERSONA.banking.bank_base_url}/admin/ebics/subscribers",
            json=PERSONA.ebics.get_as_dict(with_url=False),
        )
    )
    # give a bank account to such subscriber, at sandbox
    assertResponse(
        post(
            f"{PERSONA.banking.bank_base_url}/admin/ebics/bank-accounts",
            json=dict(
                name=PERSONA.banking.name,
                subscriber=PERSONA.ebics.get_as_dict(with_url=False),
                iban=PERSONA.banking.iban,
                bic=PERSONA.banking.bic,
                label=PERSONA.banking.label,
                currency="EUR"
            )
        )
    )

def prepareNexus():
    makeNexusSuperuser()
    # make a new nexus user.
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/users",
            auth=auth.HTTPBasicAuth("admin", "x"),
            json=dict(username=PERSONA.nexus.username, password=PERSONA.nexus.password),
        )
    )
    # make a ebics bank connection for the new user.
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-connections",
            json=dict(
                name=PERSONA.nexus.bank_connection,
                source="new",
                type="ebics",
                data=PERSONA.ebics.get_as_dict(with_url=True),
            ),
            auth=PERSONA.nexus.auth
        )
    )
    # synchronizing the connection
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-connections/{PERSONA.nexus.bank_connection}/connect",
            json=dict(),
            auth=PERSONA.nexus.auth
        )
    )
    # download offered bank accounts
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-connections/{PERSONA.nexus.bank_connection}/fetch-accounts",
            json=dict(),
            auth=PERSONA.nexus.auth
        )
    )
    # import one bank account into the Nexus
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-connections/{PERSONA.nexus.bank_connection}/import-account",
            json=dict(
                offeredAccountId=PERSONA.banking.label,
                nexusBankAccountId=PERSONA.nexus.bank_label
            ),
            auth=PERSONA.nexus.auth
        )
    )

compileLibeufin()
dropSandboxTables()
startSandbox()
dropNexusTables()
startNexus()

def setup_function():
    try:
        prepareSandbox()
        prepareNexus()
    except Exception:
        teardown_function()
        assert False, "Setup function failed"

def teardown_function():
    dropSandboxTables()
    dropNexusTables()

# def test_double_username():


def test_change_nonadmin_password():
    assertResponse(
        post(f"{PERSONA.nexus.base_url}/users",
             auth=PERSONA.nexus.auth,
             json=dict(username="nonadmin", password="secret")
        )
    )

    resp = assertResponse(
        get(
            f"{PERSONA.nexus.base_url}/bank-accounts",
            auth=auth.HTTPBasicAuth("nonadmin", "secret")
        )
    )

    assertResponse(
        post(f"{PERSONA.nexus.base_url}/users/password",
             auth=auth.HTTPBasicAuth("nonadmin", "secret"),
             json=dict(newPassword="changed")
        )
    )

    resp = assertResponse(
        get(
            f"{PERSONA.nexus.base_url}/bank-accounts",
            auth=auth.HTTPBasicAuth("nonadmin", "changed")
        )
    )


def test_connection_deletion():
    resp = assertResponse(
        get(
            f"{PERSONA.nexus.base_url}/bank-connections",
            auth=PERSONA.nexus.auth
        )
    )
    connection = resp.json().get("bankConnections").pop()
    assert PERSONA.nexus.bank_connection == connection.get("name")

    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-connections/delete-connection",
            json=dict(bankConnectionId=PERSONA.nexus.bank_connection),
            auth=PERSONA.nexus.auth
        )
    )
    resp = assertResponse(
        get(
            f"{PERSONA.nexus.base_url}/bank-connections",
            auth=PERSONA.nexus.auth
        )
    )
    assert len(resp.json().get("bankConnections")) == 0

# Tests whether Nexus knows the imported bank account.
def test_imported_account():
    resp = assertResponse(
        get(
            f"{PERSONA.nexus.base_url}/bank-connections/{PERSONA.nexus.bank_connection}/accounts",
            auth=PERSONA.nexus.auth
        )
    )
    imported_account = resp.json().get("accounts").pop()
    assert imported_account.get("nexusBankAccountId") == PERSONA.nexus.bank_label

# Expecting a empty history for an account that
# never made or receivd a payment.
def test_empty_history():
    resp = assertResponse(
        get(
            f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/transactions",
            auth=PERSONA.nexus.auth
        )
    )
    assert len(resp.json().get("transactions")) == 0

# This test checks the bank connection backup export+import 
# However the restored connection is never sent through the
# "/connect" step.
def test_backup():
    resp = assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-connections/{PERSONA.nexus.bank_connection}/export-backup",
            json=dict(passphrase="secret"),
            auth=PERSONA.nexus.auth
        )
    )
    sleep(3)
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-connections",
            json=dict(name="my-ebics-restored", data=resp.json(), passphrase="secret", source="backup"),
            auth=PERSONA.nexus.auth
        )
    )

def test_ebics_custom_ebics_order():
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-connections/{PERSONA.nexus.bank_connection}/ebics/download/tsd",
            auth=PERSONA.nexus.auth
        )
    )

# This test makes a payment and expects to see it
# in the account history.
def test_payment():
    resp = assertResponse(post(
        f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/payment-initiations",
        json=dict(
            iban="FR7630006000011234567890189",
            bic="AGRIFRPP",
            name="Jacques La Fayette",
            subject="integration test",
            amount="EUR:1",
        ),
        auth=PERSONA.nexus.auth
    ))
    PAYMENT_UUID = resp.json().get("uuid")
    assertResponse(post("/".join([
        PERSONA.nexus.base_url,
        "bank-accounts",
        PERSONA.nexus.bank_label,
        "payment-initiations",
        PAYMENT_UUID,
        "submit"]),
        json=dict(),
        auth=PERSONA.nexus.auth
    ))
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/fetch-transactions",
            auth=PERSONA.nexus.auth
        )
    )
    resp = assertResponse(get(
        f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/transactions",
        auth=PERSONA.nexus.auth
    ))
    assert len(resp.json().get("transactions")) == 1

    # assert now that the bank booked the payment.
    resp = assertResponse(get("/".join([
        PERSONA.nexus.base_url,
        "bank-accounts",
        PERSONA.nexus.bank_label,
        "payment-initiations",
        PAYMENT_UUID]),
        auth=PERSONA.nexus.auth
    ))
    assert resp.json()["status"] == "BOOK"

    # Posting a second payment initiation, but not submitting it.
    # It's expected to have a "null" status.
    resp = assertResponse(post(
        f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/payment-initiations",
        json=dict(
            iban="FR7630006000011234567890189",
            bic="AGRIFRPP",
            name="Jacques La Fayette",
            subject="integration test",
            amount="EUR:1",
        ),
        auth=PERSONA.nexus.auth
    ))
    PAYMENT_UUID_NON_SUBMITTED = resp.json().get("uuid")
    resp = assertResponse(get("/".join([
        PERSONA.nexus.base_url,
        "bank-accounts",
        PERSONA.nexus.bank_label,
        "payment-initiations",
        PAYMENT_UUID_NON_SUBMITTED]),
        auth=PERSONA.nexus.auth
    ))
    assert resp.json()["status"] == None


@pytest.fixture
def fetch_transactions():
    assertResponse(post(
        f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/fetch-transactions",
        auth=PERSONA.nexus.auth
    ))

# Book a incoming payment for "persona" at the Sandbox.
@pytest.fixture
def make_crdt_payment():
    payment_instruction = dict(
        creditorIban=PERSONA.banking.iban,
        creditorBic=PERSONA.banking.bic,
        creditorName=PERSONA.banking.name,
        debitorIban="FR00000000000000000000",
        debitorBic="BUKBGB22",
        debitorName="Max Mustermann",
        amount=5,
        currency="EUR",
        subject="Reimbursement",
        direction="CRDT"
    )

    assertResponse(post(
        f"{PERSONA.banking.bank_base_url}/admin/payments",
        json=payment_instruction
    ))


def test_deduplication(make_crdt_payment):
    # fetching twice the transactions and check that
    # the payment made via the fixture shows up only once.
    assertResponse(post(
        f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/fetch-transactions",
        auth=PERSONA.nexus.auth
    ))
    assertResponse(post(
        f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/fetch-transactions",
        auth=PERSONA.nexus.auth
    ))
    resp = assertResponse(get(
        f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/transactions",
        auth=PERSONA.nexus.auth
    ))
    assert len(resp.json().get("transactions")) == 1


@pytest.fixture
def make_taler_facade():
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/facades",
            json=dict(
                name=PERSONA.nexus.taler_facade_name,
                type="taler-wire-gateway",
                config=dict(
                    currency="EUR",
                    bankAccount=PERSONA.nexus.bank_label,
                    bankConnection=PERSONA.nexus.bank_connection,
                    reserveTransferLevel="UNUSED",
                )
            ),
            auth=PERSONA.nexus.auth
        )
    )


def test_taler_facade_config(make_taler_facade):
    resp = assertResponse(
        get(
            f"{PERSONA.nexus.base_url}/facades/{PERSONA.nexus.taler_facade_name}/taler-wire-gateway/config",
            auth=PERSONA.nexus.auth
        )
    )
    assertJsonEqual(
        resp.json(),
        dict(currency="EUR", version="0.0.0", name=PERSONA.nexus.taler_facade_name)
    )


def test_taler_facade_incoming(make_taler_facade):
    resp = assertResponse(post(
        f"{PERSONA.nexus.base_url}/facades/{PERSONA.nexus.taler_facade_name}/taler-wire-gateway/admin/add-incoming",
        json=dict(
            amount="EUR:1",
            reserve_pub="1BCZ7KA333E3YJBFWT4J173M3E713YGFFGD856KPSGZN1N8ZKZR0",
            debit_account="payto://iban/BUKBGB22/DE00000000000000000000?sender-name=TheName"
        ),
        auth=PERSONA.nexus.auth
    ))

    assertResponse(post(
        f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/fetch-transactions",
        auth=PERSONA.nexus.auth
    ))

    resp = assertResponse(get(
        "/".join([
            PERSONA.nexus.base_url,
            "facades",
            PERSONA.nexus.taler_facade_name,
            "taler-wire-gateway/history/incoming?delta=5"]),
        auth=PERSONA.nexus.auth
    ))
    assert len(resp.json().get("incoming_transactions")) == 1

def test_taler_facade_outgoing(make_taler_facade):
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/facades/{PERSONA.nexus.taler_facade_name}/taler-wire-gateway/transfer",
            json=dict(
                request_uid="0",
                amount="EUR:1",
                exchange_base_url="http//url",
                wtid="nice",
                credit_account="payto://iban/AGRIFRPP/FR7630006000011234567890189?receiver-name=theName"
            ),
            auth=PERSONA.nexus.auth
        )
    )
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/payment-initiations/1/submit",
            json=dict(),
            auth=PERSONA.nexus.auth
        )
    )
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/fetch-transactions",
            auth=PERSONA.nexus.auth
        )
    )

    resp = assertResponse(
        get(
            f"{PERSONA.nexus.base_url}/facades/{PERSONA.nexus.taler_facade_name}/taler-wire-gateway/history/outgoing?delta=5",
            auth=PERSONA.nexus.auth
        )
    )
    assert len(resp.json().get("outgoing_transactions")) == 1

def test_double_connection_name():
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-connections",
            json=dict(
                name=PERSONA.nexus.bank_connection,
                source="new",
                type="ebics",
                data=PERSONA.ebics.get_as_dict(with_url=True),
            ),
            auth=PERSONA.nexus.auth
        ),
        [406] # expecting "406 Not acceptable"
    )

def test_ingestion_camt53_non_singleton():
    with open("../parsing-tests/samples/camt53-gls-style-1.xml") as f:
        camt = f.read()
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/test-camt-ingestion/C53",
            auth=PERSONA.nexus.auth,
            data=camt
        )
    )
    resp = assertResponse(
        get(
            f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/transactions",
            auth=PERSONA.nexus.auth
        )
    )
    with open("../parsing-tests/samples/camt53-gls-style-1.json") as f:
        expected_txs = f.read()
    assert not dd(resp.json(), json.loads(expected_txs), ignore_order=True)


def test_ingestion_camt53():
    with open("../parsing-tests/samples/camt53-gls-style-0.xml") as f:
        camt = f.read()
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/test-camt-ingestion/C53",
            auth=PERSONA.nexus.auth,
            data=camt
        )
    )
    resp = assertResponse(
        get(
            f"{PERSONA.nexus.base_url}/bank-accounts/{PERSONA.nexus.bank_label}/transactions",
            auth=PERSONA.nexus.auth
        )
    )
    with open("../parsing-tests/samples/camt53-gls-style-0.json") as f:
        expected_txs = f.read()
    assert not dd(resp.json(), json.loads(expected_txs), ignore_order=True)

def test_sandbox_camt():
    payment_instruction = dict(
        creditorIban="GB33BUKB20201555555555",
        creditorBic="BUKBGB22",
        creditorName="Oliver Smith",
        debitorIban="FR00000000000000000000",
        debitorBic="BUKBGB22",
        debitorName="Max Mustermann",
        amount=5,
        currency="EUR",
        subject="Reimbursement",
        direction="CRDT"
    )
    assertResponse(
        post(
            f"{PERSONA.banking.bank_base_url}/admin/payments",
            json=payment_instruction
        )
    )

    assertResponse(
        post(
            f"{PERSONA.banking.bank_base_url}/admin/payments/camt",
            json=dict(iban="GB33BUKB20201555555555", type=53)
        )
    )

def test_schedule_deletion():
    assertResponse(
        post("/".join([
            PERSONA.nexus.base_url,
            "bank-accounts",
            PERSONA.nexus.bank_label,
            "schedule"]),
            json=dict(
                name="test-task",
                cronspec="* * *",
                type="fetch",
                params=dict(rangeType="all", level="all")),
            auth=auth.HTTPBasicAuth("admin", "x")
        )
    )

    resp = assertResponse(
        get("/".join([
            PERSONA.nexus.base_url,
            "bank-accounts",
            PERSONA.nexus.bank_label,
            "schedule",
            "test-task"]),
            auth=auth.HTTPBasicAuth("admin", "x")
        )
    )
    assert resp.json().get("taskName") == "test-task"

    assertResponse(
        delete("/".join([
            PERSONA.nexus.base_url,
            "bank-accounts",
            PERSONA.nexus.bank_label,
            "schedule",
            "test-task"]),
            auth=auth.HTTPBasicAuth("admin", "x")
        )
    )

    resp = assertResponse(
        get("/".join([
            PERSONA.nexus.base_url,
            "bank-accounts",
            PERSONA.nexus.bank_label,
            "schedule",
            "test-task"]),
            auth=auth.HTTPBasicAuth("admin", "x")
        ),
        acceptedResponses=[404]
    )

def test_invalid_json():
    assertResponse(
        post(
            f"{PERSONA.nexus.base_url}/users",
            data="malformed",
            headers={"Content-Type": "application/json"},
            auth=auth.HTTPBasicAuth("admin", "x")),
        acceptedResponses=[400]
    )
    assertResponse(
        post(
            f"{PERSONA.banking.bank_base_url}/admin/ebics/hosts",
            data="malformed",
            headers={"Content-Type": "application/json"},
            auth=auth.HTTPBasicAuth("admin", "x")),
        acceptedResponses=[400]
    )
