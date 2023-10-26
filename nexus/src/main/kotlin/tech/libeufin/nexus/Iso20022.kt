package tech.libeufin.nexus

import tech.libeufin.util.IbanPayto
import tech.libeufin.util.constructXml
import tech.libeufin.util.parsePayto
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
 * @param debtorMetadataFile bank account information of the EBICS subscriber that
 *                           sends this request.
 * @param amount amount to pay.
 * @param wireTransferSubject wire transfer subject.
 * @param creditAccount payment receiver.
 * @return raw pain.001 XML, or throws if the debtor BIC is not found.
 */
fun createPain001(
    requestUid: String,
    debtorMetadataFile: BankAccountMetadataFile,
    amount: TalerAmount,
    wireTransferSubject: String,
    creditAccount: IbanPayto
): String {
    val namespace = Pain001Namespaces(
        fullNamespace = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09",
        xsdFilename = "pain.001.001.09.ch.03.xsd"
    )
    val creationTimestamp = Instant.now()
    val amountWithoutCurrency: String = amount.toString().split(":").run {
        if (this.size != 2) throw Exception("Invalid stringified amount: $amount")
        return@run this[1]
    }
    if (debtorMetadataFile.bank_code == null)
        throw Exception("Need debtor BIC, but not found in the debtor account metadata file.")
    // Current version expects the receiver BIC, TODO: try also without.
    if (creditAccount.bic == null)
        throw Exception("Expecting the receiver BIC.")

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
                        val zoned = ZonedDateTime.ofInstant(
                            creationTimestamp,
                            ZoneId.systemDefault() // FIXME: should this be UTC?
                        )
                        text(dateFormatter.format(zoned))
                    }
                    element("NbOfTxs") {
                        text("1")
                    }
                    element("CtrlSum") {
                        text(amount.toString())
                    }
                    element("InitgPty/Nm") { // optional
                        text(debtorMetadataFile.account_holder_name)
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
                        text(amount.toString())
                    }
                    element("PmtTpInf/SvcLvl/Cd") {
                        text("SDVA")
                    }
                    element("ReqdExctnDt") {
                        text(DateTimeFormatter.ISO_DATE.format(creationTimestamp))
                    }
                    element("Dbtr/Nm") { // optional
                        text(debtorMetadataFile.account_holder_name)
                    }
                    element("DbtrAcct/Id/IBAN") {
                        text(debtorMetadataFile.account_holder_iban)
                    }
                    element("DbtrAgt/FinInstnId") {
                        element("BICFI") {
                            text(debtorMetadataFile.bank_code)
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
                        creditAccount.receiverName.apply {
                            if (this != null)
                                element("Cdtr/Nm") {
                                    text(this@apply)
                                }
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