#!/usr/bin/env python3

# This minimal library checks only if the JSON values
# contains the expected fields, without actually checking
# if the fields' value match the API.

from util import CheckJsonField as F, CheckJsonTop as T

def checkNewUserRequest(json):
    c = T(F("username"), F("password"))
    return c.check(json)

def checkPreparePayment(json):
    c = T(
        F("iban"),
        F("bic"),
        F("name"),
        F("subject"),
        F("amount")
    )
    return c.check(json)

def checkPreparedPaymentResponse(json):
    c = T(F("uuid"))
    return c.check(json)

def checkTransaction(json):
    c = T(
            F("account"),
            F("counterpartIban"),
            F("counterpartBic"),
            F("counterpartName"),
            F("amount"),
            F("date"),
            F("subject")
    )

def checkNewEbicsConnection(json):
    c = T(
            F("source"),
            F("name"),
            F("type"),
            F("data", T(
                F("ebicsURL"),
                F("hostID"),
                F("partnerID"),
                F("userID"),
            ))
    )
    return c.check(json)

def checkImportAccount(json):
    c = T(F("nexusBankAccountId"),
          F("offeredAccountId"))
    return c.check(json)

def checkSandboxEbicsHosts(json):
    c = T(F("hostID"),
          F("ebicsVersion"))
    return c.check(json)

def checkSandboxEbicsSubscriber(json):
    c = T(F("hostID"),
          F("userID"),
          F("partnerID"),
          F("systemID", optional=True))
    return c.check(json)

def checkSandboxBankAccount(json):
    c = T(
        F("iban"),
        F("bic"),
        F("name"),
        F("subscriber"),
        F("label")
    )
    return c.check(json)

def checkBackupDetails(json):
    return T(F("passphrase")).check(json)
