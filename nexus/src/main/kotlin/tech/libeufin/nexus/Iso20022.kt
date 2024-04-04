/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

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
package tech.libeufin.nexus

import tech.libeufin.common.*
import java.io.InputStream
import java.net.URLEncoder
import java.time.*
import java.time.format.*

/**
 * Collects details to define the pain.001 namespace
 * XML attributes.
 */
data class Pain001Namespaces(
    val fullNamespace: String,
    val xsdFilename: String
)

/**
 * Gets the amount number, also converting it from the
 * Taler-friendly 8 fractional digits to the more bank
 * friendly with 2.
 *
 * @param amount the Taler amount where to extract the number
 * @return [String] of the amount number without the currency.
 */
fun getAmountNoCurrency(amount: TalerAmount): String {
    if (amount.frac == 0) {
        return amount.value.toString()
    } else {
        val fractionFormat = amount.frac.toString().padStart(8, '0').dropLastWhile { it == '0' }
        if (fractionFormat.length > 2) throw Exception("Sub-cent amounts not supported")
        return "${amount.value}.${fractionFormat}"
    }
}

/**
 * Create a pain.001 document.  It requires the debtor BIC.
 *
 * @param requestUid UID of this request, helps to make this request idempotent.
 * @param initiationTimestamp timestamp when the payment was initiated in the database.
 *                            Although this is NOT the pain.001 creation timestamp, it
 *                            will help making idempotent requests where one MsgId is
 *                            always associated with one, and only one creation timestamp.
 * @param debtorAccount [IbanPayto] bank account information of the EBICS subscriber that
 *                           sends this request.  It's expected to contain IBAN, BIC, and NAME.
 * @param amount amount to pay.  The caller is responsible for sanity-checking this
 *               value to match the bank expectation.  For example, that the decimal
 *               part formats always to at most two digits.
 * @param wireTransferSubject wire transfer subject.
 * @param creditAccount payment receiver in [IbanPayto].  It should contain IBAN and NAME.
 * @return raw pain.001 XML, or throws if the debtor BIC is not found.
 */
fun createPain001(
    requestUid: String,
    initiationTimestamp: Instant,
    debitAccount: IbanAccountMetadata,
    amount: TalerAmount,
    wireTransferSubject: String,
    creditAccount: IbanAccountMetadata
): ByteArray {
    val namespace = Pain001Namespaces(
        fullNamespace = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09",
        xsdFilename = "pain.001.001.09.ch.03.xsd"
    )
    val zonedTimestamp = ZonedDateTime.ofInstant(initiationTimestamp, ZoneId.of("UTC"))
    val amountWithoutCurrency: String = getAmountNoCurrency(amount)
    return XmlBuilder.toBytes("Document") {
        attr("xmlns", namespace.fullNamespace)
        attr("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
        attr("xsi:schemaLocation", "${namespace.fullNamespace} ${namespace.xsdFilename}")
        el("CstmrCdtTrfInitn") {
            el("GrpHdr") {
                el("MsgId", requestUid)
                el("CreDtTm", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zonedTimestamp))
                el("NbOfTxs", "1")
                el("CtrlSum", amountWithoutCurrency)
                el("InitgPty/Nm", debitAccount.name)
            }
            el("PmtInf") {
                el("PmtInfId", "NOTPROVIDED")
                el("PmtMtd", "TRF")
                el("BtchBookg", "false")
                el("ReqdExctnDt/Dt", DateTimeFormatter.ISO_DATE.format(zonedTimestamp))
                el("Dbtr/Nm", debitAccount.name)
                el("DbtrAcct/Id/IBAN", debitAccount.iban)
                if (debitAccount.bic != null) 
                    el("DbtrAgt/FinInstnId/BICFI", debitAccount.bic)
                el("CdtTrfTxInf") {
                    el("PmtId") {
                        el("InstrId", "NOTPROVIDED")
                        el("EndToEndId", "NOTPROVIDED")
                    }
                    el("Amt/InstdAmt") {
                        attr("Ccy", amount.currency)
                        text(amountWithoutCurrency)
                    }
                    el("Cdtr/Nm", creditAccount.name)
                    // TODO write credit account bic if we have it
                    el("CdtrAcct/Id/IBAN", creditAccount.iban)
                    el("RmtInf/Ustrd", wireTransferSubject)
                }
            }
        }
    }
}

data class CustomerAck(
    val actionType: HacAction,
    val orderId: String?,
    val code: ExternalStatusReasonCode?,
    val timestamp: Instant
) {
    fun msg(): String {
        var str = "${actionType}"
        if (code != null) str += " ${code.isoCode}"
        str += " - '${actionType.description}'"
        if (code != null) str += " '${code.description}'"
        return str
    }

    override fun toString(): String {
        var str = "${timestamp.fmtDateTime()}"
        if (orderId != null) str += " ${orderId}"
        return str + " ${msg()}"
    }
}

/** Parse HAC pain.002 XML file */
fun parseCustomerAck(xml: InputStream): List<CustomerAck> {
    return XmlDestructor.fromStream(xml, "Document") {
        one("CstmrPmtStsRpt").map("OrgnlPmtInfAndSts") {
            val actionType = one("OrgnlPmtInfId").enum<HacAction>()
            one("StsRsnInf") {
                var timestamp: Instant? = null
                var orderId: String? = null
                one("Orgtr").one("Id").one("OrgId").each("Othr") {
                    val value = one("Id")
                    val key = one("SchmeNm").one("Prtry").text()
                    when (key) {
                        "TimeStamp" -> {
                            timestamp = value.dateTime().toInstant(ZoneOffset.UTC)
                        }
                        "OrderID" -> orderId = value.text()
                        // TODO extract ids ?
                    }
                }
                val code = opt("Rsn")?.one("Cd")?.enum<ExternalStatusReasonCode>()
                CustomerAck(actionType, orderId, code, timestamp!!)
            }
        }
    }
}

data class PaymentStatus(
    val msgId: String,
    val paymentId: String?,
    val txId: String?,
    val paymentCode: ExternalPaymentGroupStatusCode,
    val txCode: ExternalPaymentTransactionStatusCode?,
    val reasons: List<Reason>
) { 
    fun id(): String {
        var str = "$msgId"
        if (paymentId != null) str += ".$paymentId"
        if (txId != null) str += ".$txId"
        return str
    }

    fun code(): String = txCode?.isoCode ?: paymentCode.isoCode

    fun description(): String = txCode?.description ?: paymentCode.description

    fun msg(): String {
        return if (reasons.isEmpty()) {
            "${code()} '${description()}'"
        } else if (reasons.size == 1) {
            "${code()} ${reasons[0].code.isoCode} - '${description()}' '${reasons[0].code.description}'"
        } else {
            var str = "${code()} '${description()}' - "
            for (reason in reasons) {
                str += "${reason.code.isoCode} '${reason.code.description}' "
            }
            str
        }
    }

    override fun toString(): String = "${id()} ${msg()}"
}

data class Reason (
    val code: ExternalStatusReasonCode,
    val information: String
)

/** Parse pain.002 XML file */
fun parseCustomerPaymentStatusReport(xml: InputStream): PaymentStatus {
    fun XmlDestructor.reasons(): List<Reason> {
        return map("StsRsnInf") {
            val code = one("Rsn").one("Cd").enum<ExternalStatusReasonCode>()
            // TODO parse information
            Reason(code, "")
        }
    }
    return XmlDestructor.fromStream(xml, "Document") {
        // TODO handle batch status
        one("CstmrPmtStsRpt") {
            val (msgId, msgCode, msgReasons) = one("OrgnlGrpInfAndSts") {
                val id = one("OrgnlMsgId").text()
                val code = opt("GrpSts")?.enum<ExternalPaymentGroupStatusCode>()
                val reasons = reasons()
                Triple(id, code, reasons)
            }
            opt("OrgnlPmtInfAndSts") {
                val payId = one("OrgnlPmtInfId").text()
                val payCode = one("PmtInfSts").enum<ExternalPaymentGroupStatusCode>()
                val payReasons = reasons()
                opt("TxInfAndSts") {
                    val txId = one("OrgnlInstrId").text()
                    val txCode = one("TxSts").enum<ExternalPaymentTransactionStatusCode>()
                    val txReasons = reasons()
                    PaymentStatus(msgId, payId, txId, payCode, txCode, txReasons)
                } ?: PaymentStatus(msgId, payId, null, payCode, null, payReasons)
            } ?: PaymentStatus(msgId, null, null, msgCode!!, null, msgReasons)
        }
    }
}

sealed interface TxNotification {
    val executionTime: Instant
    data class Reversal(
        val msgId: String,
        val reason: String?,
        override val executionTime: Instant
    ): TxNotification
}

/** ISO20022 incoming payment */
data class IncomingPayment(
    val amount: TalerAmount,
    val wireTransferSubject: String,
    val debitPaytoUri: String,
    override val executionTime: Instant,
    /** ISO20022 AccountServicerReference */
    val bankId: String
): TxNotification {
    override fun toString(): String {
        return "IN ${executionTime.fmtDate()} $amount '$bankId' debitor=$debitPaytoUri subject=$wireTransferSubject"
    }
}

/** ISO20022 outgoing payment */
data class OutgoingPayment(
    val amount: TalerAmount,
    override val executionTime: Instant,
    /** ISO20022 MessageIdentification */
    val messageId: String,
    val creditPaytoUri: String? = null, // not showing in camt.054
    val wireTransferSubject: String? = null // not showing in camt.054
): TxNotification {
    override fun toString(): String {
        return "OUT ${executionTime.fmtDate()} $amount '$messageId' creditor=$creditPaytoUri subject=$wireTransferSubject"
    }
}

/** Parse camt.054 XML file */
fun parseTxNotif(
    notifXml: InputStream,
    acceptedCurrency: String
): List<TxNotification> {
    fun notificationForEachTx(
        directionLambda: XmlDestructor.(Instant, Boolean, String?) -> Unit
    ) {
        XmlDestructor.fromStream(notifXml, "Document") {
            opt("BkToCstmrDbtCdtNtfctn")?.each("Ntfctn") {
                each("Ntry") {
                    val reversal = opt("RvslInd")?.bool() ?: false
                    val info = opt("AddtlNtryInf")?.text()
                    one("Sts") {
                        if (text() != "BOOK") {
                            one("Cd") {
                                if (text() != "BOOK")
                                    throw Exception("Found non booked transaction, " +
                                            "stop parsing.  Status was: ${text()}"
                                    )
                            }
                        }
                    }
                    val bookDate: Instant = one("BookgDt").one("Dt").date().atStartOfDay().toInstant(ZoneOffset.UTC)
                    one("NtryDtls").each("TxDtls") {
                        directionLambda(this, bookDate, reversal, info)
                    }
                }
            }
        }
    }

    val notifications = mutableListOf<TxNotification>()
    notificationForEachTx { bookDate, reversal, info ->
        val kind = one("CdtDbtInd").text()
        val amount: TalerAmount = one("Amt") {
            val currency = attr("Ccy")
            /**
             * FIXME: test by sending non-CHF to PoFi and see which currency gets here.
             */
            if (currency != acceptedCurrency) throw Exception("Currency $currency not supported")
            TalerAmount("$currency:${text()}")
        }
        if (reversal) {
            require("CRDT" == kind)
            val msgId = one("Refs").opt("MsgId")?.text()
            if (msgId == null) {
                logger.debug("Unsupported reversal without message id")
            } else {
                notifications.add(TxNotification.Reversal(
                    msgId = msgId,
                    reason = info,
                    executionTime = bookDate
                ))
            }
            return@notificationForEachTx
        }
        when (kind) {
            "CRDT" -> {
                val bankId: String = one("Refs").one("AcctSvcrRef").text()
                // Obtaining payment subject. 
                val subject = opt("RmtInf")?.map("Ustrd") { text() }?.joinToString("")
                if (subject == null) {
                    logger.debug("Skip notification '$bankId', missing subject")
                    return@notificationForEachTx
                }

                // Obtaining the payer's details
                val debtorPayto = StringBuilder("payto://iban/")
                one("RltdPties") {
                    one("DbtrAcct").one("Id").one("IBAN") {
                        debtorPayto.append(text())
                    }
                    // warn: it might need the postal address too..
                    one("Dbtr").opt("Pty")?.one("Nm") {
                        val urlEncName = URLEncoder.encode(text(), "utf-8")
                        debtorPayto.append("?receiver-name=$urlEncName")
                    }
                }
                notifications.add(IncomingPayment(
                    amount = amount,
                    bankId = bankId,
                    debitPaytoUri = debtorPayto.toString(),
                    executionTime = bookDate,
                    wireTransferSubject = subject.toString()
                ))
            }
            "DBIT" -> {
                val messageId = one("Refs").one("MsgId").text()
                notifications.add(OutgoingPayment(
                    amount = amount,
                    messageId = messageId,
                    executionTime = bookDate
                ))
            }
            else -> throw Exception("Unknown transaction notification kind '$kind'")
        }        
    }
    return notifications
}