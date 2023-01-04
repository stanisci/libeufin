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

import io.ktor.server.application.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.util.*
import java.security.interfaces.RSAPublicKey
import java.util.*
import java.util.zip.DeflaterInputStream

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

/**
 * DB helper inserting a new "account" into the database.
 * The account is made of a 'customer' and 'bank account'
 * object.  The helper checks first that the username is
 * acceptable (chars, no institutional names, available
 * names); then checks that IBAN is available and then adds
 * the two database objects under the given demobank.  This
 * function contains the common logic shared by the Access
 * and Circuit API.  Additional data that is peculiar to one
 * API should be added separately.
 *
 * It returns a AccountPair type.  That contains the customer
 * object and the bank account; the caller may this way add custom
 * values to them.  */
data class AccountPair(
    val customer: DemobankCustomerEntity,
    val bankAccount: BankAccountEntity
)
fun insertNewAccount(username: String,
                     password: String,
                     name: String? = null, // tests do not usually give one.
                     iban: String? = null,
                     isPublic: Boolean = false,
                     demobank: String = "default"): AccountPair {
    requireValidResourceName(username)
    // Forbid institutional usernames.
    if (username == "bank" || username == "admin") {
        logger.info("Username: $username not allowed.")
        throw forbidden("Username: $username is not allowed.")
    }
    return transaction {
        val demobankFromDb = getDemobank(demobank)
        // Bank's fault, because when this function gets
        // called, the demobank must exist.
        if (demobankFromDb == null) {
            logger.error("Demobank '$demobank' not found.  Won't add account $username")
            throw internalServerError("Demobank $demobank not found.  Won't add account $username")
        }
        // Generate a IBAN if the caller didn't provide one.
        val newIban = iban ?: getIban()
        // Check IBAN collisions.
        val checkIbanExist = BankAccountEntity.find(BankAccountsTable.iban eq newIban).firstOrNull()
        if (checkIbanExist != null) {
            logger.info("IBAN $newIban not available.  Won't register username $username")
            throw conflict("IBAN $iban not available.")
        }
        // Check username availability.
        val checkCustomerExist = DemobankCustomerEntity.find {
            DemobankCustomersTable.username eq username
        }.firstOrNull()
        if (checkCustomerExist != null) {
            throw SandboxError(
                HttpStatusCode.Conflict,
                "Username $username not available."
            )
        }
        val newCustomer = DemobankCustomerEntity.new {
            this.username = username
            passwordHash = CryptoUtil.hashpw(password)
            this.name = name // nullable
        }
        // Actual account creation.
        val newBankAccount = BankAccountEntity.new {
            this.iban = newIban
            /**
             * For now, keep same semantics of Pybank: a username
             * is AS WELL a bank account label.  In other words, it
             * identifies a customer AND a bank account.  The reason
             * to have the two values (label and owner) is to allow
             * multiple bank accounts being owned by one customer.
             */
            label = username
            owner = username
            this.demoBank = demobankFromDb
            this.isPublic = isPublic
        }
        if (demobankFromDb.withSignupBonus)
            newBankAccount.bonus("${demobankFromDb.currency}:100")
        AccountPair(customer = newCustomer, bankAccount = newBankAccount)
    }
}

/**
 * Return true if access to the bank account can be granted,
 * false otherwise.
 *
 * Given the policy of having bank account names matching
 * their owner's username, this function enforces such policy
 * with the exception that 'admin' can access every bank
 * account.  A null username indicates disabled authentication
 * checks, hence it grants the access.
 */
fun allowOwnerOrAdmin(username: String?, bankAccountLabel: String): Boolean {
    if (username == null) return true
    if (username == "admin") return true
    return username == bankAccountLabel
}

/**
 * Throws exception if the credentials are wrong.
 *
 * Return:
 * - null if the authentication is disabled (during tests, for example).
 *   This facilitates tests because allows requests to lack entirely a
 *   Authorization header.
 * - the username of the authenticated user
 * - throw exception when the authentication fails
 *
 * Note: at this point it is ONLY checked whether the user provided
 * a valid password for the username mentioned in the Authorization header.
 * The actual access to the resources must be later checked by each handler.
 */
fun ApplicationRequest.basicAuth(onlyAdmin: Boolean = false): String? {
    val withAuth = this.call.ensureAttribute(WITH_AUTH_ATTRIBUTE_KEY)
    if (!withAuth) {
        logger.info("Authentication is disabled - assuming tests currently running.")
        return null
    }
    val credentials = getHTTPBasicAuthCredentials(this)
    if (credentials.first == "admin") {
        // env must contain the admin password, because --with-auth is true.
        val adminPassword: String = this.call.ensureAttribute(ADMIN_PASSWORD_ATTRIBUTE_KEY)
        if (credentials.second != adminPassword) throw unauthorized(
            "Admin authentication failed"
        )
        return credentials.first
    }
    if (onlyAdmin) throw forbidden("Only admin allowed.")
    val passwordHash = transaction {
        val customer = getCustomer(credentials.first)
        customer.passwordHash
    }
    if (!CryptoUtil.checkPwOrThrow(credentials.second, passwordHash))
        throw unauthorized("Customer '${credentials.first}' gave wrong credentials")
    return credentials.first
}

fun sandboxAssert(condition: Boolean, reason: String) {
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

fun getHistoryElementFromTransactionRow(dbRow: BankAccountTransactionEntity): RawPayment {
    return RawPayment(
        subject = dbRow.subject,
        creditorIban = dbRow.creditorIban,
        creditorBic = dbRow.creditorBic,
        creditorName = dbRow.creditorName,
        debtorIban = dbRow.debtorIban,
        debtorBic = dbRow.debtorBic,
        debtorName = dbRow.debtorName,
        date = importDateFromMillis(dbRow.date).toDashedDate(),
        amount = dbRow.amount,
        currency = dbRow.currency,
        // The line below produces a value too long (>35 chars),
        // and dbRow makes the document invalid!
        // uid = "${dbRow.pmtInfId}-${it.msgId}"
        uid = dbRow.accountServicerReference,
        direction = dbRow.direction,
        pmtInfId = dbRow.pmtInfId
    )
}

fun getHistoryElementFromTransactionRow(
    dbRow: BankAccountFreshTransactionEntity
): RawPayment {
    return getHistoryElementFromTransactionRow(dbRow.transactionRef)
}

/**
 * Need to be called within a transaction {} block.  It
 * is acceptable to pass a bank account's label as the
 * parameter, because usernames can only own one bank
 * account whose label equals the owner's username.
 *
 * Future versions may relax this policy to allow one
 * customer to own multiple bank accounts.
 */
fun getCustomer(username: String): DemobankCustomerEntity {
    return transaction {
        DemobankCustomerEntity.find {
            DemobankCustomersTable.username eq username
        }.firstOrNull()
    } ?: throw notFound("Customer '${username}' not found")
}

/**
 * Get person name from a customer's username, or throw
 * exception if not found.
 */
fun getPersonNameFromCustomer(customerUsername: String): String {
    return when (customerUsername) {
        "admin" -> "Admin"
        else -> transaction {
            val ownerCustomer = DemobankCustomerEntity.find(
                DemobankCustomersTable.username eq customerUsername
            ).firstOrNull() ?: run {
                logger.error("Customer '${customerUsername}' not found, couldn't get their name.")
                throw SandboxError(
                    HttpStatusCode.InternalServerError,
                    "'$customerUsername' not a customer."
                )

            }
            ownerCustomer.name ?: "Never given."
        }
    }
}

fun getDefaultDemobank(): DemobankConfigEntity {
    return transaction {
        DemobankConfigEntity.find {
            DemobankConfigsTable.name eq "default"
        }.firstOrNull()
    } ?: throw SandboxError(
        HttpStatusCode.InternalServerError,
        "Default demobank is missing."
    )
}

fun getWithdrawalOperation(opId: String): TalerWithdrawalEntity {
    return transaction {
        TalerWithdrawalEntity.find {
            TalerWithdrawalsTable.wopid eq UUID.fromString(opId)
        }.firstOrNull() ?: throw SandboxError(
            HttpStatusCode.NotFound, "Withdrawal operation $opId not found."
        )
    }
}

fun getBankAccountFromPayto(paytoUri: String): BankAccountEntity {
    val paytoParse = parsePayto(paytoUri)
    return getBankAccountFromIban(paytoParse.iban)
}

fun getBankAccountFromIban(iban: String): BankAccountEntity {
    return transaction {
        BankAccountEntity.find(BankAccountsTable.iban eq iban).firstOrNull()
    } ?: throw SandboxError(
        HttpStatusCode.NotFound,
        "Did not find a bank account for $iban"
    )
}

fun getBankAccountFromLabel(label: String, demobank: String = "default"): BankAccountEntity {
    val maybeDemobank = getDemobank(demobank)
    if (maybeDemobank == null) {
        logger.error("Demobank '$demobank' not found")
        throw SandboxError(
            HttpStatusCode.NotFound,
            "Demobank '$demobank' not found"
        )
    }
    return getBankAccountFromLabel(label, maybeDemobank)
}
fun getBankAccountFromLabel(label: String,
                            demobank: DemobankConfigEntity
): BankAccountEntity {
    return transaction {
        BankAccountEntity.find(
            BankAccountsTable.label eq label and (
                    BankAccountsTable.demoBank eq demobank.id
                    )
        ).firstOrNull() ?: throw SandboxError(
            HttpStatusCode.NotFound,
            "Did not find a bank account for label $label"
        )
    }
}

fun getBankAccountFromSubscriber(subscriber: EbicsSubscriberEntity): BankAccountEntity {
    return transaction {
        subscriber.bankAccount ?: throw SandboxError(
            HttpStatusCode.NotFound,
            "Subscriber doesn't have any bank account"
        )
    }
}

fun BankAccountEntity.bonus(amount: String) {
    wireTransfer(
        "admin",
        this.label,
        this.demoBank.name,
        "Sign-up bonus",
        amount
    )
}

fun ensureDemobank(call: ApplicationCall): DemobankConfigEntity {
    return ensureDemobank(call.getUriComponent("demobankid"))
}

fun ensureDemobank(name: String): DemobankConfigEntity {
    return transaction {
        DemobankConfigEntity.find {
            DemobankConfigsTable.name eq name
        }.firstOrNull() ?: throw notFound("Demobank '$name' not found.  Was it ever created?")
    }
}

fun getDemobank(name: String?): DemobankConfigEntity? {
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
            "Ebics subscriber (${userID}, ${partnerID}, ${hostID}) not found"
        )
    }
}

/**
 * This helper tries to:
 * 1.  Authenticate the client.
 * 2.  Extract the bank account's label from the request's path
 * 3.  Return the bank account DB object if the client has access to it.
 */
fun getBankAccountWithAuth(call: ApplicationCall): BankAccountEntity {
    val username = call.request.basicAuth()
    val accountAccessed = call.getUriComponent("account_name")
    val demobank = ensureDemobank(call)
    val bankAccount = getBankAccountFromLabel(accountAccessed, demobank)
    if (WITH_AUTH && (bankAccount.owner != username && username != "admin"))
        throw forbidden("Customer '$username' cannot access bank account '$accountAccessed'")
    return bankAccount
}

/**
 * Compress, encrypt, encode a EBICS payload.  The payload
 * is assumed to be a Zip archive with only one entry.
 * Return the customer key (second element) along the data.
 */
fun prepareEbicsPayload(
    payload: String, pub: RSAPublicKey
): Pair<String, CryptoUtil.EncryptionResult> {
    val zipSingleton = mutableListOf(payload.toByteArray()).zip()
    val compressedResponse = DeflaterInputStream(zipSingleton.inputStream()).use {
        it.readAllBytes()
    }
    val enc = CryptoUtil.encryptEbicsE002(compressedResponse, pub)
    return Pair(Base64.getEncoder().encodeToString(enc.encryptedData), enc)

}