/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */


package tech.libeufin.sandbox

import io.ktor.server.application.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import org.apache.xml.security.binding.xmldsig.RSAKeyValueType
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import org.w3c.dom.Document
import tech.libeufin.util.*
import tech.libeufin.util.XMLUtil.Companion.signEbicsResponse
import tech.libeufin.util.ebics_h004.*
import tech.libeufin.util.ebics_hev.HEVResponse
import tech.libeufin.util.ebics_hev.SystemReturnCodeType
import tech.libeufin.util.ebics_s001.SignatureTypes
import tech.libeufin.util.ebics_s001.UserSignatureData
import java.math.BigDecimal
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.sql.Connection
import java.util.*
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream

val EbicsHostIdAttribute = AttributeKey<String>("RequestedEbicsHostID")

data class PainParseResult(
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val debtorIban: String,
    val debtorName: String,
    val debtorBic: String?,
    val subject: String,
    val amount: String,
    val currency: String,
    val pmtInfId: String,
    val endToEndId: String,
    val msgId: String
)

open class EbicsRequestError(
    val errorText: String,
    val errorCode: String
) : Exception("$errorText (EBICS error code: $errorCode)")

class EbicsNoDownloadDataAvailable(reason: String? = null) : EbicsRequestError(
    "[EBICS_NO_DOWNLOAD_DATA_AVAILABLE]" + if (reason != null) " $reason" else "",
    "090005"
)

class EbicsInvalidRequestError : EbicsRequestError(
    "[EBICS_INVALID_REQUEST] Invalid request",
    "060102"
)
class EbicsAccountAuthorisationFailed(reason: String) : EbicsRequestError(
    "[EBICS_ACCOUNT_AUTHORISATION_FAILED] $reason",
    "091302"
)

/**
 * This error is thrown whenever the Subscriber's state is not suitable
 * for the requested action.  For example, the subscriber sends a EbicsRequest
 * message without having first uploaded their keys (#5973).
 */
class EbicsSubscriberStateError : EbicsRequestError(
    "[EBICS_INVALID_USER_OR_USER_STATE] Subscriber unknown or subscriber state inadmissible",
    "091002"
)
// hint should mention at least the userID
class EbicsUserUnknown(hint: String) : EbicsRequestError(
    "[EBICS_USER_UNKNOWN] $hint",
    "091003"
)

class EbicsOrderParamsIgnored(hint: String) : EbicsRequestError(
    "[EBICS_ORDER_PARAMS_IGNORED] $hint",
    "031001"
)


open class EbicsKeyManagementError(private val errorText: String, private val errorCode: String) :
    Exception("EBICS key management error: $errorText ($errorCode)")

private class EbicsInvalidXmlError : EbicsKeyManagementError(
    "[EBICS_INVALID_XML]",
    "091010"
)

private class EbicsUnsupportedOrderType : EbicsRequestError(
    "[EBICS_UNSUPPORTED_ORDER_TYPE] Order type not supported",
    "091005"
)

/**
 * Used here also for "Internal server error".  For example, when the
 * sandbox itself generates a invalid XML response.
 */
class EbicsProcessingError(detail: String?) : EbicsRequestError(
    // a missing detail is already the bank's fault.
    "[EBICS_PROCESSING_ERROR] " + (detail ?: "bank internal error"),
    "091116"
)

class EbicsAmountCheckError(detail: String): EbicsRequestError(
    "[EBICS_AMOUNT_CHECK_FAILED] $detail",
    "091303"
)

suspend fun respondEbicsTransfer(
    call: ApplicationCall,
    errorText: String,
    errorCode: String
) {
    /**
     * Because this handler runs for any error, it could
     * handle the case where the Ebics host ID is unknown due
     * to an invalid request.  Recall: Sandbox is multi-host, and
     * which Ebics host was requested belongs to the request document.
     *
     * Therefore, because any Ebics response
     * should speak for one Ebics host, we can't respond any Ebics
     * type when the Ebics host ID remains unknown due to invalid
     * request.  Instead, we'll respond plain text:
     */
    if (!call.attributes.contains(EbicsHostIdAttribute)) {
        call.respondText("Invalid document.", status = HttpStatusCode.BadRequest)
        return
    }
    val resp = EbicsResponse.createForUploadWithError(
        errorText,
        errorCode,
        // For now, phase gets hard-coded as TRANSFER,
        // because errors during initialization should have
        // already been caught by the chunking logic.
        EbicsTypes.TransactionPhaseType.TRANSFER
    )
    val hostAuthPriv = transaction {
        val host = EbicsHostEntity.find {
            EbicsHostsTable.hostID.upperCase() eq call.attributes[EbicsHostIdAttribute]
                .uppercase()
        }.firstOrNull() ?: throw SandboxError(
            HttpStatusCode.InternalServerError,
            "Requested Ebics host ID (${call.attributes[EbicsHostIdAttribute]}) not found."
        )
        CryptoUtil.loadRsaPrivateKey(host.authenticationPrivateKey.bytes)
    }
    call.respondText(
        signEbicsResponse(resp, hostAuthPriv),
        ContentType.Application.Xml,
        HttpStatusCode.OK
    )
}

private suspend fun ApplicationCall.respondEbicsKeyManagement(
    errorText: String,
    errorCode: String,
    bankReturnCode: String,
    dataTransfer: CryptoUtil.EncryptionResult? = null,
    orderId: String? = null
) {
    val responseXml = EbicsKeyManagementResponse().apply {
        version = "H004"
        header = EbicsKeyManagementResponse.Header().apply {
            authenticate = true
            mutable = EbicsKeyManagementResponse.MutableHeaderType().apply {
                reportText = errorText
                returnCode = errorCode
                if (orderId != null) {
                    this.orderID = orderId
                }
            }
            _static = EbicsKeyManagementResponse.EmptyStaticHeader()
        }
        body = EbicsKeyManagementResponse.Body().apply {
            this.returnCode = EbicsKeyManagementResponse.ReturnCode().apply {
                this.authenticate = true
                this.value = bankReturnCode
            }
            if (dataTransfer != null) {
                this.dataTransfer = EbicsKeyManagementResponse.DataTransfer().apply {
                    this.dataEncryptionInfo = EbicsTypes.DataEncryptionInfo().apply {
                        this.authenticate = true
                        this.transactionKey = dataTransfer.encryptedTransactionKey
                        this.encryptionPubKeyDigest = EbicsTypes.PubKeyDigest().apply {
                            this.algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                            this.version = "E002"
                            this.value = dataTransfer.pubKeyDigest
                        }
                    }
                    this.orderData = EbicsKeyManagementResponse.OrderData().apply {
                        this.value = Base64.getEncoder().encodeToString(dataTransfer.encryptedData)
                    }
                }
            }
        }
    }
    val text = XMLUtil.convertJaxbToString(responseXml)
    // logger.info("responding with:\n${text}")
    if (!XMLUtil.validateFromString(text)) throw SandboxError(
        HttpStatusCode.InternalServerError,
        "Outgoint EBICS key management response is invalid"
    )
    respondText(text, ContentType.Application.Xml, HttpStatusCode.OK)
}

fun <T> expectNonNull(x: T?): T {
    if (x == null) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "expected non-null value")
    }
    return x;
}

private fun getRelatedParty(branch: XmlElementBuilder, payment: XLibeufinBankTransaction) {
    val otherParty = object {
        var ibanPath = "CdtrAcct/Id/IBAN"
        var namePath = "Cdtr/Nm"
        var iban = payment.creditorIban
        var name = payment.creditorName
        var bicPath = "CdtrAgt/FinInstnId/BIC"
        var bic = payment.creditorBic
    }
    if (payment.direction == XLibeufinBankDirection.CREDIT) {
        otherParty.iban = payment.debtorIban
        otherParty.ibanPath = "DbtrAcct/Id/IBAN"
        otherParty.namePath = "Dbtr/Nm"
        otherParty.name = payment.debtorName
        otherParty.bic = payment.debtorBic
        otherParty.bicPath = "DbtrAgt/FinInstnId/BIC"
    }
    branch.element("RltdPties") {
        element(otherParty.namePath) {
            text(otherParty.name)
        }
        element(otherParty.ibanPath) {
            text(otherParty.iban)
        }
    }
    val otherPartyBic = otherParty.bic
    if (otherPartyBic != null) {
        branch.element("RltdAgts") {
            element(otherParty.bicPath) {
                text(otherPartyBic)
            }
        }
    }
}

// This should fix #6269.
private fun getCreditDebitInd(balance: BigDecimal): String {
    if (balance < BigDecimal.ZERO) return "DBIT"
    return "CRDT"
}

fun buildCamtString(
    type: Int,
    subscriberIban: String,
    history: MutableList<XLibeufinBankTransaction>,
    currency: String
): SandboxCamt {
    /**
     * ID types required:
     *
     * - Message Id
     * - Statement / Report Id
     * - Electronic sequence number
     * - Legal sequence number
     * - Entry Id by the Servicer
     * - Payment information Id
     * - Proprietary code of the bank transaction
     * - Id of the servicer (Issuer and Code)
     */
    val camtCreationTime = getUTCnow() // FIXME: should this be the payment time?
    val dashedDate = camtCreationTime.toDashedDate()
    val zonedDateTime = camtCreationTime.toZonedString()
    val creationTimeMillis = camtCreationTime.toInstant().toEpochMilli()
    val messageId = "sandbox-${creationTimeMillis / 1000}-${getRandomString(10)}"

    val camtMessage = constructXml(indent = true) {
        root("Document") {
            attribute("xmlns", "urn:iso:std:iso:20022:tech:xsd:camt.0${type}.001.02")
            attribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            attribute(
                "xsi:schemaLocation",
                "urn:iso:std:iso:20022:tech:xsd:camt.0${type}.001.02 camt.0${type}.001.02.xsd"
            )
            element(if (type == 53) "BkToCstmrStmt" else "BkToCstmrAcctRpt") {
                element("GrpHdr") {
                    element("MsgId") {
                        text(messageId)
                    }
                    element("CreDtTm") {
                        text(zonedDateTime)
                    }
                }
                element(if (type == 52) "Rpt" else "Stmt") {
                    element("Id") {
                        text("0")
                    }
                    element("ElctrncSeqNb") {
                        text("0")
                    }
                    element("LglSeqNb") {
                        text("0")
                    }
                    element("CreDtTm") {
                        text(zonedDateTime)
                    }
                    element("Acct") {
                        // mandatory account identifier
                        element("Id/IBAN") {
                            text(subscriberIban)
                        }
                        element("Ccy") {
                            text(currency)
                        }
                        element("Ownr/Nm") {
                            text("Debitor/Owner Name")
                        }
                        element("Svcr/FinInstnId") {
                            element("Nm") {
                                text("Libeufin Bank")
                            }
                            element("Othr") {
                                element("Id") {
                                    text("0")
                                }
                                element("Issr") {
                                    text("XY")
                                }
                            }
                        }
                    }
                    history.forEach {
                        this.element("Ntry") {
                            element("Amt") {
                                attribute("Ccy", it.currency)
                                text(it.amount)
                            }
                            element("CdtDbtInd") {
                                text(
                                    if (subscriberIban.equals(it.creditorIban))
                                        "CRDT" else "DBIT"
                                )
                            }
                            element("Sts") {
                                /* Status of the entry (see 2.4.2.15.5 from the ISO20022 reference document.)
                                    * From the original text:
                                    * "Status of an entry on the books of the account servicer" */
                                text("BOOK")
                            }
                            element("BookgDt/Dt") {
                                text(dashedDate)
                            } // date of the booking
                            element("ValDt/Dt") {
                                text(dashedDate)
                            } // date of assets' actual (un)availability
                            element("AcctSvcrRef") {
                                text(it.uid)
                            }
                            element("BkTxCd") {
                                /*  "Set of elements used to fully identify the type of underlying
                                 *   transaction resulting in an entry".  */
                                element("Domn") {
                                    element("Cd") {
                                        text("PMNT")
                                    }
                                    element("Fmly") {
                                        element("Cd") {
                                            text("ICDT")
                                        }
                                        element("SubFmlyCd") {
                                            text("ESCT")
                                        }
                                    }
                                }
                                element("Prtry") {
                                    element("Cd") {
                                        text("0")
                                    }
                                    element("Issr") {
                                        text("XY")
                                    }
                                }
                            }
                            element("NtryDtls/TxDtls") {
                                element("Refs") {
                                    element("MsgId") {
                                        text(it.msgId ?: "NOTPROVIDED")
                                    }
                                    element("PmtInfId") {
                                        text(it.pmtInfId ?: "NOTPROVIDED")
                                    }
                                    element("EndToEndId") {
                                        text(it.endToEndId ?: "NOTPROVIDED")
                                    }
                                }
                                element("AmtDtls/TxAmt/Amt") {
                                    attribute("Ccy", currency)
                                    text(it.amount)
                                }
                                element("BkTxCd") {
                                    element("Domn") {
                                        element("Cd") {
                                            text("PMNT")
                                        }
                                        element("Fmly") {
                                            element("Cd") {
                                                text("ICDT")
                                            }
                                            element("SubFmlyCd") {
                                                text("ESCT")
                                            }
                                        }
                                    }
                                    element("Prtry") {
                                        element("Cd") {
                                            text("0")
                                        }
                                        element("Issr") {
                                            text("XY")
                                        }
                                    }
                                }
                                getRelatedParty(this, it)
                                element("RmtInf/Ustrd") {
                                    text(it.subject)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return SandboxCamt(
        camtMessage = camtMessage,
        messageId = messageId,
        creationTime = creationTimeMillis
    )
}

/**
 * Builds CAMT response.
 *
 * @param type 52 or 53.
 */
private fun constructCamtResponse(
    type: Int,
    subscriber: EbicsSubscriberEntity,
    dateRange: Pair<Long, Long>?
): List<String> {

    if (type != 53 && type != 52) throw EbicsUnsupportedOrderType()
    val bankAccount = getBankAccountFromSubscriber(subscriber)
    if (type == 52) {
        if (dateRange != null)
            throw EbicsOrderParamsIgnored("C52 does not support date ranges.")
        val history = mutableListOf<XLibeufinBankTransaction>()
        transaction {
            BankAccountFreshTransactionEntity.all().forEach {
                if (it.transactionRef.account.label == bankAccount.label) {
                    history.add(getHistoryElementFromTransactionRow(it))
                }
            }
        }
        if (history.size == 0) throw EbicsNoDownloadDataAvailable()
        val camtData = buildCamtString(
            type,
            bankAccount.iban,
            history,
            bankAccount.demoBank.config.currency
        )
        val paymentsList: String = if (logger.isDebugEnabled) {
            var ret = " It includes the payments:"
            for (p in history) ret += "\n- ${p.subject}"
            ret
        } else ""
        logger.debug("camt.052 document '${camtData.messageId}' generated.$paymentsList")
        return listOf(camtData.camtMessage)
    } // end of C52 case.
    val ret = mutableListOf<String>()
    /**
     * Retrieve all the records whose creation date lies into the
     * time range given in the function parameters.
     */
    if (dateRange != null) {
        logger.debug("Serving C53 with date range: $dateRange")
        BankAccountStatementEntity.find {
            BankAccountStatementsTable.creationTime.between(
                dateRange.first,
                dateRange.second) and(
                    BankAccountStatementsTable.bankAccount eq bankAccount.id)
        }.forEach {
            logger.debug("Including Camt.053: ${it.statementId}")
            ret.add(it.xmlMessage)
        }
    } else {
        logger.debug("Serving C53 without date range.")
        // No time range was given, hence pick the latest statement.
        BankAccountStatementEntity.find {
            BankAccountStatementsTable.bankAccount eq bankAccount.id
        }.lastOrNull().apply {
            if (this != null) {
                logger.debug("Including Camt.053: ${this.statementId}")
                ret.add(this.xmlMessage)
            }
        }
    }
    if (ret.size == 0) throw EbicsNoDownloadDataAvailable()
    return ret
}

/**
 * TSD (test download) message.
 *
 * This is a non-standard EBICS order type use by LibEuFin to
 * test download transactions.
 *
 * In the future, additional parameters (size, chunking, inject fault for retry) might
 * be added to the order parameters.
 */
private fun handleEbicsTSD(): ByteArray {
    return "Hello World\n".repeat(1024).toByteArray()
}

private fun handleEbicsPTK(): ByteArray {
    return "Hello I am a dummy PTK response.".toByteArray()
}

private fun parsePain001(paymentRequest: String): PainParseResult {
    val painDoc = XMLUtil.parseStringIntoDom(paymentRequest)
    return destructXml(painDoc) {
        requireRootElement("Document") {
            requireUniqueChildNamed("CstmrCdtTrfInitn") {
                val msgId = requireUniqueChildNamed("GrpHdr") {
                    requireUniqueChildNamed("MsgId") { focusElement.textContent }
                }
                requireUniqueChildNamed("PmtInf") {
                    val debtorName = requireUniqueChildNamed("Dbtr"){
                        requireUniqueChildNamed("Nm") {
                            focusElement.textContent
                        }
                    }
                    val debtorIban = requireUniqueChildNamed("DbtrAcct"){
                        requireUniqueChildNamed("Id") {
                            requireUniqueChildNamed("IBAN") {
                                focusElement.textContent
                            }
                        }
                    }
                    val debtorBic = requireUniqueChildNamed("DbtrAgt"){
                        requireUniqueChildNamed("FinInstnId") {
                            requireUniqueChildNamed("BIC") {
                                focusElement.textContent
                            }
                        }
                    }
                    val pmtInfId = requireUniqueChildNamed("PmtInfId") { focusElement.textContent }
                    val txDetails = requireUniqueChildNamed("CdtTrfTxInf") {
                        object {
                            val creditorIban = requireUniqueChildNamed("CdtrAcct") {
                                requireUniqueChildNamed("Id") {
                                    requireUniqueChildNamed("IBAN") { focusElement.textContent }
                                }
                            }
                            val creditorName = requireUniqueChildNamed("Cdtr") {
                                requireUniqueChildNamed("Nm") {
                                    focusElement.textContent
                                }
                            }
                            val creditorBic = maybeUniqueChildNamed("CdtrAgt") {
                                requireUniqueChildNamed("FinInstnId") {
                                    requireUniqueChildNamed("BIC") {
                                        focusElement.textContent
                                    }
                                }
                            }
                            val amt = requireUniqueChildNamed("Amt") {
                                requireOnlyChild { focusElement }
                            }
                            val subject = requireUniqueChildNamed("RmtInf") {
                                requireUniqueChildNamed("Ustrd") { focusElement.textContent }
                            }
                            val endToEndId = requireUniqueChildNamed("PmtId") {
                                requireUniqueChildNamed("EndToEndId") { focusElement.textContent }
                            }
                        }
                    }
                    /**
                     * NOTE: this check breaks the compatibility with pain.001,
                     * because that allows up to 5 fractional digits.  For Taler
                     * compatibility however, we enforce the max 2 fractional digits policy.
                     */
                    if (!validatePlainAmount(txDetails.amt.textContent)) {
                        throw EbicsProcessingError(
                            "Amount number malformed: ${txDetails.amt.textContent}"
                        )
                    }
                    PainParseResult(
                        currency = txDetails.amt.getAttribute("Ccy"),
                        amount = txDetails.amt.textContent,
                        subject = txDetails.subject,
                        debtorIban = debtorIban,
                        debtorName = debtorName,
                        debtorBic = debtorBic,
                        creditorName = txDetails.creditorName,
                        creditorIban = txDetails.creditorIban,
                        creditorBic = txDetails.creditorBic,
                        pmtInfId = pmtInfId,
                        endToEndId = txDetails.endToEndId,
                        msgId = msgId
                    )
                }
            }
        }
    }
}

/**
 * Process a payment request in the pain.001 format.  Note:
 * the receiver IBAN is NOT checked to have one account at
 * the Sandbox.  That's because (1) it leaves open to send
 * payments outside of the running Sandbox and (2) may ease
 * tests where the preparation logic can skip creating also
 * the receiver account.  */
private fun handleCct(
    paymentRequest: String,
    requestingSubscriber: EbicsSubscriberEntity
) {
    val parseResult = parsePain001(paymentRequest)
    logger.debug("Handling Pain.001: ${parseResult.pmtInfId}, " +
            "for payment: ${parseResult.subject}")
    transaction(Connection.TRANSACTION_SERIALIZABLE, repetitionAttempts = 10) {
        // Check that subscriber has a bank account
        // and that they have rights over the debtor IBAN
        if (requestingSubscriber.bankAccount == null) throw EbicsProcessingError(
            "Subscriber '${requestingSubscriber.userId}' does not have a bank account."
        )
        if (requestingSubscriber.bankAccount!!.iban != parseResult.debtorIban) throw
                EbicsAccountAuthorisationFailed(
                    "Subscriber '${requestingSubscriber.userId}' does not have rights" +
                            " over the debtor IBAN '${parseResult.debtorIban}'"
                )
        val maybeExist = BankAccountTransactionEntity.find {
            BankAccountTransactionsTable.pmtInfId eq parseResult.pmtInfId
        }.firstOrNull()
        if (maybeExist != null) {
            logger.info(
                "Nexus submitted twice the Pain: ${maybeExist.pmtInfId}. Not taking any action." +
                        "  Sandbox gave it this reference: ${maybeExist.accountServicerReference}"
            )
            return@transaction
        }
        val bankAccount = getBankAccountFromIban(parseResult.debtorIban)
        if (parseResult.currency != bankAccount.demoBank.config.currency) throw EbicsRequestError(
            "[EBICS_PROCESSING_ERROR] Currency (${parseResult.currency}) not supported.",
            "091116"
        )
        // Check for the debit case.
        val maybeAmount = try {
            BigDecimal(parseResult.amount)
        } catch (e: Exception) {
            logger.warn("Although PAIN validated, BigDecimal didn't parse its amount (${parseResult.amount})!")
            throw EbicsProcessingError("The CCT request contains an invalid amount: ${parseResult.amount}")
        }
        if (maybeDebit(bankAccount.label, maybeAmount, bankAccount.demoBank.name))
            throw EbicsAmountCheckError("The requested amount (${parseResult.amount}) would exceed the debit threshold")

        // Get the two parties.
        BankAccountTransactionEntity.new {
            account = bankAccount
            demobank = bankAccount.demoBank
            creditorIban = parseResult.creditorIban
            creditorName = parseResult.creditorName
            creditorBic = parseResult.creditorBic
            debtorIban = parseResult.debtorIban
            debtorName = parseResult.debtorName
            debtorBic = parseResult.debtorBic
            subject = parseResult.subject
            amount = parseResult.amount
            currency = parseResult.currency
            date = getUTCnow().toInstant().toEpochMilli()
            pmtInfId = parseResult.pmtInfId
            endToEndId = parseResult.endToEndId
            accountServicerReference = "sandboxref-${getRandomString(16)}"
            direction = "DBIT"
        }
        val maybeLocalCreditor = BankAccountEntity.find(BankAccountsTable.iban eq parseResult.creditorIban).firstOrNull()
        if (maybeLocalCreditor != null) {
            BankAccountTransactionEntity.new {
                account = maybeLocalCreditor
                demobank = maybeLocalCreditor.demoBank
                creditorIban = parseResult.creditorIban
                creditorName = parseResult.creditorName
                creditorBic = parseResult.creditorBic
                debtorIban = parseResult.debtorIban
                debtorName = parseResult.debtorName
                debtorBic = parseResult.debtorBic
                subject = parseResult.subject
                amount = parseResult.amount
                currency = parseResult.currency
                date = getUTCnow().toInstant().toEpochMilli()
                pmtInfId = parseResult.pmtInfId
                accountServicerReference = "sandboxref-${getRandomString(16)}"
                direction = "CRDT"
            }
        }
    }
}

/**
 * This handler reports all the fresh transactions, belonging
 * to the querying subscriber.
 */
private fun handleEbicsC52(requestContext: RequestContext): ByteArray {
    val report = constructCamtResponse(
        52,
        requestContext.subscriber,
        dateRange = null
    )
    sandboxAssert(
        report.size == 1,
        "C52 response contains more than one Camt.052 document"
    )
    if (!XMLUtil.validateFromString(report[0])) {
        logger.error("This document was generated invalid:\n${report[0]}")
        throw EbicsProcessingError("One outgoing report was found invalid.")
    }
    return report.map { it.toByteArray() }.zip()
}

private fun handleEbicsC53(requestContext: RequestContext): ByteArray {
    // Fetch date range.
    val orderParams = requestContext.requestObject.header.static.orderDetails?.orderParams // as EbicsRequest.StandardOrderParams
    val dateRange = if (orderParams != null) {
        val standardOrderParams = orderParams as EbicsRequest.StandardOrderParams
        val start = standardOrderParams.dateRange?.start?.toGregorianCalendar()?.timeInMillis
        val end = standardOrderParams.dateRange?.end?.toGregorianCalendar()?.timeInMillis
        if (start == null || end == null) {
            // only accepting when both start/end are given.
            null
        } else {
            Pair(start, end)
        }
    } else
        null
    /**
     * By multiple statements, this function is responsible to return
     * a list of Strings: one for each statement.
     */
    val camtStatements = constructCamtResponse(
        53,
        requestContext.subscriber,
        dateRange
    )
    camtStatements.forEach {
        if (!XMLUtil.validateFromString(it)) {
            logger.error("This document was generated invalid:\n$it")
            throw EbicsProcessingError("One outgoing statement was found invalid.")
        }
    }
    return camtStatements.map { it.toByteArray() }.zip()
}

private suspend fun ApplicationCall.handleEbicsHia(header: EbicsUnsecuredRequest.Header, orderData: ByteArray) {
    InflaterInputStream(orderData.inputStream()).use { it.readAllBytes() }
    val keyObject = EbicsOrderUtil.decodeOrderDataXml<HIARequestOrderData>(orderData)
    val encPubXml = keyObject.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue
    val authPubXml = keyObject.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue
    val encPub = CryptoUtil.loadRsaPublicKeyFromComponents(encPubXml.modulus, encPubXml.exponent)
    val authPub = CryptoUtil.loadRsaPublicKeyFromComponents(authPubXml.modulus, authPubXml.exponent)

    val ok = transaction {
        val ebicsSubscriber = findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            logger.warn("ebics subscriber not found")
            throw EbicsInvalidRequestError()
        }
        when (ebicsSubscriber.state) {
            SubscriberState.NEW -> {}
            SubscriberState.PARTIALLY_INITIALIZED_INI -> {}
            SubscriberState.PARTIALLY_INITIALIZED_HIA, SubscriberState.INITIALIZED, SubscriberState.READY -> {
                return@transaction false
            }
        }

        ebicsSubscriber.authenticationKey = EbicsSubscriberPublicKeyEntity.new {
            this.rsaPublicKey = ExposedBlob(authPub.encoded)
            state = KeyState.NEW
        }
        ebicsSubscriber.encryptionKey = EbicsSubscriberPublicKeyEntity.new {
            this.rsaPublicKey = ExposedBlob(encPub.encoded)
            state = KeyState.NEW
        }
        ebicsSubscriber.state = when (ebicsSubscriber.state) {
            SubscriberState.NEW -> SubscriberState.PARTIALLY_INITIALIZED_HIA
            SubscriberState.PARTIALLY_INITIALIZED_INI -> SubscriberState.INITIALIZED
            else -> throw Exception("internal invariant failed")
        }
        return@transaction true
    }
    if (ok) {
        respondEbicsKeyManagement("[EBICS_OK]", "000000", "000000")
    } else {
        respondEbicsKeyManagement("[EBICS_INVALID_USER_OR_USER_STATE]", "091002", "000000")
    }
}

private suspend fun ApplicationCall.handleEbicsIni(header: EbicsUnsecuredRequest.Header, orderData: ByteArray) {
    InflaterInputStream(orderData.inputStream()).use { it.readAllBytes() }
    val keyObject = EbicsOrderUtil.decodeOrderDataXml<SignatureTypes.SignaturePubKeyOrderData>(orderData)
    val sigPubXml = keyObject.signaturePubKeyInfo.pubKeyValue.rsaKeyValue
    val sigPub = CryptoUtil.loadRsaPublicKeyFromComponents(sigPubXml.modulus, sigPubXml.exponent)

    val ok = transaction {
        val ebicsSubscriber =
            findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            logger.warn("ebics subscriber, ${dumpEbicsSubscriber(header.static)}, not found")
            throw EbicsUserUnknown(dumpEbicsSubscriber(header.static))
        }
        when (ebicsSubscriber.state) {
            SubscriberState.NEW -> {}
            SubscriberState.PARTIALLY_INITIALIZED_HIA -> {}
            SubscriberState.PARTIALLY_INITIALIZED_INI, SubscriberState.INITIALIZED, SubscriberState.READY -> {
                return@transaction false
            }
        }
        ebicsSubscriber.signatureKey = EbicsSubscriberPublicKeyEntity.new {
            this.rsaPublicKey = ExposedBlob(sigPub.encoded)
            state = KeyState.NEW
        }
        ebicsSubscriber.state = when (ebicsSubscriber.state) {
            SubscriberState.NEW -> SubscriberState.PARTIALLY_INITIALIZED_INI
            SubscriberState.PARTIALLY_INITIALIZED_HIA -> SubscriberState.INITIALIZED
            else -> throw Error("internal invariant failed")
        }
        return@transaction true
    }
    logger.info("Signature key inserted in database _and_ subscriber state changed accordingly")
    if (ok) {
        respondEbicsKeyManagement("[EBICS_OK]", "000000", "000000")
    } else {
        respondEbicsKeyManagement("[EBICS_INVALID_USER_OR_USER_STATE]", "091002", "000000")
    }
}

private suspend fun ApplicationCall.handleEbicsHpb(
    ebicsHostInfo: EbicsHostPublicInfo,
    requestDocument: Document,
    header: EbicsNpkdRequest.Header
) {
    val subscriberKeys = transaction {
        val ebicsSubscriber =
            findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            throw EbicsInvalidRequestError()
        }
        if (ebicsSubscriber.state != SubscriberState.INITIALIZED) {
            throw EbicsSubscriberStateError()
        }
        val authPubBlob = ebicsSubscriber.authenticationKey!!.rsaPublicKey
        val encPubBlob = ebicsSubscriber.encryptionKey!!.rsaPublicKey
        val sigPubBlob = ebicsSubscriber.signatureKey!!.rsaPublicKey
        SubscriberKeys(
            CryptoUtil.loadRsaPublicKey(authPubBlob.bytes),
            CryptoUtil.loadRsaPublicKey(encPubBlob.bytes),
            CryptoUtil.loadRsaPublicKey(sigPubBlob.bytes)
        )
    }
    val validationResult =
        XMLUtil.verifyEbicsDocument(requestDocument, subscriberKeys.authenticationPublicKey)
    if (!validationResult) {
        throw EbicsKeyManagementError("invalid signature", "90000")
    }
    val hpbRespondeData = HPBResponseOrderData().apply {
        this.authenticationPubKeyInfo = EbicsTypes.AuthenticationPubKeyInfoType().apply {
            this.authenticationVersion = "X002"
            this.pubKeyValue = EbicsTypes.PubKeyValueType().apply {
                this.rsaKeyValue = RSAKeyValueType().apply {
                    this.exponent = ebicsHostInfo.authenticationPublicKey.publicExponent.toByteArray()
                    this.modulus = ebicsHostInfo.authenticationPublicKey.modulus.toByteArray()
                }
            }
        }
        this.encryptionPubKeyInfo = EbicsTypes.EncryptionPubKeyInfoType().apply {
            this.encryptionVersion = "E002"
            this.pubKeyValue = EbicsTypes.PubKeyValueType().apply {
                this.rsaKeyValue = RSAKeyValueType().apply {
                    this.exponent = ebicsHostInfo.encryptionPublicKey.publicExponent.toByteArray()
                    this.modulus = ebicsHostInfo.encryptionPublicKey.modulus.toByteArray()
                }
            }
        }
        this.hostID = ebicsHostInfo.hostID
    }
    val compressedOrderData = EbicsOrderUtil.encodeOrderDataXml(hpbRespondeData)
    val encryptionResult = CryptoUtil.encryptEbicsE002(compressedOrderData, subscriberKeys.encryptionPublicKey)
    respondEbicsKeyManagement("[EBICS_OK]", "000000", "000000", encryptionResult, "OR01")
}

/**
 * Find the ebics host corresponding to the one specified in the header.
 */
private fun ensureEbicsHost(requestHostID: String): EbicsHostPublicInfo {
    return transaction {
        val ebicsHost =
            EbicsHostEntity.find { EbicsHostsTable.hostID.upperCase() eq requestHostID.uppercase(Locale.getDefault()) }.firstOrNull()
        if (ebicsHost == null) {
            logger.warn("client requested unknown HostID ${requestHostID}")
            throw EbicsKeyManagementError("[EBICS_INVALID_HOST_ID]", "091011")
        }
        val encryptionPrivateKey = CryptoUtil.loadRsaPrivateKey(ebicsHost.encryptionPrivateKey.bytes)
        val authenticationPrivateKey = CryptoUtil.loadRsaPrivateKey(ebicsHost.authenticationPrivateKey.bytes)
        EbicsHostPublicInfo(
            requestHostID,
            CryptoUtil.getRsaPublicFromPrivate(encryptionPrivateKey),
            CryptoUtil.getRsaPublicFromPrivate(authenticationPrivateKey)
        )
    }
}
fun receiveEbicsXmlInternal(xmlData: String): Document {
    // logger.debug("Data received: $xmlData")
    val requestDocument: Document = XMLUtil.parseStringIntoDom(xmlData)
    if (!XMLUtil.validateFromDom(requestDocument)) {
        println("Problematic document was: $requestDocument")
        throw EbicsInvalidXmlError()
    }
    return requestDocument
}

private fun makePartnerInfo(subscriber: EbicsSubscriberEntity): EbicsTypes.PartnerInfo {
    val bankAccount = getBankAccountFromSubscriber(subscriber)
    val customerProfile = getCustomer(bankAccount.label)
    return EbicsTypes.PartnerInfo().apply {
        this.accountInfoList = listOf(
            EbicsTypes.AccountInfo().apply {
                this.id = bankAccount.label
                this.accountHolder = customerProfile.name ?: "Never Given"
                this.accountNumberList = listOf(
                    EbicsTypes.GeneralAccountNumber().apply {
                        this.international = true
                        this.value = bankAccount.iban
                    }
                )
                this.currency = bankAccount.demoBank.config.currency
                this.description = "Ordinary Bank Account"
                this.bankCodeList = listOf(
                    EbicsTypes.GeneralBankCode().apply {
                        this.international = true
                        this.value = bankAccount.bic
                    }
                )
            }
        )
        this.addressInfo = EbicsTypes.AddressInfo().apply {
            this.name = "Address Info Object"
        }
        this.bankInfo = EbicsTypes.BankInfo().apply {
            this.hostID = subscriber.hostId
        }
        this.orderInfoList = listOf(
            EbicsTypes.AuthOrderInfoType().apply {
                this.description = "Transactions statement"
                this.orderType = "C53"
                this.transferType = "Download"
            },
            EbicsTypes.AuthOrderInfoType().apply {
                this.description = "Transactions report"
                this.orderType = "C52"
                this.transferType = "Download"
            },
            EbicsTypes.AuthOrderInfoType().apply {
                this.description = "Payment initiation (ZIPped payload)"
                this.orderType = "CCC"
                this.transferType = "Upload"
            },
            EbicsTypes.AuthOrderInfoType().apply {
                this.description = "Payment initiation (plain text payload)"
                this.orderType = "CCT"
                this.transferType = "Upload"
            },
            EbicsTypes.AuthOrderInfoType().apply {
                this.description = "vmk"
                this.orderType = "VMK"
                this.transferType = "Download"
            },
            EbicsTypes.AuthOrderInfoType().apply {
                this.description = "sta"
                this.orderType = "STA"
                this.transferType = "Download"
            }
        )
    }
}

private fun handleEbicsHtd(requestContext: RequestContext): ByteArray {
    val htd = HTDResponseOrderData().apply {
        this.partnerInfo = makePartnerInfo(requestContext.subscriber)
        this.userInfo = EbicsTypes.UserInfo().apply {
            this.name = "Some User"
            this.userID = EbicsTypes.UserIDType().apply {
                this.status = 5
                this.value = requestContext.subscriber.userId
            }
            this.permissionList = listOf(
                EbicsTypes.UserPermission().apply {
                    this.orderTypes = "C53 C52 CCC VMK STA"
                }
            )
        }
    }
    val str = XMLUtil.convertJaxbToString(htd)
    return str.toByteArray()
}

private fun handleEbicsHkd(requestContext: RequestContext): ByteArray {
    val hkd = HKDResponseOrderData().apply {
        this.partnerInfo = makePartnerInfo(requestContext.subscriber)
        this.userInfoList = listOf(
            EbicsTypes.UserInfo().apply {
                this.name = "Some User"
                this.userID = EbicsTypes.UserIDType().apply {
                    this.status = 1
                    this.value = requestContext.subscriber.userId
                }
                this.permissionList = listOf(
                    EbicsTypes.UserPermission().apply {
                        this.orderTypes = "C54 C53 C52 CCC"
                    }
                )
            })
    }
    val str = XMLUtil.convertJaxbToString(hkd)
    return str.toByteArray()
}

private data class RequestContext(
    val ebicsHost: EbicsHostEntity,
    val subscriber: EbicsSubscriberEntity,
    val clientEncPub: RSAPublicKey,
    val clientAuthPub: RSAPublicKey,
    val clientSigPub: RSAPublicKey,
    val hostEncPriv: RSAPrivateCrtKey,
    val hostAuthPriv: RSAPrivateCrtKey,
    val requestObject: EbicsRequest,
    val uploadTransaction: EbicsUploadTransactionEntity?,
    val downloadTransaction: EbicsDownloadTransactionEntity?
)

/**
 * Get segmentation values and the EBICS transaction ID, before
 * handing the response to 'createForDownloadTransferPhase()'.
 */
private fun handleEbicsDownloadTransactionTransfer(requestContext: RequestContext): EbicsResponse {
    val segmentNumber =
        requestContext.requestObject.header.mutable.segmentNumber?.value ?: throw EbicsInvalidRequestError()
    val transactionID = requestContext.requestObject.header.static.transactionID ?: throw EbicsInvalidRequestError()
    val downloadTransaction = requestContext.downloadTransaction ?: throw AssertionError()
    return EbicsResponse.createForDownloadTransferPhase(
        transactionID,
        downloadTransaction.numSegments,
        downloadTransaction.segmentSize,
        downloadTransaction.encodedResponse,
        segmentNumber.toInt()
    )
}

private fun handleEbicsDownloadTransactionInitialization(requestContext: RequestContext): EbicsResponse {
    val orderType =
        requestContext.requestObject.header.static.orderDetails?.orderType ?: throw EbicsInvalidRequestError()
    val nonce = requestContext.requestObject.header.static.nonce
    val transactionID = EbicsOrderUtil.generateTransactionId()
    logger.debug(
        "Handling download initialization for order type $orderType, " +
                "nonce: ${nonce?.toHexString() ?: "not given"}, " +
                "transaction ID: $transactionID"
    )
    val response = when (orderType) {
        "HTD" -> handleEbicsHtd(requestContext)
        "HKD" -> handleEbicsHkd(requestContext)
        "C53" -> handleEbicsC53(requestContext)
        "C52" -> handleEbicsC52(requestContext)
        "TSD" -> handleEbicsTSD()
        "PTK" -> handleEbicsPTK()
        else -> throw EbicsInvalidXmlError()
    }
    val compressedResponse = DeflaterInputStream(response.inputStream()).use {
        it.readAllBytes()
    }
    val enc = CryptoUtil.encryptEbicsE002(compressedResponse, requestContext.clientEncPub)
    val encodedResponse = Base64.getEncoder().encodeToString(enc.encryptedData)

    val segmentSize = 4096
    val totalSize = encodedResponse.length
    val numSegments = ((totalSize + segmentSize - 1) / segmentSize)

    EbicsDownloadTransactionEntity.new(transactionID) {
        this.subscriber = requestContext.subscriber
        this.host = requestContext.ebicsHost
        this.orderType = orderType
        this.segmentSize = segmentSize
        this.transactionKeyEnc = ExposedBlob(enc.encryptedTransactionKey)
        this.encodedResponse = encodedResponse
        this.numSegments = numSegments
        this.receiptReceived = false
    }
    /**
     * In case of C52, the payload (that includes all the pending
     * transactions) got at this point persisted into the database.
     * The next block causes such transactions NOT to be returned
     * along the next C52 request.
     */
    if (orderType == "C52") {
        val account = getBankAccountFromSubscriber(requestContext.subscriber)
        BankAccountFreshTransactionEntity.all().forEach {
            if (it.transactionRef.account.label == account.label)
                it.delete()
        }
    }
    return EbicsResponse.createForDownloadInitializationPhase(
        transactionID,
        numSegments,
        segmentSize,
        enc, // has customer key
        encodedResponse
    )
}

private fun handleEbicsUploadTransactionInitialization(requestContext: RequestContext): EbicsResponse {
    val orderType =
        requestContext.requestObject.header.static.orderDetails?.orderType ?: throw EbicsInvalidRequestError()
    val transactionID = EbicsOrderUtil.generateTransactionId()
    logger.debug("Handling upload initialization for order $orderType, " +
            "transactionID $transactionID, nonce: " +
            (requestContext.requestObject.header.static.nonce?.toHexString() ?: "not given")
    )
    val oidn = requestContext.subscriber.nextOrderID++
    if (EbicsOrderUtil.checkOrderIDOverflow(oidn)) throw NotImplementedError()
    val orderID = EbicsOrderUtil.computeOrderIDFromNumber(oidn)
    val numSegments =
        requestContext.requestObject.header.static.numSegments ?: throw EbicsInvalidRequestError()
    val transactionKeyEnc =
        requestContext.requestObject.body.dataTransfer?.dataEncryptionInfo?.transactionKey
            ?: throw EbicsInvalidRequestError()
    val encPubKeyDigest =
        requestContext.requestObject.body.dataTransfer?.dataEncryptionInfo?.encryptionPubKeyDigest?.value
            ?: throw EbicsInvalidRequestError()
    val encSigData = requestContext.requestObject.body.dataTransfer?.signatureData?.value
        ?: throw EbicsInvalidRequestError()
    val decryptedSignatureData = CryptoUtil.decryptEbicsE002(
        CryptoUtil.EncryptionResult(
            transactionKeyEnc,
            encPubKeyDigest,
            encSigData
        ), requestContext.hostEncPriv
    )
    val plainSigData = InflaterInputStream(decryptedSignatureData.inputStream()).use {
        it.readAllBytes()
    }
    EbicsUploadTransactionEntity.new(transactionID) {
        this.host = requestContext.ebicsHost
        this.subscriber = requestContext.subscriber
        this.lastSeenSegment = 0
        this.orderType = orderType
        this.orderID = orderID
        this.numSegments = numSegments.toInt()
        this.transactionKeyEnc = ExposedBlob(transactionKeyEnc)
    }
    val sigObj = XMLUtil.convertStringToJaxb<UserSignatureData>(plainSigData.toString(Charsets.UTF_8))
    for (sig in sigObj.value.orderSignatureList ?: listOf()) {
        logger.debug("inserting order signature for orderID $orderID, order type $orderType, transaction '$transactionID'")
        EbicsOrderSignatureEntity.new {
            this.orderID = orderID
            this.orderType = orderType
            this.partnerID = sig.partnerID
            this.userID = sig.userID
            this.signatureAlgorithm = sig.signatureVersion
            this.signatureValue = ExposedBlob(sig.signatureValue)
        }
    }
    return EbicsResponse.createForUploadInitializationPhase(transactionID, orderID)
}

private fun handleEbicsUploadTransactionTransmission(requestContext: RequestContext): EbicsResponse {
    val uploadTransaction = requestContext.uploadTransaction ?: throw EbicsInvalidRequestError()
    val requestObject = requestContext.requestObject
    val requestSegmentNumber =
        requestContext.requestObject.header.mutable.segmentNumber?.value?.toInt() ?: throw EbicsInvalidRequestError()
    val requestTransactionID = requestObject.header.static.transactionID ?: throw EbicsInvalidRequestError()
    if (requestSegmentNumber == 1 && uploadTransaction.numSegments == 1) {
        val encOrderData =
            requestObject.body.dataTransfer?.orderData ?: throw EbicsInvalidRequestError()
        val zippedData = CryptoUtil.decryptEbicsE002(
            uploadTransaction.transactionKeyEnc.bytes,
            Base64.getDecoder().decode(encOrderData),
            requestContext.hostEncPriv
        )
        val unzippedData =
            InflaterInputStream(zippedData.inputStream()).use { it.readAllBytes() }

        val sigs = EbicsOrderSignatureEntity.find {
            (EbicsOrderSignaturesTable.orderID eq uploadTransaction.orderID) and
                    (EbicsOrderSignaturesTable.orderType eq uploadTransaction.orderType)
        }
        if (sigs.count() == 0L) {
            throw EbicsInvalidRequestError()
        }
        for (sig in sigs) {
            if (sig.signatureAlgorithm == "A006") {

                val signedData = CryptoUtil.digestEbicsOrderA006(unzippedData)
                val res1 = CryptoUtil.verifyEbicsA006(
                    sig.signatureValue.bytes,
                    signedData,
                    requestContext.clientSigPub
                )
                if (!res1) {
                    throw EbicsInvalidRequestError()
                }

            } else {
                throw NotImplementedError()
            }
        }
        if (getOrderTypeFromTransactionId(requestTransactionID) == "CCT") {
            handleCct(unzippedData.toString(Charsets.UTF_8),
                requestContext.subscriber
            )
        }
        return EbicsResponse.createForUploadTransferPhase(
            requestTransactionID,
            requestSegmentNumber,
            true,
            uploadTransaction.orderID
        )
    } else {
        throw NotImplementedError()
    }
}
// req.header.static.hostID.
private fun makeRequestContext(requestObject: EbicsRequest): RequestContext {
    val staticHeader = requestObject.header.static
    val requestedHostId = staticHeader.hostID
    val ebicsHost =
        EbicsHostEntity.find { EbicsHostsTable.hostID.upperCase() eq requestedHostId.uppercase(Locale.getDefault()) }
            .firstOrNull()
    val requestTransactionID = requestObject.header.static.transactionID
    var downloadTransaction: EbicsDownloadTransactionEntity? = null
    var uploadTransaction: EbicsUploadTransactionEntity? = null
    val subscriber = if (requestTransactionID != null) {
        downloadTransaction = EbicsDownloadTransactionEntity.findById(requestTransactionID.uppercase(Locale.getDefault()))
        if (downloadTransaction != null) {
            downloadTransaction.subscriber
        } else {
            uploadTransaction = EbicsUploadTransactionEntity.findById(requestTransactionID)
            uploadTransaction?.subscriber
        }
    } else {
        val partnerID = staticHeader.partnerID ?: throw EbicsInvalidRequestError()
        val userID = staticHeader.userID ?: throw EbicsInvalidRequestError()
        findEbicsSubscriber(partnerID, userID, staticHeader.systemID)
    }

    if (ebicsHost == null) throw EbicsInvalidRequestError()

    /**
     * NOTE: production logic must check against READY state (the
     * one activated after the subscriber confirms their keys via post)
     */
    if (subscriber == null || subscriber.state != SubscriberState.INITIALIZED)
        throw EbicsSubscriberStateError()

    val hostAuthPriv = CryptoUtil.loadRsaPrivateKey(
        ebicsHost.authenticationPrivateKey.bytes
    )
    val hostEncPriv = CryptoUtil.loadRsaPrivateKey(
        ebicsHost.encryptionPrivateKey.bytes
    )
    val clientAuthPub =
        CryptoUtil.loadRsaPublicKey(subscriber.authenticationKey!!.rsaPublicKey.bytes)
    val clientEncPub =
        CryptoUtil.loadRsaPublicKey(subscriber.encryptionKey!!.rsaPublicKey.bytes)
    val clientSigPub =
        CryptoUtil.loadRsaPublicKey(subscriber.signatureKey!!.rsaPublicKey.bytes)

    return RequestContext(
        hostAuthPriv = hostAuthPriv,
        hostEncPriv = hostEncPriv,
        clientAuthPub = clientAuthPub,
        clientEncPub = clientEncPub,
        clientSigPub = clientSigPub,
        ebicsHost = ebicsHost,
        requestObject = requestObject,
        subscriber = subscriber,
        downloadTransaction = downloadTransaction,
        uploadTransaction = uploadTransaction
    )
}

suspend fun ApplicationCall.ebicsweb() {
    val requestDocument = this.request.call.receive<Document>()
    val requestedHostID = requestDocument.getElementsByTagName("HostID")
    this.attributes.put(
        EbicsHostIdAttribute,
        requestedHostID.item(0).textContent
    )
    when (requestDocument.documentElement.localName) {
        "ebicsUnsecuredRequest" -> {
            val requestObject = requestDocument.toObject<EbicsUnsecuredRequest>()
            logger.info("Serving a ${requestObject.header.static.orderDetails.orderType} request")

            val orderData = requestObject.body.dataTransfer.orderData.value
            val header = requestObject.header

            when (header.static.orderDetails.orderType) {
                "INI" -> handleEbicsIni(header, orderData)
                "HIA" -> handleEbicsHia(header, orderData)
                else -> throw EbicsInvalidXmlError()
            }
        }
        "ebicsHEVRequest" -> {
            val hevResponse = HEVResponse().apply {
                this.systemReturnCode = SystemReturnCodeType().apply {
                    this.reportText = "[EBICS_OK]"
                    this.returnCode = "000000"
                }
                this.versionNumber = listOf(HEVResponse.VersionNumber.create("H004", "02.50"))
            }

            val strResp = XMLUtil.convertJaxbToString(hevResponse)
            if (!XMLUtil.validateFromString(strResp)) throw SandboxError(
                HttpStatusCode.InternalServerError,
                "Outgoing HEV response is invalid"
            )
            respondText(strResp, ContentType.Application.Xml, HttpStatusCode.OK)
        }
        // FIXME: should check subscriber state?
        "ebicsNoPubKeyDigestsRequest" -> {
            val requestObject = requestDocument.toObject<EbicsNpkdRequest>()
            val hostInfo = ensureEbicsHost(requestObject.header.static.hostID)
            when (requestObject.header.static.orderDetails.orderType) {
                "HPB" -> handleEbicsHpb(hostInfo, requestDocument, requestObject.header)
                else -> throw EbicsInvalidXmlError()
            }
        }
        // FIXME: must check subscriber state.
        "ebicsRequest" -> {
            val requestObject = requestDocument.toObject<EbicsRequest>()
            val responseXmlStr = transaction(Connection.TRANSACTION_SERIALIZABLE, repetitionAttempts = 10) {
                // Step 1 of 3:  Get information about the host and subscriber
                val requestContext = makeRequestContext(requestObject)
                // Step 2 of 3:  Validate the signature
                val verifyResult = XMLUtil.verifyEbicsDocument(requestDocument, requestContext.clientAuthPub)
                if (!verifyResult) {
                    throw EbicsAccountAuthorisationFailed("Subscriber's signature did not verify")
                }
                // Step 3 of 3:  Generate response
                val ebicsResponse: EbicsResponse = when (requestObject.header.mutable.transactionPhase) {
                    EbicsTypes.TransactionPhaseType.INITIALISATION -> {
                        if (requestObject.header.static.numSegments == null) {
                            handleEbicsDownloadTransactionInitialization(requestContext)
                        } else {
                            handleEbicsUploadTransactionInitialization(requestContext)
                        }
                    }
                    EbicsTypes.TransactionPhaseType.TRANSFER -> {
                        if (requestContext.uploadTransaction != null) {
                            handleEbicsUploadTransactionTransmission(requestContext)
                        } else if (requestContext.downloadTransaction != null) {
                            handleEbicsDownloadTransactionTransfer(requestContext)
                        } else {
                            throw AssertionError()
                        }
                    }
                    EbicsTypes.TransactionPhaseType.RECEIPT -> {
                        val requestTransactionID =
                            requestObject.header.static.transactionID ?: throw EbicsInvalidRequestError()
                        if (requestContext.downloadTransaction == null)
                            throw EbicsInvalidRequestError()
                        logger.debug("Handling download receipt for EBICS transaction: " +
                                requestTransactionID)
                        /**
                         * The receipt phase means that the client has already
                         * received all the data related to the current download
                         * transaction.  Hence this data can now be removed from
                         * the database.
                         */
                        val ebicsData = transaction {
                            EbicsDownloadTransactionEntity.findById(requestTransactionID)
                        }
                        if (ebicsData == null)
                            throw SandboxError(
                                HttpStatusCode.InternalServerError,
                                "EBICS transaction $requestTransactionID was not" +
                                        "found in the database for deletion.",
                                LibeufinErrorCode.LIBEUFIN_EC_INCONSISTENT_STATE
                            )
                        ebicsData.delete()
                        val receiptCode =
                            requestObject.body.transferReceipt?.receiptCode ?: throw EbicsInvalidRequestError()
                        EbicsResponse.createForDownloadReceiptPhase(requestTransactionID, receiptCode == 0)
                    }
                }
                signEbicsResponse(ebicsResponse, requestContext.hostAuthPriv)
            }
            if (!XMLUtil.validateFromString(responseXmlStr)) throw SandboxError(
                HttpStatusCode.InternalServerError,
                "Outgoing EBICS XML is invalid"
            )
            respondText(responseXmlStr, ContentType.Application.Xml, HttpStatusCode.OK)
        }
        else -> {
            /* Log to console and return "unknown type" */
            logger.info("Unknown message, just logging it!")
            respond(
                HttpStatusCode.NotImplemented,
                SandboxError(
                    HttpStatusCode.NotImplemented,
                    "Not Implemented"
                )
            )
        }
    }
}
