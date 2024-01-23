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
import tech.libeufin.ebics.*
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter


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
    creditAccount: FullIbanPayto
): String {
    val namespace = Pain001Namespaces(
        fullNamespace = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09",
        xsdFilename = "pain.001.001.09.ch.03.xsd"
    )
    val zonedTimestamp = ZonedDateTime.ofInstant(initiationTimestamp, ZoneId.of("UTC"))
    val amountWithoutCurrency: String = getAmountNoCurrency(amount)
    return constructXml {
        root("Document") {
            attribute(
                "xmlns",
                namespace.fullNamespace
            )
            attribute(
                "xmlns:xsi",
                "http://www.w3.org/2001/XMLSchema-instance"
            )
            attribute(
                "xsi:schemaLocation",
                "${namespace.fullNamespace} ${namespace.xsdFilename}"
            )
            element("CstmrCdtTrfInitn") {
                element("GrpHdr") {
                    element("MsgId") {
                        text(requestUid)
                    }
                    element("CreDtTm") {
                        val dateFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        text(dateFormatter.format(zonedTimestamp))
                    }
                    element("NbOfTxs") {
                        text("1")
                    }
                    element("CtrlSum") {
                        text(amountWithoutCurrency)
                    }
                    element("InitgPty/Nm") {
                        text(debitAccount.name)
                    }
                }
                element("PmtInf") {
                    element("PmtInfId") {
                        text("NOTPROVIDED")
                    }
                    element("PmtMtd") {
                        text("TRF")
                    }
                    element("BtchBookg") {
                        text("false")
                    }
                    element("ReqdExctnDt") {
                        element("Dt") {
                            text(DateTimeFormatter.ISO_DATE.format(zonedTimestamp))
                        }
                    }
                    element("Dbtr/Nm") {
                        text(debitAccount.name)
                    }
                    element("DbtrAcct/Id/IBAN") {
                        text(debitAccount.iban)
                    }
                    element("DbtrAgt/FinInstnId/BICFI") {
                        text(debitAccount.bic)
                    }
                    element("CdtTrfTxInf") {
                        element("PmtId") {
                            element("InstrId") { text("NOTPROVIDED") }
                            element("EndToEndId") { text("NOTPROVIDED") }
                        }
                        element("Amt/InstdAmt") {
                            attribute("Ccy", amount.currency)
                            text(amountWithoutCurrency)
                        }
                        element("Cdtr/Nm") {
                            text(creditAccount.receiverName)
                        }
                        element("CdtrAcct/Id/IBAN") {
                            text(creditAccount.payto.iban)
                        }
                        element("RmtInf/Ustrd") {
                            text(wireTransferSubject)
                        }
                    }
                }
            }
        }
    }
}

data class CustomerAck(
    val actionType: String,
    val code: ExternalStatusReasonCode?,
    val timestamp: Instant
) {
    override fun toString(): String {
        return if (code != null)
            "${timestamp.fmtDateTime()} ${actionType} ${code.isoCode} '${code.description}'"
        else 
            "${timestamp.fmtDateTime()} ${actionType}"
    }
}

/**
 * Extract logs from a pain.002 HAC document.
 *
 * @param xml pain.002 input document
 */
fun parseCustomerAck(xml: String): List<CustomerAck> {
    val notifDoc = XMLUtil.parseStringIntoDom(xml)
    return destructXml(notifDoc) {
        requireRootElement("Document") {
            requireUniqueChildNamed("CstmrPmtStsRpt") {
                mapEachChildNamed("OrgnlPmtInfAndSts") {
                    val actionType = requireUniqueChildNamed("OrgnlPmtInfId") {
                        focusElement.textContent
                    }
                    
                    requireUniqueChildNamed("StsRsnInf") {
                        var timestamp: Instant? = null;
                        requireUniqueChildNamed("Orgtr") {
                            requireUniqueChildNamed("Id") {
                                requireUniqueChildNamed("OrgId") {
                                    mapEachChildNamed("Othr") {
                                        val value = requireUniqueChildNamed("Id") {
                                            focusElement.textContent
                                        }
                                        val key = requireUniqueChildNamed("SchmeNm") {
                                            requireUniqueChildNamed("Prtry") {
                                                focusElement.textContent
                                            }
                                        }
                                        when (key) {
                                            "TimeStamp" -> {
                                                timestamp = parseCamtTime(value.trimEnd('Z')) // TODO better date parsing
                                            }
                                            // TODO extract ids ?
                                        }
                                    }
                                }
                            }
                        }
                        val code = maybeUniqueChildNamed("Rsn") {
                            requireUniqueChildNamed("Cd") {
                                ExternalStatusReasonCode.valueOf(focusElement.textContent)
                            }
                        }
                        CustomerAck(actionType, code, timestamp!!)
                    }
                }
            }
        }
    }
}

data class PaymentStatus(
    val msgId: String,
    val code: ExternalPaymentGroupStatusCode,
    val reasons: List<Reason>
) {
    override fun toString(): String {
        var builder = "'${msgId}' ${code.isoCode} '${code.description}'"
        for (reason in reasons) {
            builder += " - ${reason.code.isoCode} '${reason.code.description}'"
        }
        return builder
    }
}

data class Reason (
    val code: ExternalStatusReasonCode,
    val information: String
)

/**
 * Extract payment status from a pain.002 document.
 *
 * @param xml pain.002 input document
 */
fun parseCustomerPaymentStatusReport(xml: String): PaymentStatus {
    val notifDoc = XMLUtil.parseStringIntoDom(xml)
    fun XmlElementDestructor.reasons(): List<Reason> {
        return mapEachChildNamed("StsRsnInf") {
            val code = requireUniqueChildNamed("Rsn") {
                requireUniqueChildNamed("Cd") {
                    ExternalStatusReasonCode.valueOf(focusElement.textContent)
                }
            }
            // TODO parse information
            Reason(code, "")
        }
    }
    return destructXml(notifDoc) {
        requireRootElement("Document") {
            requireUniqueChildNamed("CstmrPmtStsRpt") {
                val (msgId, msgCode, msgReasons) = requireUniqueChildNamed("OrgnlGrpInfAndSts") {
                    val id = requireUniqueChildNamed("OrgnlMsgId") {
                        focusElement.textContent
                    }
                    val code = maybeUniqueChildNamed("GrpSts") {
                        ExternalPaymentGroupStatusCode.valueOf(focusElement.textContent)
                    }
                    val reasons = reasons()
                    Triple(id, code, reasons)
                }
                val paymentInfo = maybeUniqueChildNamed("OrgnlPmtInfAndSts") {
                    val code = requireUniqueChildNamed("PmtInfSts") {
                        ExternalPaymentGroupStatusCode.valueOf(focusElement.textContent)
                    }
                    val reasons = reasons()
                    Pair(code, reasons)
                }

                // TODO handle multi level code better 
                if (paymentInfo != null) {
                    val (code, reasons) = paymentInfo
                    PaymentStatus(msgId, code, reasons)
                } else {
                    PaymentStatus(msgId, msgCode!!, msgReasons)
                }
            }
        }
    }
}

/**
 * Searches payments in a camt.054 (Detailavisierung) document.
 *
 * @param notifXml camt.054 input document
 * @param acceptedCurrency currency accepted by Nexus
 * @param incoming list of incoming payments
 * @param outgoing list of outgoing payments
 */
fun parseTxNotif(
    notifXml: String,
    acceptedCurrency: String,
    incoming: MutableList<IncomingPayment>,
    outgoing: MutableList<OutgoingPayment>
) {
    notificationForEachTx(notifXml) { bookDate ->
        val kind = requireUniqueChildNamed("CdtDbtInd") {
            focusElement.textContent
        }
        val amount: TalerAmount = requireUniqueChildNamed("Amt") {
            val currency = focusElement.getAttribute("Ccy")
            /**
             * FIXME: test by sending non-CHF to PoFi and see which currency gets here.
             */
            if (currency != acceptedCurrency) throw Exception("Currency $currency not supported")
            TalerAmount("$currency:${focusElement.textContent}")
        }
        when (kind) {
            "CRDT" -> {
                val bankId: String = requireUniqueChildNamed("Refs") {
                    requireUniqueChildNamed("AcctSvcrRef") {
                        focusElement.textContent
                    }
                }
                // Obtaining payment subject. 
                val subject = maybeUniqueChildNamed("RmtInf") {
                    val subject = StringBuilder()
                    mapEachChildNamed("Ustrd") {
                        val piece = focusElement.textContent
                        subject.append(piece)
                    }
                    subject
                }
                if (subject == null) {
                    logger.debug("Skip notification '$bankId', missing subject")
                    return@notificationForEachTx
                }

                // Obtaining the payer's details
                val debtorPayto = StringBuilder("payto://iban/")
                requireUniqueChildNamed("RltdPties") {
                    requireUniqueChildNamed("DbtrAcct") {
                        requireUniqueChildNamed("Id") {
                            requireUniqueChildNamed("IBAN") {
                                debtorPayto.append(focusElement.textContent)
                            }
                        }
                    }
                    // warn: it might need the postal address too..
                    requireUniqueChildNamed("Dbtr") {
                        maybeUniqueChildNamed("Pty") {
                            requireUniqueChildNamed("Nm") {
                                val urlEncName = URLEncoder.encode(focusElement.textContent, "utf-8")
                                debtorPayto.append("?receiver-name=$urlEncName")
                            }
                        }
                    }
                }
                incoming.add(
                    IncomingPayment(
                        amount = amount,
                        bankId = bankId,
                        debitPaytoUri = debtorPayto.toString(),
                        executionTime = bookDate,
                        wireTransferSubject = subject.toString()
                    )
                )
            }
            "DBIT" -> {
                val messageId = requireUniqueChildNamed("Refs") {
                    requireUniqueChildNamed("MsgId") {
                        focusElement.textContent
                    }
                }

                outgoing.add(
                    OutgoingPayment(
                        amount = amount,
                        messageId = messageId,
                        executionTime = bookDate
                    )
                )
            }
            else -> throw Exception("Unknown transaction notification kind '$kind'")
        }        
    }
}

/**
 * Navigates the camt.054 (Detailavisierung) until its leaves, where
 * then it invokes the related parser, according to the payment direction.
 *
 * @param notifXml the input document.
 * @return any incoming payment as a list of [IncomingPayment]
 */
private fun notificationForEachTx(
    notifXml: String,
    directionLambda: XmlElementDestructor.(Instant) -> Unit
) {
    val notifDoc = XMLUtil.parseStringIntoDom(notifXml)
    destructXml(notifDoc) {
        requireRootElement("Document") {
            requireUniqueChildNamed("BkToCstmrDbtCdtNtfctn") {
                mapEachChildNamed("Ntfctn") {
                    mapEachChildNamed("Ntry") {
                        requireUniqueChildNamed("Sts") {
                            if (focusElement.textContent != "BOOK") {
                                requireUniqueChildNamed("Cd") {
                                    if (focusElement.textContent != "BOOK")
                                        throw Exception("Found non booked transaction, " +
                                                "stop parsing.  Status was: ${focusElement.textContent}"
                                        )
                                }
                            }
                        }
                        val bookDate: Instant = requireUniqueChildNamed("BookgDt") {
                            requireUniqueChildNamed("Dt") {
                                parseBookDate(focusElement.textContent)
                            }
                        }
                        mapEachChildNamed("NtryDtls") {
                            mapEachChildNamed("TxDtls") {
                                directionLambda(this, bookDate)
                            }
                        }
                    }
                }
            }
        }
    }
}