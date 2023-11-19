package tech.libeufin.nexus

import tech.libeufin.util.IbanPayto
import tech.libeufin.util.constructXml
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
    return constructXml(indent = true) {
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
                        text("true")
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