# Helpers for the integration tests.

from subprocess import check_call, Popen, PIPE, DEVNULL
import socket
from requests import post, get
from time import sleep
import atexit
from pathlib import Path
import sys
import os

class CheckJsonField:
    def __init__(self, name, nested=None, optional=False):
        self.name = name
        self.nested = nested
        self.optional = optional

    def check(self, json):
        if self.name not in json and not self.optional:
            print(f"'{self.name}' not found in the JSON: {json}.")
            sys.exit(1)
        if self.nested:
            self.nested.check(json.get(self.name))

class CheckJsonTop:
    def __init__(self, *args):
        self.checks = args

    def check(self, json):
        for check in self.checks:
            check.check(json)
        return json

def checkPort(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("0.0.0.0", port))
        s.close()
    except:
        print(f"Port {port} is not available")
        print(sys.exc_info()[0])
        exit(77)

def kill(name, s):
    s.terminate()
    s.wait()

def removeStaleDatabase(dbName):
    db_full_path = str(Path.cwd() / dbName)
    if os.path.exists(db_full_path):
        os.remove(db_full_path)

def makeNexusSuperuser(dbName):
    db_full_path = str(Path.cwd() / dbName)
    check_call(
        [
            "../gradlew",
            "-q",
            "-p",
            "..",
            "nexus:run",
            "--console=plain",
            f"--args=superuser admin --password x --db-name={db_full_path}",
        ]
    )

def flushTablesSandbox(dbName):
    db_full_path = str(Path.cwd() / dbName)
    check_call(
        ["sqlite3",
         db_full_path,
         "DELETE FROM BankAccountReports",
         "DELETE FROM EbicsOrderSignatures",
         "DELETE FROM BankAccountStatements",
         "DELETE FROM EbicsSubscriberPublicKeys",
         "DELETE FROM BankAccountTransactions",
         "DELETE FROM EbicsSubscribers",
         "DELETE FROM BankAccounts",
         "DELETE FROM EbicsUploadTransactionChunks",
         "DELETE FROM EbicsDownloadTransactions",
         "DELETE FROM EbicsUploadTransactions",
         "DELETE FROM EbicsHosts"
        ]
     )

def flushTablesNexus(dbName):
    db_full_path = str(Path.cwd() / dbName)
    check_call(
        ["sqlite3",
         db_full_path,
         "DELETE FROM EbicsSubscribers",
         "DELETE FROM NexusBankTransactions",
         "DELETE FROM TalerFacadeState",
         "DELETE FROM Facades",
         "DELETE FROM NexusScheduledTasks",
         "DELETE FROM TalerIncomingPayments",
         "DELETE FROM NexusBankAccounts",
         "DELETE FROM NexusUsers",
         "DELETE FROM TalerRequestedPayments",
         "DELETE FROM NexusBankConnections",
         "DELETE FROM OfferedBankAccounts",
         "DELETE FROM NexusBankMessages",
         "DELETE FROM PaymentInitiations"
        ]
    )

def startSandbox(dbName="sandbox-test.sqlite3"):
    db_full_path = str(Path.cwd() / dbName)
    check_call(["rm", "-f", db_full_path])
    check_call(["../gradlew", "-q", "-p", "..", "sandbox:assemble"])
    checkPort(5000)
    sandbox = Popen(
        ["../gradlew", "-q", "-p", "..", "sandbox:run", "--console=plain", "--args=serve --db-name={}".format(db_full_path)],
        stdin=DEVNULL,
        stdout=open("sandbox-stdout.log", "w"),
        stderr=open("sandbox-stderr.log", "w"),
    )
    atexit.register(lambda: kill("sandbox", sandbox))
    for i in range(10):
        try:
            get("http://localhost:5000/")
        except:
            if i == 9:
                stdout, stderr = sandbox.communicate()
                print("Sandbox timed out")
                print("{}\n{}".format(stdout.decode(), stderr.decode()))
                exit(77)
            sleep(2)
            continue
        break


def startNexus(dbName="nexus-test.sqlite3"):
    db_full_path = str(Path.cwd() / dbName)
    check_call(["rm", "-f", "--", db_full_path])
    check_call(
        ["../gradlew", "-q", "-p", "..", "nexus:assemble",]
    )
    checkPort(5001)
    nexus = Popen(
        [
            "../gradlew",
            "-q",
            "-p",
            "..",
            "nexus:run",
            "--console=plain",
            "--args=serve --db-name={}".format(db_full_path),
        ],
        stdin=DEVNULL,
        stdout=open("nexus-stdout.log", "w"),
        stderr=open("nexus-stderr.log", "w"),
    )
    atexit.register(lambda: kill("nexus", nexus))
    for i in range(80):
        try:
            get("http://localhost:5001/")
        except:
            if i == 79:
                nexus.terminate()
                print("Nexus timed out")
                exit(77)
            sleep(1)
            continue
        break
    return nexus

def assertResponse(response, acceptedResponses=[200]):
    assert response.status_code in acceptedResponses
    return response
