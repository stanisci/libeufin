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
import tech.libeufin.nexus.ebics.Dialect
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
    creditAccount: IbanAccountMetadata,
    dialect: Dialect
): ByteArray {
    val version = "09"
    val zonedTimestamp = ZonedDateTime.ofInstant(initiationTimestamp, ZoneId.of("UTC"))
    val amountWithoutCurrency: String = getAmountNoCurrency(amount)
    return XmlBuilder.toBytes("Document") {
        attr("xmlns", "urn:iso:std:iso:20022:tech:xsd:pain.001.001.$version")
        attr("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
        attr("xsi:schemaLocation", "urn:iso:std:iso:20022:tech:xsd:pain.001.001.$version pain.001.001.$version.xsd")
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
                el("BtchBookg", "true")
                el("NbOfTxs", "1")
                el("CtrlSum", amountWithoutCurrency)
                el("PmtTpInf/SvcLvl/Cd", 
                    when (dialect) {
                        Dialect.postfinance -> "SDVA"
                        Dialect.gls -> "SEPA"
                    }
                )
                el("ReqdExctnDt/Dt", DateTimeFormatter.ISO_DATE.format(zonedTimestamp))
                el("Dbtr/Nm", debitAccount.name)
                el("DbtrAcct/Id/IBAN", debitAccount.iban)
                el("DbtrAgt/FinInstnId") {
                    if (debitAccount.bic != null) {
                        el("BICFI", debitAccount.bic)
                    } else {
                        el("Othr/Id", "NOTPROVIDED")
                    }
                }
                el("ChrgBr", "SLEV")
                el("CdtTrfTxInf") {
                    el("PmtId") {
                        el("InstrId", "NOTPROVIDED")
                        el("EndToEndId", "NOTPROVIDED")
                    }
                    el("Amt/InstdAmt") {
                        attr("Ccy", amount.currency)
                        text(amountWithoutCurrency)
                    }
                    if (creditAccount.bic != null) el("CdtrAgt/FinInstnId/BICFI", creditAccount.bic)
                    el("Cdtr") {
                        el("Nm", creditAccount.name)
                        // Addr might become a requirement in the future
                        /*el("PstlAdr") {
                            el("TwnNm", "Bochum")
                            el("Ctry", "DE")
                        }*/
                    }
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
    val info: String,
    val timestamp: Instant
) {
    fun msg(): String = buildString {
        append("${actionType}")
        if (code != null) append(" ${code.isoCode}")
        append(" - '${actionType.description}'")
        if (code != null) append(" '${code.description}'")
        if (info != "") append(" - '$info'")
    }

    override fun toString(): String = buildString {
        append("${timestamp.fmtDateTime()}")
        if (orderId != null) append(" ${orderId}")
        append(" ${msg()}")
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
                val info = map("AddtlInf") { text() }.joinToString("")
                CustomerAck(actionType, orderId, code, info, timestamp!!)
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
            buildString {
                append("${code()} '${description()}' - ")
                for (reason in reasons) {
                    append("${reason.code.isoCode} '${reason.code.description}' ")
                }
            }
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
    /** ISO20022 AccountServicerReference */
    val bankId: String,
    val amount: TalerAmount,
    val wireTransferSubject: String,
    override val executionTime: Instant,
    val debitPaytoUri: String
): TxNotification {
    override fun toString(): String {
        return "IN ${executionTime.fmtDate()} $amount '$bankId' debitor=$debitPaytoUri subject=\"$wireTransferSubject\""
    }
}

/** ISO20022 outgoing payment */
data class OutgoingPayment(
    /** ISO20022 MessageIdentification */
    val messageId: String,
    val amount: TalerAmount,
    val wireTransferSubject: String? = null, // not showing in camt.054
    override val executionTime: Instant,
    val creditPaytoUri: String? = null, // not showing in camt.054
): TxNotification {
    override fun toString(): String {
        return "OUT ${executionTime.fmtDate()} $amount '$messageId' creditor=$creditPaytoUri subject=\"$wireTransferSubject\""
    }
}

private fun XmlDestructor.payto(prefix: String): String? {
    val iban = opt("${prefix}Acct")?.one("Id")?.one("IBAN")?.text()
    return if (iban != null) {
        val payto = StringBuilder("payto://iban/$iban")
        val name = opt(prefix)?.opt("Pty")?.one("Nm")?.text()
        if (name != null) {
            val urlEncName = URLEncoder.encode(name, "utf-8")
            payto.append("?receiver-name=$urlEncName")
        }
        return payto.toString()
    } else {
        null
    }
}

/** Parse camt.054 or camt.053 file */
fun parseTx(
    notifXml: InputStream,
    acceptedCurrency: String
): List<TxNotification> {
    fun XmlDestructor.parseNotif(): List<RawTx> {
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
        var reversal = opt("RvslInd")?.bool() ?: false
        opt("BkTxCd") {
            opt("Domn") {
                // TODO automate enum generation for all those code
                val domainCode = one("Cd")
                one("Fmly") {
                    val familyCode = one("Cd")
                    val subFamilyCode = one("SubFmlyCd").text()
                    if (subFamilyCode == "RRTN" || subFamilyCode == "RPCR") {
                        reversal = true
                    }
                }
            }
        }
        val info = opt("AddtlNtryInf")?.text()
        val bookDate: Instant = one("BookgDt").one("Dt").date().atStartOfDay().toInstant(ZoneOffset.UTC)
        val ref = opt("AcctSvcrRef")?.text()
        return one("NtryDtls").map("TxDtls") {
            val kind = one("CdtDbtInd").text()
            val amount: TalerAmount = one("Amt") {
                val currency = attr("Ccy")
                /** FIXME: test by sending non-CHF to PoFi and see which currency gets here. */
                if (currency != acceptedCurrency) throw Exception("Currency $currency not supported")
                TalerAmount("$currency:${text()}")
            }
            var msgId = opt("Refs")?.opt("MsgId")?.text()
            val subject = opt("RmtInf")?.map("Ustrd") { text() }?.joinToString("")
            var debtorPayto = opt("RltdPties") { payto("Dbtr") }
            var creditorPayto = opt("RltdPties") { payto("Cdtr") }
            RawTx(
                kind,
                bookDate,
                amount,
                reversal,
                info,
                ref,
                msgId,
                subject,
                debtorPayto,
                creditorPayto
            )
        }
    }
    fun XmlDestructor.parseStatement(): RawTx {
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
        var reversal = opt("RvslInd")?.bool() ?: false
        val info = opt("AddtlNtryInf")?.text()
        val bookDate: Instant = one("BookgDt").one("Dt").date().atStartOfDay().toInstant(ZoneOffset.UTC)
        val kind = one("CdtDbtInd").text()
        val amount: TalerAmount = one("Amt") {
            val currency = attr("Ccy")
            /** FIXME: test by sending non-CHF to PoFi and see which currency gets here. */
            if (currency != acceptedCurrency) throw Exception("Currency $currency not supported")
            TalerAmount("$currency:${text()}")
        }
        val ref = opt("AcctSvcrRef")?.text()
        return one("NtryDtls").one("TxDtls") {
            opt("BkTxCd") {
                opt("Domn") {
                    // TODO automate enum generation for all those code
                    val domainCode = one("Cd")
                    one("Fmly") {
                        val familyCode = one("Cd")
                        val subFamilyCode = one("SubFmlyCd").text()
                        if (subFamilyCode == "RRTN" || subFamilyCode == "RPCR") {
                            reversal = true
                        }
                    }
                }
            }
            var msgId = opt("Refs")?.opt("MsgId")?.text()
            val subject = opt("RmtInf")?.map("Ustrd") { text() }?.joinToString("")
            var debtorPayto = opt("RltdPties") { payto("Dbtr") }
            var creditorPayto = opt("RltdPties") { payto("Cdtr") }
            RawTx(
                kind,
                bookDate,
                amount,
                reversal,
                info,
                ref,
                msgId,
                subject,
                debtorPayto,
                creditorPayto
            )
        }
    }
    val raws = mutableListOf<RawTx>()
    XmlDestructor.fromStream(notifXml, "Document") {
        opt("BkToCstmrDbtCdtNtfctn") {
            each("Ntfctn") {
                each("Ntry") {
                    raws.addAll(parseNotif())
                }
            }
        } ?: opt("BkToCstmrStmt") {
            each("Stmt") {
                one("Acct") {
                    // Sanity check on currency and IBAN ?
                }
                each("Ntry") {
                    raws.add(parseStatement())
                }
            }
        } ?: throw Exception("Missing BkToCstmrDbtCdtNtfctn or BkToCstmrStmt")
    }
    return raws.mapNotNull { it ->  
        try {
            parseTxLogic(it)
        } catch (e: TxErr) {
            // TODO: add more info in doc or in log message?
            logger.warn("skip incomplete tx: ${e.msg}")
            null
        }    
    }
}

private data class RawTx(
    val kind: String,
    val bookDate: Instant,
    val amount: TalerAmount,
    val reversal: Boolean,
    val info: String?,
    val ref: String?,
    val msgId: String?,
    val subject: String?,
    val debtorPayto: String?,
    val creditorPayto: String?
)

private class TxErr(val msg: String): Exception(msg)

private fun parseTxLogic(raw: RawTx): TxNotification {
    if (raw.reversal) {
        require("CRDT" == raw.kind) // TODO handle DBIT reversal
        if (raw.msgId == null) 
            throw TxErr("missing msg ID for Credit reversal ${raw.ref}")
        return TxNotification.Reversal(
            msgId = raw.msgId,
            reason = raw.info,
            executionTime = raw.bookDate
        )
    }
    return when (raw.kind) {
        "CRDT" -> {
            if (raw.ref == null)
                throw TxErr("missing subject for Credit ${raw.ref}")
            if (raw.subject == null)
                throw TxErr("missing subject for Credit ${raw.ref}")
            if (raw.debtorPayto == null)
                throw TxErr("missing debtor info for Credit ${raw.ref}")
            IncomingPayment(
                amount = raw.amount,
                bankId = raw.ref,
                debitPaytoUri = raw.debtorPayto,
                executionTime = raw.bookDate,
                wireTransferSubject = raw.subject
            )
        }
        "DBIT" -> {
            if (raw.msgId == null)
                throw TxErr("missing msg ID for Debit ${raw.ref}")
            OutgoingPayment(
                amount = raw.amount,
                messageId = raw.msgId,
                executionTime = raw.bookDate,
                creditPaytoUri = raw.creditorPayto
            )
        }
        else -> throw Exception("Unknown transaction notification kind '${raw.kind}'")
    }
}