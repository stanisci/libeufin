package tech.libeufin.nexus.xlibeufinbank

import AgentIdentification
import Batch
import BatchTransaction
import CamtBankAccountEntry
import CashAccount
import CreditDebitIndicator
import CurrencyAmount
import PartyIdentification
import TransactionDetails
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.util.*
import io.ktor.util.*
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.*
import tech.libeufin.nexus.bankaccount.*
import tech.libeufin.nexus.server.*
import tech.libeufin.util.*
import java.net.MalformedURLException
import java.net.URL
import java.time.LocalDate

// Gets Sandbox URL and credentials, taking the connection name as input.
private fun getXLibeufinBankCredentials(conn: NexusBankConnectionEntity): XLibeufinBankTransport {
    val maybeCredentials = transaction {
        XLibeufinBankUserEntity.find {
            XLibeufinBankUsersTable.nexusBankConnection eq conn.id
        }.firstOrNull()
    }
    if (maybeCredentials == null) throw internalServerError(
        "Existing connection ${conn.connectionId} has no transport details"
    )
    return XLibeufinBankTransport(
        username = maybeCredentials.username,
        password = maybeCredentials.password,
        baseUrl = maybeCredentials.baseUrl
    )
}
private fun getXLibeufinBankCredentials(connId: String): XLibeufinBankTransport {
    val conn = getBankConnection(connId)
    return getXLibeufinBankCredentials(conn)
}
class XlibeufinBankConnectionProtocol : BankConnectionProtocol {
    override fun getBankUrl(connId: String): String {
        return getXLibeufinBankCredentials(connId).baseUrl
    }
    /**
     * Together with checking the credentials, this method downloads
     * additional details from the bank, and stores them in the table
     * that holds offered bank accounts.  That saves one call to the
     * "import" method by recycling the information obtained here.
     */
    override suspend fun connect(client: HttpClient, connId: String) {
        val conn = getBankConnection(connId)
        val connDetails = getXLibeufinBankCredentials(conn)
        // Defining the URL to request the bank account balance.
        val url = connDetails.baseUrl + "/accounts/${connDetails.username}"
        // Error handling expected by the caller.
        val details = client.get(url) {
            expectSuccess = true
            basicAuth(connDetails.username, connDetails.password)
        }
        val txtDetails = details.bodyAsText()
        val jDetails = jacksonObjectMapper().readTree(txtDetails)
        val paytoUri: String = try { jDetails.get("paytoUri").asText() }
        catch (e: Exception) {
            logger.error("Did not find 'paytoUri' along the connection" +
                    " operation from x-libeufin-bank connection $connId." +
                    "  Bank says: $txtDetails"
            )
            throw badGateway("Bank missed basic account information ('paytoUri' field)")
        }
        val paytoObj = parsePayto(payto = paytoUri)
        val maybeOfferedAccount = transaction {
            OfferedBankAccountEntity.find {
                // Sandbox reliably names the bank account with the owner's username
                OfferedBankAccountsTable.offeredAccountId eq connDetails.username
            }.firstOrNull()
        }
        // Bank account already imported.
        if (maybeOfferedAccount != null)
            return
        // Import it.
        transaction {
            OfferedBankAccountEntity.new {
                offeredAccountId = connDetails.username
                bankConnection = conn
                accountHolder = paytoObj.receiverName ?: "Not given by the bank"
                bankCode = paytoObj.bic ?: "SANDBOXX"
                iban = paytoObj.iban
            }
        }
    }

    /**
     * This operation is carried along connect(), because as a side
     * effect of checking the credentials it ALSO gets all the offered
     * bank account information that this function WOULD have obtained.
     *
     * Therefore, this method throws error when called, in order to raise
     * the clients' awareness not to rely on it.
     */
    override suspend fun fetchAccounts(client: HttpClient, connId: String) {
        throw NotImplementedError("Please skip this method when using x-libeufin-bank.")
    }

    override fun createConnectionFromBackup(
        connId: String,
        user: NexusUserEntity,
        passphrase: String?,
        backup: JsonNode
    ) {
        TODO("Not yet implemented")
    }

    override fun createConnection(
        connId: String,
        user: NexusUserEntity,
        data: JsonNode
    ) {
        val bankConn = transaction {
            NexusBankConnectionEntity.new {
                this.connectionId = connId
                owner = user
                type = "x-libeufin-bank"
            }
        }
        val newTransportData = jacksonObjectMapper().treeToValue(
            data, XLibeufinBankTransport::class.java
        ) ?: throw badRequest("x-libeufin-bank details not found in the request")
        // Validate the base URL
        try { URL(newTransportData.baseUrl).toURI() }
        catch (e: MalformedURLException) {
            throw badRequest("Base URL (${newTransportData.baseUrl}) is invalid.")
        }
        transaction {
            XLibeufinBankUserEntity.new {
                username = newTransportData.username
                password = newTransportData.password
                // Only addressing mild cases where ONE slash ends the base URL.
                baseUrl = newTransportData.baseUrl.dropLastWhile { it == '/' }
                nexusBankConnection = bankConn
            }
        }
    }

    override fun getConnectionDetails(conn: NexusBankConnectionEntity): JsonNode {
        val credentials = getXLibeufinBankCredentials(conn)
        val mapper = ObjectMapper()
        val details = mapper.createObjectNode()
        details.put("baseUrl", credentials.baseUrl)
        details.put("username", credentials.username)
        val node = mapper.createObjectNode()
        node.put("type", conn.type)
        node.put("owner", conn.owner.username)
        node.set<JsonNode>("details", details)
        return node
    }

    override fun exportBackup(bankConnectionId: String, passphrase: String): JsonNode {
        TODO("Not yet implemented")
    }

    override fun exportAnalogDetails(conn: NexusBankConnectionEntity): ByteArray {
        throw NotImplementedError("x-libeufin-bank does not need analog details")
    }

    override suspend fun submitPaymentInitiation(
        httpClient: HttpClient,
        paymentInitiationId: Long
    ) {
        /**
         * Main steps.
         *
         * 1) Get prep from the DB.
         * 2) Collect credentials.
         * 3) Create the format to POST.
         * 4) POST the transaction.
         * 5) Mark the prep as submitted.
         * */
        // 1
        val preparedPayment = getPaymentInitiation(paymentInitiationId)
        // 2
        val conn = transaction { preparedPayment.bankAccount.defaultBankConnection } ?: throw
                internalServerError("Default connection not found for bank account: ${preparedPayment.bankAccount.bankAccountName}")
        val credentials: XLibeufinBankTransport = getXLibeufinBankCredentials(conn)
        // 3
        val paytoUri = buildIbanPaytoUri(
            iban = preparedPayment.creditorIban,
            bic = preparedPayment.creditorBic ?: "SANDBOXX",
            receiverName = preparedPayment.creditorName,
            message = preparedPayment.subject
        )
        val req = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
            XLibeufinBankPaytoReq(
                paytoUri = paytoUri,
                amount = "${preparedPayment.currency}:${preparedPayment.sum}",
                pmtInfId = preparedPayment.paymentInformationId
            )
        )
        // 4
        val url = credentials.baseUrl + "/accounts/${credentials.username}/transactions"
        logger.debug("POSTing transactions to x-libeufin-bank at: $url")
        val r = httpClient.post(url) {
            expectSuccess = false
            contentType(ContentType.Application.Json)
            basicAuth(credentials.username, credentials.password)
            setBody(req)
        }
        if (r.status.value.toString().startsWith("5")) {
            throw NexusError(
                HttpStatusCode.BadGateway,
                "The bank failed: ${r.bodyAsText()}"
            )
        }
        if (!r.status.value.toString().startsWith("2")) {
            throw NexusError(
                /**
                 * Echoing whichever status code the bank gave.  That
                 * however masks client errors where - for example - a
                 * request detail causes 404 where Nexus has no power.
                 */
                HttpStatusCode(r.status.value, r.status.description),
                r.bodyAsText()
            )
        }
        // 5
        transaction { preparedPayment.submitted = true }
    }

    override suspend fun fetchTransactions(
        fetchSpec: FetchSpecJson, // FIXME: handle time range.
        client: HttpClient,
        bankConnectionId: String,
        accountId: String
    ): List<Exception>? {
        val conn = getBankConnection(bankConnectionId)
        if (fetchSpec.level == FetchLevel.REPORT || fetchSpec.level == FetchLevel.ALL)
            throw badRequest("level '${fetchSpec.level}' on x-libeufin-bank" +
                    "connection (${conn.connectionId}) is not supported:" +
                    " bank has only 'booked' state."
            )
        // Get credentials
        val credentials = getXLibeufinBankCredentials(conn)
        /**
         * Now builds the URL to ask the transactions, according to the
         * FetchSpec gotten in the args.  Level 'statement' and time range
         * 'previous-days' are NOT implemented.
         */
        val baseUrl = URL(credentials.baseUrl)
        val fetchUrl = url {
            protocol = URLProtocol(name = baseUrl.protocol, defaultPort = -1)
            port = baseUrl.port
            appendPathSegments(
                baseUrl.path.dropLastWhile { it == '/' },
                "accounts/${credentials.username}/transactions")
            when (fetchSpec) {
                is FetchSpecTimeRangeJson -> {
                    // the parse() method defaults to the YYYY-MM-DD format.
                    val start: LocalDate = LocalDate.parse(fetchSpec.start)
                    val end: LocalDate = LocalDate.parse(fetchSpec.end)
                    this.parameters["from_ms"] = start.toString()
                    this.parameters["to_ms"] = end.toString()
                }
                // Gets the last 5 transactions
                is FetchSpecLatestJson -> {
                    // Do nothing, the bare endpoint gets the last 5 txs by default.
                }
                /* Defines the from_ms URI param. according to the last transaction
                 * timestamp that was seen in this connection */
                is FetchSpecSinceLastJson -> {
                    val localBankAccount = getBankAccount(accountId)
                    // Sandbox doesn't have report vs. statement, defaulting to statement time
                    // and so does the ingestion routine when storing the last message time.
                    // The sought time must be incremented by one because the filter is _inclusive_.
                    this.parameters["from_ms"] = "${localBankAccount.lastStatementCreationTimestamp?.plus(1) ?: 0}"
                }
                // This wants ALL the transactions, hence it sets the from_ms to zero.
                is FetchSpecAllJson -> {
                    this.parameters["from_ms"] = "0"
                }
                else -> throw NexusError(
                    HttpStatusCode.NotImplemented,
                    "FetchSpec ${fetchSpec::class} not supported"
                )
            }
        }
        logger.debug("Requesting x-libeufin-bank transactions to: $fetchUrl")
        val resp: HttpResponse = try {
            client.get(fetchUrl) {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                basicAuth(credentials.username, credentials.password)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            logger.error(e.message)
            return listOf(e)
        }
        val respBlob = resp.bodyAsChannel().toByteArray()
        transaction {
            NexusBankMessageEntity.new {
                bankConnection = conn
                message = ExposedBlob(respBlob)
                fetchLevel = fetchSpec.level
            }
        }
        return null
    }
}

fun ingestXLibeufinBankMessage(
    bankAccountId: String,
    data: String // JSON
): IngestedTransactionsCount {
    val jMessage = try { jacksonObjectMapper().readTree(data) }
    catch (e: Exception) {
        logger.error("Bank message $data could not" +
                " be parsed into JSON by the x-libeufin-bank ingestion.")
        throw internalServerError("Could not ingest x-libeufin-bank message.")
    }
    return ingestXLibeufinBankMessage(bankAccountId, jMessage)
}
/**
 * Parses one x-libeufin-bank message and INSERTs Nexus local
 * transaction records into the database.  After this function
 * returns, the transactions are ready to both being communicated
 * to the CLI via the native JSON interface OR being further processed
 * by ANY facade.
 *
 * This function:
 * - updates the local timestamps related to the latest report.
 * - inserts a new NexusBankTransactionEntity.  To achieve that, it extracts the:
 * -- amount
 * -- credit/debit indicator
 * -- currency
 *
 * Note: in contrast to what the CaMt handler does, here there's NO
 * status, since Sandbox has only one (unnamed) transaction state and
 * all transactions are asked as reports.
 */
fun ingestXLibeufinBankMessage(
    bankAccountId: String,
    data: JsonNode
): IngestedTransactionsCount {
    data class XLibeufinBankTransactions(
        val transactions: List<XLibeufinBankTransaction>
    )
    val txs = try {
        jacksonObjectMapper().treeToValue(
            data,
            XLibeufinBankTransactions::class.java
        )
    } catch (e: Exception) {
        throw NexusError(
            HttpStatusCode.BadGateway,
            "The bank sent invalid x-libeufin-bank transactions."
        )
    }
    val bankAccount = getBankAccount(bankAccountId)
    var newTxs = 0 // Counts how many transactions are new.
    txs.transactions.forEach {
        val maybeTimestamp = try {
            it.date.toLong()
        } catch (e: Exception) {
            throw NexusError(
                HttpStatusCode.BadGateway,
                "The bank gave an invalid timestamp " +
                        "for x-libeufin-bank message: ${it.uid}"
            )
        }
        // Searching for duplicates.
        if (findDuplicate(bankAccountId, "${PaymentUidQualifiers.BANK_GIVEN}:${it.uid}") != null) {
            logger.debug("x-libeufin-bank ingestion: transaction ${it.uid} is a duplicate, skipping.")
            return@forEach
        }
        val direction = if (it.debtorIban == bankAccount.iban)
            XLibeufinBankDirection.DEBIT else XLibeufinBankDirection.CREDIT
        // New tx, storing it.
        transaction {
            val localTx = NexusBankTransactionEntity.new {
                this.bankAccount = bankAccount
                this.amount = it.amount
                this.currency = it.currency
                /**
                 * Sandbox has only booked state for its transactions: as soon as
                 * one payment makes it to the database, that is the final (booked)
                 * state.
                 */
                this.status = EntryStatus.BOOK
                this.accountTransactionId = "${PaymentUidQualifiers.BANK_GIVEN}:${it.uid}"
                this.transactionJson = jacksonObjectMapper(
                ).writeValueAsString(it.exportAsCamtModel())
                this.creditDebitIndicator = direction.exportAsCamtDirection()
                newTxs++
                logger.debug("x-libeufin-bank transaction with subject '${it.subject}' ingested.")
            }
            /**
             * The following block tries to reconcile a previous prepared
             * (outgoing) payment with the one being iterated over.
             */
            if (direction == XLibeufinBankDirection.DEBIT) {
                val maybePrepared = it.pmtInfId?.let { it1 -> getPaymentInitiation(pmtInfId = it1) }
                if (maybePrepared != null) maybePrepared.confirmationTransaction = localTx
            }
            // x-libeufin-bank transactions are ALWAYS modeled as reports
            // in Nexus, because such bank protocol supplier doesn't have
            // the report vs. statement distinction.  Therefore, we only
            // consider the last report timestamp.
            if ((bankAccount.lastStatementCreationTimestamp ?: 0L) < maybeTimestamp)
                bankAccount.lastStatementCreationTimestamp = maybeTimestamp
        }
    }
    return IngestedTransactionsCount(
        newTransactions = newTxs,
        downloadedTransactions = txs.transactions.size
    )
}

fun XLibeufinBankTransaction.exportCamtDirectionIndicator(): CreditDebitIndicator =
    if (this.direction == XLibeufinBankDirection.CREDIT)
        CreditDebitIndicator.CRDT else CreditDebitIndicator.DBIT

/**
 * This function transforms an x-libeufin-bank transaction
 * into the JSON representation of CaMt used by Nexus along
 * its processing.  Notably, this helps to stick to one unified
 * type when facades process transactions.
 */
fun XLibeufinBankTransaction.exportAsCamtModel(): CamtBankAccountEntry =
    CamtBankAccountEntry(
        /**
         * Amount obtained by summing all the transactions accounted
         * in this report/statement.  Here this field equals the amount of the
         * _unique_ transaction accounted.
         */
        amount = CurrencyAmount(currency = this.currency, value = this.amount),
        accountServicerRef = this.uid,
        bankTransactionCode = "Not given",
        bookingDate = this.date,
        counterValueAmount = null,
        creditDebitIndicator = this.exportCamtDirectionIndicator(),
        currencyExchange = null,
        entryRef = null,
        instructedAmount = null,
        valueDate = null,
        status = EntryStatus.BOOK, // x-libeufin-bank always/only BOOK.
        /**
         * This field accounts for the _unique_ transaction that this
         * object represents.
         */
        batches = listOf(
            Batch(
                messageId = null,
                paymentInformationId = this.uid,
                batchTransactions = listOf(
                    BatchTransaction(
                        amount = CurrencyAmount(
                            currency = this.currency,
                            value = this.amount
                        ),
                        creditDebitIndicator = this.exportCamtDirectionIndicator(),
                        details = TransactionDetails(
                            debtor = PartyIdentification(
                                name = this.debtorName,
                                countryOfResidence = null,
                                organizationId = null,
                                otherId = null,
                                postalAddress = null,
                                privateId = null
                                ),
                            debtorAccount = CashAccount(
                                name = null,
                                currency = this.currency,
                                iban = this.debtorIban,
                                otherId = null
                            ),
                            debtorAgent = AgentIdentification(
                                name = null,
                                bic = this.debtorBic,
                                clearingSystemCode = null,
                                clearingSystemMemberId = null,
                                lei = null,
                                otherId = null,
                                postalAddress = null,
                                proprietaryClearingSystemCode = null
                            ),
                            counterValueAmount = null,
                            currencyExchange = null,
                            interBankSettlementAmount = null,
                            proprietaryPurpose = null,
                            purpose = null,
                            returnInfo = null,
                            ultimateCreditor = null,
                            ultimateDebtor = null,
                            unstructuredRemittanceInformation = this.subject,
                            instructedAmount = null,
                            creditor = PartyIdentification(
                                name = this.creditorName,
                                countryOfResidence = null,
                                organizationId = null,
                                otherId = null,
                                postalAddress = null,
                                privateId = null
                            ),
                            creditorAccount = CashAccount(
                                name = null,
                                currency = this.currency,
                                iban = this.creditorIban,
                                otherId = null
                            ),
                            creditorAgent = AgentIdentification(
                                name = null,
                                bic = this.creditorBic,
                                clearingSystemCode = null,
                                clearingSystemMemberId = null,
                                lei = null,
                                otherId = null,
                                postalAddress = null,
                                proprietaryClearingSystemCode = null
                            )
                        )
                    )
                )
            )
        )
    )