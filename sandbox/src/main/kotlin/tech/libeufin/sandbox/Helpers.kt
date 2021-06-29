/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.sandbox

import com.google.common.io.Resources
import com.hubspot.jinjava.Jinjava
import com.hubspot.jinjava.lib.fn.ELFunctionDefinition
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

fun SandboxAssert(condition: Boolean, reason: String) {
    if (!condition) throw SandboxError(HttpStatusCode.InternalServerError, reason)
}

fun getOrderTypeFromTransactionId(transactionID: String): String {
    val uploadTransaction = transaction {
        EbicsUploadTransactionEntity.findById(transactionID)
    } ?: throw SandboxError(
        /**
         * NOTE: at this point, it might even be the server's fault.
         * For example, if it failed to store a ID earlier.
         */
        HttpStatusCode.NotFound,
        "Could not retrieve order type for transaction: $transactionID"
    )
    return uploadTransaction.orderType
}

fun getBankAccountFromIban(iban: String): BankAccountEntity {
    return transaction {
        BankAccountEntity.find(
            BankAccountsTable.iban eq iban
        )
    }.firstOrNull() ?: throw SandboxError(
        HttpStatusCode.NotFound,
        "Did not find a bank account for ${iban}"
    )
}

fun getBankAccountFromLabel(label: String): BankAccountEntity {
    return transaction {
        BankAccountEntity.find(
            BankAccountsTable.label eq label
        )
    }.firstOrNull() ?: throw SandboxError(
        HttpStatusCode.NotFound,
        "Did not find a bank account for label ${label}"
    )
}

fun getBankAccountFromSubscriber(subscriber: EbicsSubscriberEntity): BankAccountEntity {
    return transaction {
        subscriber.bankAccount ?: throw SandboxError(
            HttpStatusCode.NotFound,
            "Subscriber doesn't have any bank account"
        )
    }
}

/**
 * Fetch a configuration for Sandbox, corresponding to the host that runs the service.
 */
fun getSandboxConfig(hostname: String?): SandboxConfigEntity {
    var ret: SandboxConfigEntity? = transaction {
        if (hostname == null) {
            SandboxConfigEntity.all().firstOrNull()
        } else {
            SandboxConfigEntity.find {
                SandboxConfigsTable.hostname eq hostname
            }.firstOrNull()
        }
    }
    if (ret == null) throw SandboxError(
        HttpStatusCode.InternalServerError,
        "Serving from a non configured host"
    )
    return ret
}

fun getEbicsSubscriberFromDetails(userID: String, partnerID: String, hostID: String): EbicsSubscriberEntity {
    return transaction {
        EbicsSubscriberEntity.find {
            (EbicsSubscribersTable.userId eq userID) and (EbicsSubscribersTable.partnerId eq partnerID) and
                    (EbicsSubscribersTable.hostId eq hostID)
        }.firstOrNull() ?: throw SandboxError(
            HttpStatusCode.NotFound,
            "Ebics subscriber not found"
        )
    }
}

/**
 * FIXME: commenting out until a solution for i18n is found.
 *
private fun initJinjava(): Jinjava {
    class JinjaFunctions {
        // Used by templates to retrieve configuration values.
        fun settings_value(name: String): String {
            return "foo"
        }
        fun gettext(translatable: String): String {
            // temporary, just to make the compiler happy.
            return translatable
        }
        fun url(name: String): String {
            val map = mapOf<String, String>(
                "login" to "todo",
                "profile" to "todo",
                "register" to "todo",
                "public-accounts" to "todo"
            )
            return map[name] ?: throw SandboxError(HttpStatusCode.InternalServerError, "URL name unknown")
        }
    }
    val jinjava = Jinjava()
    val settingsValueFunc = ELFunctionDefinition(
        "tech.libeufin.sandbox", "settings_value",
        JinjaFunctions::class.java, "settings_value", String::class.java
    )
    val gettextFuncAlias = ELFunctionDefinition(
        "tech.libeufin.sandbox", "_",
        JinjaFunctions::class.java, "gettext", String::class.java
    )
    val gettextFunc = ELFunctionDefinition(
        "", "gettext",
        JinjaFunctions::class.java, "gettext", String::class.java
    )
    val urlFunc = ELFunctionDefinition(
        "tech.libeufin.sandbox", "url",
        JinjaFunctions::class.java, "url", String::class.java
    )

    jinjava.globalContext.registerFunction(settingsValueFunc)
    jinjava.globalContext.registerFunction(gettextFunc)
    jinjava.globalContext.registerFunction(gettextFuncAlias)
    jinjava.globalContext.registerFunction(urlFunc)

    return jinjava
}

val jinjava = initJinjava()

fun renderTemplate(templateName: String, context: Map<String, String>): String {
    val template = Resources.toString(Resources.getResource(
        "templates/$templateName"), Charsets.UTF_8
    )
    return jinjava.render(template, context)
} **/