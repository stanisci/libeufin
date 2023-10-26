package tech.libeufin.nexus

import tech.libeufin.util.IbanPayto
import tech.libeufin.util.constructXml
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter


data class Pain001Namespaces(
    val fullNamespace: String,
    val xsdFilename: String
)

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
    debitAccount: IbanPayto,
    amount: TalerAmount,
    wireTransferSubject: String,
    creditAccount: IbanPayto
): String {
    val namespace = Pain001Namespaces(
        fullNamespace = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09",
        xsdFilename = "pain.001.001.09.ch.03.xsd"
    )
    val zonedTimestamp = ZonedDateTime.ofInstant(initiationTimestamp, ZoneId.of("UTC"))
    val amountWithoutCurrency: String = amount.stringify().split(":").run {
        if (this.size != 2) throw Exception("Invalid stringified amount: $amount")
        return@run this[1]
    }
    val debtorBic: String = debitAccount.bic ?: throw Exception("Cannot operate without the debtor BIC")
    val debtorName: String = debitAccount.receiverName ?: throw Exception("Cannot operate without the debtor name")
    val creditorName: String = creditAccount.receiverName ?: throw Exception("Cannot operate without the creditor name")
    return constructXml(indent = true) {
        root("Document") {
            attribute("xmlns", namespace.fullNamespace)
            attribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
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
                        text(debtorName)
                    }
                }
                element("PmtInf") {
                    element("PmtInfId") {
                        text("NOT GIVEN")
                    }
                    element("PmtMtd") {
                        text("TRF")
                    }
                    element("BtchBookg") {
                        text("true")
                    }
                    element("NbOfTxs") {
                        text("1")
                    }
                    element("CtrlSum") {
                        text(amountWithoutCurrency)
                    }
                    element("PmtTpInf/SvcLvl/Cd") {
                        text("SDVA")
                    }
                    element("ReqdExctnDt") {
                        element("Dt") {
                            text(DateTimeFormatter.ISO_DATE.format(zonedTimestamp))
                        }
                    }
                    element("Dbtr/Nm") {
                        text(debtorName)
                    }
                    element("DbtrAcct/Id/IBAN") {
                        text(debitAccount.iban)
                    }
                    element("DbtrAgt/FinInstnId") {
                        element("BICFI") {
                            text(debtorBic)
                        }
                    }
                    element("ChrgBr") {
                        text("SLEV")
                    }
                    element("CdtTrfTxInf") {
                        element("PmtId") {
                            element("InstrId") { text("NOT PROVIDED") }
                            element("EndToEndId") { text("NOT PROVIDED") }
                        }
                        element("Amt/InstdAmt") {
                            attribute("Ccy", amount.currency)
                            text(amountWithoutCurrency)
                        }
                        creditAccount.bic.apply {
                            if (this != null)
                                element("CdtrAgt/FinInstnId") {
                                    element("BICFI") {
                                        text(this@apply)
                                    }
                                }
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