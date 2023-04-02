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

package tech.libeufin.nexus.bankaccount

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.ApplicationCall
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.*
import tech.libeufin.nexus.iso20022.*
import tech.libeufin.nexus.server.*
import tech.libeufin.nexus.xlibeufinbank.processXLibeufinBankMessage
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.internalServerError
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

private val keepBankMessages: String? = System.getenv("LIBEUFIN_NEXUS_KEEP_BANK_MESSAGES")

/**
 * Gets a prepared payment starting from its 'payment information id'.
 * Note: although the terminology comes from CaMt, a 'payment information id'
 * is indeed any UID that identifies the payment.  For this reason, also
 * the x-libeufin-bank logic uses this helper.
 *
 * Returns the prepared payment, or null if that's not found.  Not throwing
 * any exception because the null case is common: not every transaction being
 * processed by Neuxs was prepared/initiated here; incoming transactions are
 * one example.
 */
fun getPaymentInitiation(pmtInfId: String): PaymentInitiationEntity? =
    transaction {
        PaymentInitiationEntity.find(
            PaymentInitiationsTable.paymentInformationId.eq(pmtInfId)
        ).firstOrNull()
    }
fun requireBankAccount(call: ApplicationCall, parameterKey: String): NexusBankAccountEntity {
    val name = call.parameters[parameterKey]
    if (name == null)
        throw NexusError(
            HttpStatusCode.InternalServerError,
            "no parameter for bank account"
        )
    val account = transaction { NexusBankAccountEntity.findByName(name) }
    if (account == null) {
        throw NexusError(HttpStatusCode.NotFound, "bank connection '$name' not found")
    }
    return account
}

suspend fun submitPaymentInitiation(httpClient: HttpClient, paymentInitiationId: Long) {
    val r = transaction {
        val paymentInitiation = PaymentInitiationEntity.findById(paymentInitiationId)
        if (paymentInitiation == null) {
            throw NexusError(HttpStatusCode.NotFound, "prepared payment not found")
        }
        object {
            val type = paymentInitiation.bankAccount.defaultBankConnection?.type
            val submitted = paymentInitiation.submitted
        }
    }
    // Skips, if the payment was sent once already.
    if (r.submitted) {
        return
    }
    if (r.type == null)
        throw NexusError(HttpStatusCode.NotFound, "no default bank connection")

    getConnectionPlugin(r.type).submitPaymentInitiation(httpClient, paymentInitiationId)
}

/**
 * Submit all pending prepared payments.
 */
suspend fun submitAllPaymentInitiations(
    httpClient: HttpClient,
    accountid: String
) {
    data class Submission(val id: Long)
    val workQueue = mutableListOf<Submission>()
    transaction {
        val account = NexusBankAccountEntity.findByName(accountid) ?: throw NexusError(
            HttpStatusCode.NotFound,
            "account not found"
        )
        /**
         * Skip submitted and invalid preparations.
         */
        PaymentInitiationEntity.find {
            // Not submitted.
            (PaymentInitiationsTable.submitted eq false) and
                    // From the correct bank account.
            (PaymentInitiationsTable.bankAccount eq account.id)
        }.forEach {
            if (it.invalid == true) return@forEach
            val defaultBankConnectionId = it.bankAccount.defaultBankConnection?.id ?: throw NexusError(
                HttpStatusCode.NotFound,
                "Default bank connection not found.  Can't submit Pain document"
            )
            // Rare, but filter out bank accounts without a bank connection.
            val bankConnection = NexusBankConnectionEntity.findById(defaultBankConnectionId) ?: throw NexusError(
                HttpStatusCode.InternalServerError,
                "Bank connection '$defaultBankConnectionId' " +
                        "(pointed by bank account '${it.bankAccount.bankAccountName}')" +
                        " not found in the database."
            )
            try { BankConnectionType.parseBankConnectionType(bankConnection.type) }
            catch (e: Exception) {
                logger.info("Skipping non-implemented bank connection '${bankConnection.type}'")
                return@forEach
            }
            workQueue.add(Submission(it.id.value))
        }
    }
    workQueue.forEach { submitPaymentInitiation(httpClient, it.id) }
}

/**
 * NOTE: this type can be used BOTH for one Camt document OR
 * for a set of those.
 */
data class IngestedTransactionsCount(
    /**
     * Number of transactions that are new to the database.
     * Note that transaction T can be downloaded multiple times;
     * for example, once in a C52 and once - maybe a day later -
     * in a C53.  The second time, the transaction is not considered
     * 'new'.
     */
    val newTransactions: Int,

    /**
     * Total number of transactions that were included in a report
     * or a statement.
     */
    val downloadedTransactions: Int,
    /**
     * Exceptions occurred while fetching transactions.  Fetching
     * transactions can be done via multiple EBICS messages, therefore
     * a failing one should not prevent other messages to be fetched.
     * This list collects all the exceptions that happened while fetching
     * multiple messages.
     */
    var errors: List<Exception>? = null
)

/**
 * Causes new Nexus transactions to be stored into the database.  Note:
 * this function does NOT parse itself the banking data but relies on the
 * dedicated helpers.  This function is mostly responsible for _iterating_
 * over the new downloaded messages and update the local bank account about
 * the new data.
 */
fun ingestBankMessagesIntoAccount(
    bankConnectionId: String,
    bankAccountId: String
): IngestedTransactionsCount {
    var totalNew = 0
    var downloadedTransactions = 0
    transaction {
        val conn =
            NexusBankConnectionEntity.find {
                NexusBankConnectionsTable.connectionId eq bankConnectionId
            }.firstOrNull()
        if (conn == null) {
            throw NexusError(HttpStatusCode.InternalServerError, "connection not found")
        }
        val acct = NexusBankAccountEntity.findByName(bankAccountId)
        if (acct == null) {
            throw NexusError(HttpStatusCode.InternalServerError, "account not found")
        }
        var lastId = acct.highestSeenBankMessageSerialId
        /**
         * This block picks all the new messages that were downloaded
         * from the bank and passes them to the deeper banking data handlers
         * according to the connection type.  Such handlers are then responsible
         * to extract the interesting values and insert them into the database.
         */
        NexusBankMessageEntity.find {
            (NexusBankMessagesTable.bankConnection eq conn.id) and
                    (NexusBankMessagesTable.id greater acct.highestSeenBankMessageSerialId) and
                    not(NexusBankMessagesTable.errors)
        }.orderBy(
            Pair(NexusBankMessagesTable.id, SortOrder.ASC)
        ).forEach {
            val processingResult: IngestedTransactionsCount = when(BankConnectionType.parseBankConnectionType(conn.type)) {
                BankConnectionType.EBICS -> {
                    val doc = XMLUtil.parseStringIntoDom(it.message.bytes.toString(Charsets.UTF_8))
                    /**
                     * Calling the CaMt handler.  After its return, all the Neuxs-meaningful
                     * payment data got stored into the database and is ready to being further
                     * processed by any facade OR simply be communicated to the CLI via JSON.
                     */
                    processCamtMessage(
                        bankAccountId,
                        doc,
                        it.code ?: throw internalServerError(
                            "Bank message with ID ${it.id.value} in DB table" +
                                    " NexusBankMessagesTable has no code, but one is expected."
                        )
                    )
                }
                BankConnectionType.X_LIBEUFIN_BANK -> {
                    val jMessage = try { jacksonObjectMapper().readTree(it.message.bytes) }
                    catch (e: Exception) {
                        logger.error("Bank message ${it.id}/${it.messageId} could not" +
                                " be parsed into JSON by the x-libeufin-bank ingestion.")
                        throw internalServerError("Could not ingest x-libeufin-bank messages.")
                    }
                    processXLibeufinBankMessage(
                        bankAccountId,
                        jMessage
                    )
                }
            }
            /**
             * Checking for errors.  Note: errors do NOT stop this loop as
             * they mean that ONE message has errors.  Erroneous messages gets
             * (1) flagged, (2) skipped when this function will run again, and (3)
             * NEVER deleted from the database.
             */
            if (processingResult.newTransactions == -1) {
                it.errors = true
                lastId = it.id.value
                return@forEach
            }
            totalNew += processingResult.newTransactions
            downloadedTransactions += processingResult.downloadedTransactions
            /**
             * Disk-space conservative check: only store if "yes" was
             * explicitly set into the environment variable.  Any other
             * value or non given falls back to deletion.
             */
            if (keepBankMessages == null || keepBankMessages != "yes") {
                it.delete()
                return@forEach
            }
            /**
             * Updating the highest seen message ID with the serial ID of
             * the row that's being currently iterated over.  Note: this
             * number is ever-growing REGARDLESS of the row being kept into
             * the database.
             */
            lastId = it.id.value
        }
        // Causing the lastId to be stored into the database:
        acct.highestSeenBankMessageSerialId = lastId
    }
    return IngestedTransactionsCount(
        newTransactions = totalNew,
        downloadedTransactions = downloadedTransactions
    )
}

/**
 * Retrieve payment initiation from database, raising exception if not found.
 */
fun getPaymentInitiation(uuid: Long): PaymentInitiationEntity {
    return transaction {
        PaymentInitiationEntity.findById(uuid)
    } ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Payment '$uuid' not found"
    )
}

data class LastMessagesTimes(
    val lastStatement: ZonedDateTime?,
    val lastReport: ZonedDateTime?
)
/**
 * Get the last timestamps where a report and
 * a statement were received for the bank account
 * given as argument.
 */
fun getLastMessagesTimes(bankAccountId: String): LastMessagesTimes {
    val acct = getBankAccount(bankAccountId)
    return getLastMessagesTimes(acct)
}

fun getLastMessagesTimes(acct: NexusBankAccountEntity): LastMessagesTimes {
    return LastMessagesTimes(
        lastReport = acct.lastReportCreationTimestamp?.let {
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC)
        },
        lastStatement = acct.lastStatementCreationTimestamp?.let {
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC)
        }
    )
}
fun getBankAccount(label: String): NexusBankAccountEntity {
    val maybeBankAccount = transaction {
        NexusBankAccountEntity.findByName(label)
    }
    return maybeBankAccount ?:
    throw NexusError(
        HttpStatusCode.NotFound,
        "Account $label not found"
    )
}
fun addPaymentInitiation(paymentData: Pain001Data, debtorAccount: String): PaymentInitiationEntity {
    val bankAccount = getBankAccount(debtorAccount)
    return addPaymentInitiation(paymentData, bankAccount)
}

/**
 * Insert one row in the database, and leaves it marked as non-submitted.
 * @param debtorAccount the mnemonic id assigned by the bank to one bank
 * account of the subscriber that is creating the pain entity.  In this case,
 * it will be the account whose money will pay the wire transfer being defined
 * by this pain document.
 */
fun addPaymentInitiation(paymentData: Pain001Data, debtorAccount: NexusBankAccountEntity): PaymentInitiationEntity {
    return transaction {
        val now = Instant.now().toEpochMilli()
        val nowHex = now.toString(16)
        val painCounter = debtorAccount.pain001Counter++
        val painHex = painCounter.toString(16)
        val acctHex = debtorAccount.id.value.toString(16)
        PaymentInitiationEntity.new {
            currency = paymentData.currency
            bankAccount = debtorAccount
            subject = paymentData.subject
            sum = paymentData.sum
            creditorName = paymentData.creditorName
            creditorBic = paymentData.creditorBic
            creditorIban = paymentData.creditorIban
            preparationDate = now
            endToEndId = "leuf-e-$nowHex-$painHex-$acctHex"
            messageId = "leuf-mp1-$nowHex-$painHex-$acctHex"
            paymentInformationId = "leuf-p-$nowHex-$painHex-$acctHex"
            instructionId = "leuf-i-$nowHex-$painHex-$acctHex"
        }
    }
}

suspend fun fetchBankAccountTransactions(
    client: HttpClient,
    fetchSpec: FetchSpecJson,
    accountId: String
): IngestedTransactionsCount {
    val connectionDetails = transaction {
        val acct = NexusBankAccountEntity.findByName(accountId)
        if (acct == null) {
            throw NexusError(
                HttpStatusCode.NotFound,
                "Account '$accountId' not found"
            )
        }
        val conn = acct.defaultBankConnection
        if (conn == null) {
            throw NexusError(
                HttpStatusCode.BadRequest,
                "No default bank connection (explicit connection not yet supported)"
            )
        }
        return@transaction object {
            /**
             * The connection type _as enum_ should eventually come
             * directly from the database, instead of being parsed by
             * parseBankConnectionType().
             */
            val connectionType = BankConnectionType.parseBankConnectionType(conn.type)
            val connectionName = conn.connectionId
        }
    }
    /**
     * Collects transactions from the bank and stores the (camt)
     * document into the database.  This function tries to download
     * both reports AND statements even if the first one fails.
     */
    val errors: List<Exception>? = getConnectionPlugin(connectionDetails.connectionType).fetchTransactions(
        fetchSpec,
        client,
        connectionDetails.connectionName,
        accountId
    )
    /**
     * Here it MIGHT just return in case of errors, but sometimes the
     * fetcher asks for multiple results (e.g. C52 and C53), and what
     * went through SHOULD be ingested.
     */

    /**
     * This block causes new NexusBankAccountTransactions rows to be
     * INSERTed into the database, according to the banking data that
     * was recently downloaded.
     */
    val ingestionResult: IngestedTransactionsCount = ingestBankMessagesIntoAccount(
        connectionDetails.connectionName,
        accountId
    )
    /**
     * The following two functions further processe the banking data
     * that was recently downloaded, according to the particular facade
     * being honored.
     */
    ingestFacadeTransactions(
        bankAccountId = accountId,
        facadeType = NexusFacadeType.TALER,
        incomingFilterCb = ::talerFilter,
        refundCb = ::maybeTalerRefunds
    )
    ingestFacadeTransactions(
        bankAccountId = accountId,
        facadeType = NexusFacadeType.ANASTASIS,
        incomingFilterCb = ::anastasisFilter,
        refundCb = null
    )

    ingestionResult.errors = errors
    return ingestionResult
}

fun importBankAccount(call: ApplicationCall, offeredBankAccountId: String, nexusBankAccountId: String) {
    transaction {
        val conn = requireBankConnection(call, "connid")
        // first get handle of the offered bank account
        val offeredAccount = OfferedBankAccountsTable.select {
            OfferedBankAccountsTable.offeredAccountId eq offeredBankAccountId and
                    (OfferedBankAccountsTable.bankConnection eq conn.id.value)
        }.firstOrNull() ?: throw NexusError(
            HttpStatusCode.NotFound, "Could not find offered bank account '${offeredBankAccountId}'"
        )
        // detect name collisions first.
        NexusBankAccountEntity.findByName(nexusBankAccountId).run {
            // This variable will either host a new, or a found imported bank account.
            val importedAccount = when (this) {
                is NexusBankAccountEntity -> {
                    if (this.iban != offeredAccount[OfferedBankAccountsTable.iban]) {
                        throw NexusError(
                            HttpStatusCode.Conflict,
                            "$nexusBankAccountId exists already and its IBAN is different from $offeredBankAccountId"
                        )
                    }
                    // an imported bank account already exists and
                    // the user tried to import the same IBAN to it.  Do nothing
                    this
                }
                // such named imported account didn't exist.  Make it
                else -> {
                    val newImportedAccount = NexusBankAccountEntity.new {
                        bankAccountName = nexusBankAccountId
                        iban = offeredAccount[OfferedBankAccountsTable.iban]
                        bankCode = offeredAccount[OfferedBankAccountsTable.bankCode]
                        defaultBankConnection = conn
                        highestSeenBankMessageSerialId = 0
                        accountHolder = offeredAccount[OfferedBankAccountsTable.accountHolder]
                    }
                    logger.info("Account ${newImportedAccount.id} gets imported")
                    newImportedAccount
                }
            }
            // Associate the bank account as named by the bank (the 'offered')
            // with the imported/local one (the 'imported').  Rewrites are acceptable.
            OfferedBankAccountsTable.update(
                {
                    OfferedBankAccountsTable.offeredAccountId eq offeredBankAccountId and
                            (OfferedBankAccountsTable.bankConnection eq conn.id.value)
                }
            ) {
                it[imported] = importedAccount.id
            }
        }
    }
}


/**
 * Check if the transaction is already found in the database.
 * This function works as long as the caller provides the appropriate
 * 'uid' parameter.  For CaMt messages this value is carried along
 * the AcctSvcrRef node, whereas for x-libeufin-bank connections
 * that's the 'uid' field of the XLibeufinBankTransaction type.
 *
 * Returns the transaction that's already in the database, in case
 * the 'uid' is from a duplicate.
 */
fun findDuplicate(
    bankAccountId: String,
    uid: String
): NexusBankTransactionEntity? {
    return transaction {
        val account = NexusBankAccountEntity.findByName((bankAccountId)) ?:
        return@transaction null
        NexusBankTransactionEntity.find {
            (NexusBankTransactionsTable.accountTransactionId eq uid) and
                    (NexusBankTransactionsTable.bankAccount eq account.id)
        }.firstOrNull()
    }
}
