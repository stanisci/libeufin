package tech.libeufin.nexus

import tech.libeufin.util.*
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
    if (amount.fraction.toString().length > 8)
        throw Exception("Taler amount must have at most 8 fractional digits")
    if (amount.fraction == 0) {
        return amount.value.toString()
    } else {
        val fractionFormat = amount.fraction.toString().padStart(8, '0').dropLastWhile { it == '0' }
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
    creditAccount: IbanPayto
): String {
    val namespace = Pain001Namespaces(
        fullNamespace = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09",
        xsdFilename = "pain.001.001.09.ch.03.xsd"
    )
    val zonedTimestamp = ZonedDateTime.ofInstant(initiationTimestamp, ZoneId.of("UTC"))
    val amountWithoutCurrency: String = getAmountNoCurrency(amount)
    val creditorName: String = creditAccount.receiverName
        ?: throw NexusSubmitException(
            "Cannot operate without the creditor name",
            stage=NexusSubmissionStage.pain
        )
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
                            text(creditorName)
                        }
                        element("CdtrAcct/Id/IBAN") {
                            text(creditAccount.iban)
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

/**
 * Thrown if the parser expects DBIT but the transaction
 * is CRDT, and vice-versa.
 */
class WrongPaymentDirection(val msg: String) : Exception(msg)

/**
 * Parses a camt.054 document looking for outgoing payments.
 *
 * @param notifXml input document.
 * @param acceptedCurrency currency accepted by Nexus
 * @return the list of outgoing payments.
 */
fun parseOutgoingTxNotif(
    notifXml: String,
    acceptedCurrency: String,
): List<OutgoingPayment> {
    val ret = mutableListOf<OutgoingPayment>()
    notificationForEachTx(notifXml) { bookDate ->
        requireUniqueChildNamed("CdtDbtInd") {
            if (focusElement.textContent != "DBIT")
                throw WrongPaymentDirection("The payment is not outgoing, won't parse it")
        }
        // Obtaining the amount.
        val amount: TalerAmount = requireUniqueChildNamed("Amt") {
            val currency = focusElement.getAttribute("Ccy")
            if (currency != acceptedCurrency) throw Exception("Currency $currency not supported")
            getTalerAmount(focusElement.textContent, currency)
        }

        /**
         * The MsgId extracted in the block below matches the one that
         * was specified as the MsgId element in the pain.001 that originated
         * this outgoing payment.  MsgId is considered unique because the
         * bank enforces its uniqueness.  Associating MsgId to this outgoing
         * payment is also convenient to match its initiated outgoing payment
         * in the database for reconciliation.
         */
        val uidFromBank = StringBuilder()
        requireUniqueChildNamed("Refs") {
            requireUniqueChildNamed("MsgId") {
                uidFromBank.append(focusElement.textContent)
            }
        }

        ret.add(
            OutgoingPayment(
            amount = amount,
            bankTransferId = uidFromBank.toString(),
            executionTime = bookDate
        )
        )
    }
    return ret
}

/**
 * Searches incoming payments in a camt.054 (Detailavisierung) document.
 *
 * @param notifXml camt.054 input document
 * @param acceptedCurrency currency accepted by Nexus.
 * @return the list of incoming payments to ingest in the database.
 */
fun parseIncomingTxNotif(
    notifXml: String,
    acceptedCurrency: String
): List<IncomingPayment> {
    val ret = mutableListOf<IncomingPayment>()
    notificationForEachTx(notifXml) { bookDate ->
        // Check the direction first.
        requireUniqueChildNamed("CdtDbtInd") {
            if (focusElement.textContent != "CRDT")
                throw WrongPaymentDirection("The payment is not incoming, won't parse it")
        }
        val amount: TalerAmount = requireUniqueChildNamed("Amt") {
            val currency = focusElement.getAttribute("Ccy")
            if (currency != acceptedCurrency) throw Exception("Currency $currency not supported")
            getTalerAmount(focusElement.textContent, currency)
        }
        // Obtaining payment UID.
        val uidFromBank: String = requireUniqueChildNamed("Refs") {
            requireUniqueChildNamed("AcctSvcrRef") {
                focusElement.textContent
            }
        }
        // Obtaining payment subject.
        val subject = StringBuilder()
        requireUniqueChildNamed("RmtInf") {
            this.mapEachChildNamed("Ustrd") {
                val piece = this.focusElement.textContent
                subject.append(piece)
            }
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
                requireUniqueChildNamed("Pty") {
                    requireUniqueChildNamed("Nm") {
                        val urlEncName = URLEncoder.encode(focusElement.textContent, "utf-8")
                        debtorPayto.append("?receiver-name=$urlEncName")
                    }
                }
            }
        }
        ret.add(
            IncomingPayment(
                amount = amount,
                bankTransferId = uidFromBank,
                debitPaytoUri = debtorPayto.toString(),
                executionTime = bookDate,
                wireTransferSubject = subject.toString()
            )
        )
    }
    return ret
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
                            requireUniqueChildNamed("Cd") {
                                if (focusElement.textContent != "BOOK")
                                    throw Exception("Found non booked transaction, " +
                                            "stop parsing.  Status was: ${focusElement.textContent}"
                                    )
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