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
                // Use for idempotency as banks will refuse to process EBICS request with the same MsgId for a pre- agreed period
                // This is especially important for bounces
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
                        // Used to identify this transaction in CAMT files when MsgId is not present
                        el("EndToEndId", requestUid)
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
    /** ISO20022 MessageIdentification & EndToEndId */
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
    /*
        In ISO 20022 specifications, most fields are optional and the same information 
        can be written several times in different places. For libeufin, we're only 
        interested in a subset of the available values that can be found in both camt.053 
        and camt.054. As there are many similarities between these files, we use the same 
        function to share as much code as possible. This function should not fail on 
        legitimate files and should simply warn when available informations are insufficient.
    */

    /** Assert that transaction status is BOOK */
    fun XmlDestructor.assertBooked() {
        one("Sts") {
            val status = opt("Cd")?.text() ?: text()
            require(status == "BOOK") {
                "Found non booked transaction, stop parsing: expected BOOK got $status"
            }
        }
    }
    /** Parse information commonly founded a the top of the XML tree */
    fun XmlDestructor.parseHead(): TxHead = TxHead(
        reversal = opt("RvslInd")?.bool() ?: false,
        entryInfo = opt("AddtlNtryInf")?.text(),
        date = one("BookgDt").one("Dt").date().atStartOfDay().toInstant(ZoneOffset.UTC),
        entryRef = opt("AcctSvcrRef")?.text()
    )
    /** Parse transaction code */
    fun XmlDestructor.parseCode(head: TxHead) {
        opt("BkTxCd") {
            opt("Domn") {
                // TODO automate enum generation for all those code
                val domainCode = one("Cd")
                one("Fmly") {
                    val familyCode = one("Cd")
                    val subFamilyCode = one("SubFmlyCd").text()
                    if (subFamilyCode == "RRTN" || subFamilyCode == "RPCR") {
                        head.reversal = true
                    }
                }
            }
        }
    }
    /** Parse information commonly founded a the bottom or the top of the XML tree */
    fun XmlDestructor.parseMid(): TxMid = TxMid(
        kind = one("CdtDbtInd").text(),
        amount = one("Amt") {
            val currency = attr("Ccy")
            /** FIXME: test by sending non-CHF to PoFi and see which currency gets here. */
            if (currency != acceptedCurrency) throw Exception("Currency $currency not supported")
            TalerAmount("$currency:${text()}")
        }
    )
    /** Parse information commonly founded a the bottom of the XML tree */
    fun XmlDestructor.parseBtm(): TxBtm = TxBtm(
        msgId = opt("Refs")?.opt("MsgId")?.text(),
        ref = opt("Refs")?.opt("AcctSvcrRef")?.text(),
        subject = opt("RmtInf")?.map("Ustrd") { text() }?.joinToString(""),
        // TODO RltdAgts can have more info on debtor and creditor
        debtorPayto = opt("RltdPties") { payto("Dbtr") },
        creditorPayto = opt("RltdPties") { payto("Cdtr") },
    )
    /** Parse camt.054 entry */
    fun XmlDestructor.parseNotif(): List<RawTx> {
        assertBooked()
        val head = parseHead()
        parseCode(head)
        return one("NtryDtls").map("TxDtls") {
            val mid = parseMid()
            val btm = parseBtm()
            RawTx(head, mid, btm)
        }
    }
    /** Parse camt.053 entry */
    fun XmlDestructor.parseStatement(): RawTx {
        assertBooked()
        val head = parseHead()
        val mid = parseMid()
        return one("NtryDtls").one("TxDtls") {
            parseCode(head)
            val btm = parseBtm()
            RawTx(head, mid, btm)
        }
    }
    val raws = mutableListOf<RawTx>()
    XmlDestructor.fromStream(notifXml, "Document") {
        opt("BkToCstmrDbtCdtNtfctn") { // Camt.054
            each("Ntfctn") {
                opt("Acct") {
                    // Sanity check on currency and IBAN ?
                }
                each("Ntry") {
                    raws.addAll(parseNotif())
                }
            }
        } ?: opt("BkToCstmrStmt") { // Camt.053
            each("Stmt") {
                opt("Acct") {
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


private data class TxHead(
    var reversal: Boolean,
    val entryInfo: String?,
    val date: Instant,
    val entryRef: String?
)

private data class TxMid(
    val kind: String,
    val amount: TalerAmount,
)

private data class TxBtm(
    val msgId: String?,
    val ref: String?,
    val subject: String?,
    val debtorPayto: String?,
    val creditorPayto: String?
)

private data class RawTx(
    val head: TxHead,
    val mid: TxMid,
    val btm: TxBtm
)

private class TxErr(val msg: String): Exception(msg)

private fun parseTxLogic(raw: RawTx): TxNotification {
    val (reversal, entryInfo, date, entryRef) = raw.head
    val (kind, amount) = raw.mid
    val (msgId, ref, subject, debtorPayto, creditorPayto) = raw.btm
    val dbgRef = ref ?: entryRef
    if (reversal) {
        // TODO parse reason code if present
        require("CRDT" == kind) // TODO handle DBIT reversal
        if (msgId == null) 
            throw TxErr("missing msg ID for Credit reversal $dbgRef")
        return TxNotification.Reversal(
            msgId = msgId,
            reason = entryInfo,
            executionTime = date
        )
    }
    return when (kind) {
        "CRDT" -> {
            if (dbgRef == null)
                throw TxErr("missing ref for Credit $dbgRef")
            if (subject == null)
                throw TxErr("missing subject for Credit $dbgRef")
            if (debtorPayto == null)
                throw TxErr("missing debtor info for Credit $dbgRef")
            IncomingPayment(
                amount = amount,
                bankId = dbgRef,
                debitPaytoUri = debtorPayto,
                executionTime = date,
                wireTransferSubject = subject
            )
        }
        "DBIT" -> {
            if (msgId == null)
                throw TxErr("missing msg ID for Debit $dbgRef")
            OutgoingPayment(
                amount = amount,
                messageId = msgId,
                executionTime = date,
                creditPaytoUri = creditorPayto,
                wireTransferSubject = subject
            )
        }
        else -> throw Exception("Unknown transaction notification kind '$kind'")
    }
}