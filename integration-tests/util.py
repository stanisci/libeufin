# Helpers for the integration tests.

from subprocess import check_call, Popen, PIPE, DEVNULL
import socket
from requests import post, get, auth
from time import sleep
from deepdiff import DeepDiff
import atexit
from pathlib import Path
import sys
import os

class EbicsDetails:
    def get_as_dict(self, with_url):
        ret = dict(
            hostID=self.host,
            partnerID=self.partner,
            userID=self.user
        )
        if with_url:
            ret.update(ebicsURL=self.service_url)
        return ret

    def __init__(self, service_url):
        self.service_url = service_url 
        self.host = "HOST01"
        self.partner = "PARTNER1"
        self.user = "USER1"
        self.version = "H004"

class BankingDetails:
    def __init__(
            self,
            bank_base_url,
            iban="GB33BUKB20201555555555",
            bic="BUKBGB22",
            label="savings",
            name="Oliver Smith"
        ):
        self.iban =  iban
        self.bic = bic
        self.label = label
        self.bank_base_url = bank_base_url
        self.name = name

class NexusDetails:
    def __init__(self, base_url):
        self.base_url = base_url
        self.username = "admin"
        self.password = "x"
        self.bank_connection = "my-ebics"
        self.bank_label = "local-savings" 
        self.auth = auth.HTTPBasicAuth(self.username, self.password)
        self.taler_facade_name = "taler-wire-gateway"

class LibeufinPersona:
    def __init__(self, banking_details, nexus_details, ebics_details):
        self.banking = banking_details
        self.nexus = nexus_details 
        self.ebics = ebics_details 

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


def assertJsonEqual(json1, json2):
    diff = DeepDiff(json1, json2, ignore_order=True, report_repetition=True)
    assert len(diff.keys()) == 0


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

def makeNexusSuperuser():
    check_call([
        "../gradlew",
        "-q", "--console=plain",
        "-p", "..",
        "nexus:run",
        f"--args=superuser admin --password x",
    ])

def dropSandboxTables():
    check_call([
        "../gradlew",
        "-q", "--console=plain",
        "-p", "..",
        "sandbox:run",
        f"--args=reset-tables"
    ])


def compileLibeufin():
    check_call([
        "../gradlew",
        "-q", "--console=plain",
        "-p", "..",
        "assemble"
    ])

def dropNexusTables():
    check_call([
        "../gradlew",
        "-q", "--console=plain",
        "-p", "..",
        "nexus:run",
        f"--args=reset-tables"
    ])

def startSandbox():
    checkPort(5000)
    sandbox = Popen([
        "../gradlew",
        "-q",
        "-p",
        "..",
        "sandbox:run",
        "--console=plain",
        "--args=serve"],
        stdin=DEVNULL,
        stdout=open("sandbox-stdout.log", "w"),
        stderr=open("sandbox-stderr.log", "w")
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


def startNexus():
    checkPort(5001)
    nexus = Popen([
        "../gradlew",
        "-q",
        "-p",
        "..",
        "nexus:run",
        "--console=plain",
        "--args=serve"],
        stdin=DEVNULL,
        stdout=open("nexus-stdout.log", "w"),
        stderr=open("nexus-stderr.log", "w")
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

def assertResponse(r, acceptedResponses=[200]):
    def http_trace(r):
        request = f"{r.request.method} {r.request.url}"
        if r.request.body:
            request += f"\n{r.request.body.decode('utf-8')}"
        response = f"{r.status_code} {r.reason}\n{r.text}"
        return f"(the following communication failed)\n\n{request}\n\n{response}"
    assert r.status_code in acceptedResponses, http_trace(r)
    return r
