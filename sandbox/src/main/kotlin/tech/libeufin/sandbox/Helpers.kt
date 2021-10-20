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

import io.ktor.application.*
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.util.*
import javax.security.auth.Subject

/**
 * Helps to communicate Camt values without having
 * to parse the XML each time one is needed.
 */
data class SandboxCamt(
    val camtMessage: String,
    val messageId: String,
    /**
     * That is the number of SECONDS since Epoch.  This
     * value is exactly what goes into the Camt document.
     */
    val creationTime: Long
)

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

/**
 * Book a CRDT and a DBIT transaction and return the unique reference thereof.
 *
 * At the moment there is redundancy because all the creditor / debtor details
 * are contained (directly or indirectly) already in the BankAccount parameters.
 *
 * This is kept both not to break the existing tests and to allow future versions
 * where one party of the transaction is not a customer of the running Sandbox.
 */

fun wireTransfer(
    debitAccount: BankAccountEntity,
    creditAccount: BankAccountEntity,
    demoBank: DemobankConfigEntity,
    subject: String,
    amount: String,
): String {

    fun getOwnerName(ownerUsername: String): String {
        return if (creditAccount.owner == "admin") "admin" else {
            val creditorCustomer = DemobankCustomerEntity.find(
                DemobankCustomersTable.username eq creditAccount.owner
            ).firstOrNull() ?: throw internalServerError(
                "Owner of bank account '${creditAccount.label}' not found"
            )
            creditorCustomer.name ?: "Name not given"
        }
    }
    val timeStamp = getUTCnow().toInstant().toEpochMilli()
    val transactionRef = getRandomString(8)
    transaction {
        BankAccountTransactionEntity.new {
            creditorIban = creditAccount.iban
            creditorBic = creditAccount.bic
            this.creditorName = getOwnerName(creditAccount.owner)
            debtorIban = debitAccount.iban
            debtorBic = debitAccount.bic
            debtorName = getOwnerName(debitAccount.owner)
            this.subject = subject
            this.amount = amount
            this.currency = demoBank.currency
            date = timeStamp
            accountServicerReference = transactionRef
            account = creditAccount
            direction = "CRDT"
        }
        BankAccountTransactionEntity.new {
            creditorIban = creditAccount.iban
            creditorBic = creditAccount.bic
            this.creditorName = getOwnerName(creditAccount.owner)
            debtorIban = debitAccount.iban
            debtorBic = debitAccount.bic
            debtorName = getOwnerName(debitAccount.owner)
            this.subject = subject
            this.amount = amount
            this.currency = demoBank.currency
            date = timeStamp
            accountServicerReference = transactionRef
            account = debitAccount
            direction = "DBIT"
        }
    }
    return transactionRef
}


fun getBankAccountFromPayto(paytoUri: String): BankAccountEntity {
    val paytoParse = parsePayto(paytoUri)
    return getBankAccountFromIban(paytoParse.iban)

}

fun getBankAccountFromIban(iban: String): BankAccountEntity {
    return transaction {
        BankAccountEntity.find(BankAccountsTable.iban eq iban)
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

fun ensureDemobank(call: ApplicationCall): DemobankConfigEntity {
    return ensureDemobank(call.getUriComponent("demobankid"))
}

private fun ensureDemobank(name: String): DemobankConfigEntity {
    return transaction {
        val res = DemobankConfigEntity.find {
            DemobankConfigsTable.name eq name
        }.firstOrNull()
        if (res == null) throw internalServerError("Demobank '$name' never created")
        res
    }
}

fun getSandboxConfig(name: String?): DemobankConfigEntity? {
    return transaction {
        if (name == null) {
            DemobankConfigEntity.all().firstOrNull()
        } else {
            DemobankConfigEntity.find {
                DemobankConfigsTable.name eq name
            }.firstOrNull()
        }
    }
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