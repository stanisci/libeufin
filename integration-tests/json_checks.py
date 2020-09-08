#!/usr/bin/env python3

# This minimal library checks only if the JSON values
# contains the expected fields, without actually checking
# if the fields' value match the API.

from util import CheckJsonField as F, CheckJsonTop as T

def checkNewUserRequest(json):
    c = T(F("username"), F("password"))
    return c.check(json)

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
    c = T(F("nexusBankAccountId"), F("offeredAccountId"))
    return c.check(json)
