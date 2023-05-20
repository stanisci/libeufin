/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

/**
 * Parse and generate ISO 20022 messages
 */
package tech.libeufin.nexus.iso20022

import AgentIdentification
import Batch
import BatchTransaction
import CamtBankAccountEntry
import CashAccount
import CreditDebitIndicator
import CurrencyAmount
import CurrencyExchange
import EntryStatus
import GenericId
import OrganizationIdentification
import PartyIdentification
import PostalAddress
import PrivateIdentification
import ReturnInfo
import TransactionDetails
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import io.ktor.util.reflect.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.w3c.dom.Document
import tech.libeufin.nexus.*
import tech.libeufin.nexus.bankaccount.IngestedTransactionsCount
import tech.libeufin.nexus.bankaccount.findDuplicate
import tech.libeufin.nexus.server.EbicsDialects
import tech.libeufin.nexus.server.FetchLevel
import tech.libeufin.nexus.server.PaymentUidQualifiers
import tech.libeufin.util.*
import toPlainString
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter


enum class CashManagementResponseType(@get:JsonValue val jsonName: String) {
    Report("report"),
    Statement("statement"),
    Notification("notification")
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CamtReport(
    val id: String,
    val creationDateTime: String?,
    val legalSequenceNumber: Int?,
    val electronicSequenceNumber: Int?,
    val fromDate: String?,
    val toDate: String?,
    val reportingSource: String?,
    val proprietaryReportingSource: String?,
    val account: CashAccount,
    val balances: List<Balance>,
    val entries: List<CamtBankAccountEntry>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Balance(
    val type: String?,
    val subtype: String?,
    val proprietaryType: String?,
    val proprietarySubtype: String?,
    val date: String,
    val creditDebitIndicator: CreditDebitIndicator,
    val amount: CurrencyAmount
)

data class CamtParseResult(
    /**
     * Message type in form of the ISO 20022 message name.
     */
    val messageType: CashManagementResponseType,
    val messageId: String,
    val creationDateTime: String,
    /**
     * One Camt document can contain multiple reports/statements
     * for each account being owned by the requester.
     */
    val reports: List<CamtReport>
)

class CamtParsingError(msg: String) : Exception(msg)

/**
 * Data that the LibEuFin nexus uses for payment initiation.
 * Subset of what ISO 20022 allows.
 */
data class NexusPaymentInitiationData(
    val debtorIban: String,
    val debtorBic: String,
    val debtorName: String,
    val messageId: String,
    val paymentInformationId: String,
    val endToEndId: String? = null,
    val amount: String,
    val currency: String,
    val subject: String,
    val preparationTimestamp: Long,
    val creditorName: String,
    val creditorIban: String,
    val creditorBic: String? = null,
    val instructionId: String? = null
)

data class Pain001Namespaces(
    val fullNamespace: String,
    val xsdFilename: String
)

/**
 * Create a PAIN.001 XML document according to the input data.
 * Needs to be called within a transaction block.
 */
fun createPain001document(
    paymentData: NexusPaymentInitiationData,
    dialect: String? = null
): String {

    val namespace: Pain001Namespaces = if (dialect == "pf")
        Pain001Namespaces(
            fullNamespace = "http://www.six-interbank-clearing.com/de/pain.001.001.03.ch.02.xsd",
            xsdFilename = "pain.001.001.03.ch.02.xsd"
        )
    else Pain001Namespaces(
        fullNamespace = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.03",
        xsdFilename = "pain.001.001.03.xsd"
    )

    val paymentMethod = if (dialect == "pf")
        "SDVA" else "SEPA"

    val s = constructXml(indent = true) {
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
                        text(paymentData.messageId)
                    }
                    element("CreDtTm") {
                        val dateMillis = paymentData.preparationTimestamp
                        val dateFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        val instant = Instant.ofEpochSecond(dateMillis / 1000)
                        val zoned = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
                        text(dateFormatter.format(zoned))
                    }
                    element("NbOfTxs") {
                        text("1")
                    }
                    element("CtrlSum") {
                        text(paymentData.amount)
                    }
                    element("InitgPty/Nm") {
                        text(paymentData.debtorName)
                    }
                }
                element("PmtInf") {
                    element("PmtInfId") {
                        text(paymentData.paymentInformationId)
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
                        text(paymentData.amount)
                    }
                    element("PmtTpInf/SvcLvl/Cd") {
                        text(paymentMethod)
                    }
                    element("ReqdExctnDt") {
                        val dateMillis = paymentData.preparationTimestamp
                        text(importDateFromMillis(dateMillis).toDashedDate())
                    }
                    element("Dbtr/Nm") {
                        text(paymentData.debtorName)
                    }
                    element("DbtrAcct/Id/IBAN") {
                        text(paymentData.debtorIban)
                    }
                    element("DbtrAgt/FinInstnId/BIC") {
                        text(paymentData.debtorBic)
                    }
                    element("ChrgBr") {
                        text("SLEV")
                    }
                    element("CdtTrfTxInf") {
                        element("PmtId") {
                            paymentData.instructionId?.let {
                                element("InstrId") { text(it) }
                            }
                            when (val eeid = paymentData.endToEndId) {
                                null -> element("EndToEndId") { text("NOTPROVIDED") }
                                else -> element("EndToEndId") { text(eeid) }
                            }
                        }
                        element("Amt/InstdAmt") {
                            attribute("Ccy", paymentData.currency)
                            text(paymentData.amount)
                        }
                        val creditorBic = paymentData.creditorBic
                        if (creditorBic != null) {
                            element("CdtrAgt/FinInstnId/BIC") {
                                text(creditorBic)
                            }
                        }
                        element("Cdtr/Nm") {
                            text(paymentData.creditorName)
                        }
                        element("CdtrAcct/Id/IBAN") {
                            text(paymentData.creditorIban)
                        }
                        element("RmtInf/Ustrd") {
                            text(paymentData.subject)
                        }
                    }
                }
            }
        }
    }
    return s
}

private fun XmlElementDestructor.extractDateOrDateTime(): String {
    return requireOnlyChild {
        when (focusElement.localName) {
            "Dt" -> focusElement.textContent
            "DtTm" -> focusElement.textContent
            else -> throw Exception("Invalid date / time: ${focusElement.localName}")
        }
    }
}

private fun XmlElementDestructor.extractInnerPostalAddress(): PostalAddress {
    return PostalAddress(
        addressCode = maybeUniqueChildNamed("AdrTp") { maybeUniqueChildNamed("Cd") { focusElement.textContent } },
        addressProprietaryIssuer = maybeUniqueChildNamed("AdrTp") {
            maybeUniqueChildNamed("Prtry") {
                maybeUniqueChildNamed("Issr") { focusElement.textContent }
            }
        },
        addressProprietarySchemeName = maybeUniqueChildNamed("AdrTp") {
            maybeUniqueChildNamed("Prtry") {
                maybeUniqueChildNamed("SchmeNm") { focusElement.textContent }
            }
        },
        addressProprietaryId = maybeUniqueChildNamed("AdrTp") {
            maybeUniqueChildNamed("Prtry") {
                maybeUniqueChildNamed("Id") { focusElement.textContent }
            }
        },
        buildingName = maybeUniqueChildNamed("BldgNm") { focusElement.textContent },
        buildingNumber = maybeUniqueChildNamed("BldgNb") { focusElement.textContent },
        country = maybeUniqueChildNamed("Ctry") { focusElement.textContent },
        countrySubDivision = maybeUniqueChildNamed("CtrySubDvsn") { focusElement.textContent },
        department = maybeUniqueChildNamed("Dept") { focusElement.textContent },
        districtName = maybeUniqueChildNamed("DstrctNm") { focusElement.textContent },
        floor = maybeUniqueChildNamed("Flr") { focusElement.textContent },
        postBox = maybeUniqueChildNamed("PstBx") { focusElement.textContent },
        postCode = maybeUniqueChildNamed("PstCd") { focusElement.textContent },
        room = maybeUniqueChildNamed("Room") { focusElement.textContent },
        streetName = maybeUniqueChildNamed("StrtNm") { focusElement.textContent },
        subDepartment = maybeUniqueChildNamed("SubDept") { focusElement.textContent },
        townLocationName = maybeUniqueChildNamed("TwnLctnNm") { focusElement.textContent },
        townName = maybeUniqueChildNamed("TwnNm") { focusElement.textContent },
        addressLines = mapEachChildNamed("AdrLine") { focusElement.textContent }
    )
}

private fun XmlElementDestructor.extractAgent(): AgentIdentification {
    return AgentIdentification(
        name = maybeUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("Nm") { focusElement.textContent }
        },
        bic = requireUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("BIC") { focusElement.textContent }
        },
        lei = requireUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("LEI") { focusElement.textContent }
        },
        clearingSystemCode = requireUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("ClrSysMmbId") {
                maybeUniqueChildNamed("ClrSysId") {
                    maybeUniqueChildNamed("Cd") { focusElement.textContent }
                }
            }
        },
        proprietaryClearingSystemCode = requireUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("ClrSysMmbId") {
                maybeUniqueChildNamed("ClrSysId") {
                    maybeUniqueChildNamed("Prtry") { focusElement.textContent }
                }
            }
        },
        clearingSystemMemberId = requireUniqueChildNamed("FinInstnId") {
            maybeUniqueChildNamed("ClrSysMmbId") {
                maybeUniqueChildNamed("MmbId") { focusElement.textContent }
            }
        },
        otherId = requireUniqueChildNamed("FinInstnId") { maybeUniqueChildNamed("Othr") { extractGenericId() } },
        postalAddress = requireUniqueChildNamed("FinInstnId") { maybeUniqueChildNamed("PstlAdr") { extractInnerPostalAddress() } }
    )
}

private fun XmlElementDestructor.extractGenericId(): GenericId {
    return GenericId(
        id = requireUniqueChildNamed("Id") { focusElement.textContent },
        schemeName = maybeUniqueChildNamed("SchmeNm") {
            maybeUniqueChildNamed("Cd") { focusElement.textContent }
        },
        issuer = maybeUniqueChildNamed("Issr") { focusElement.textContent },
        proprietarySchemeName = maybeUniqueChildNamed("SchmeNm") {
            maybeUniqueChildNamed("Prtry") { focusElement.textContent }
        }
    )
}

private fun XmlElementDestructor.extractAccount(): CashAccount {
    var iban: String? = null
    var otherId: GenericId? = null
    val currency: String? = maybeUniqueChildNamed("Ccy") { focusElement.textContent }
    val name: String? = maybeUniqueChildNamed("Nm") { focusElement.textContent }
    requireUniqueChildNamed("Id") {
        requireOnlyChild {
            when (focusElement.localName) {
                "IBAN" -> {
                    iban = focusElement.textContent
                }
                "Othr" -> {
                    otherId = extractGenericId()
                }
                else -> throw Error("invalid account identification")
            }
        }
    }
    return CashAccount(name, currency, iban, otherId)
}

private fun XmlElementDestructor.extractParty(): PartyIdentification {
    val otherId: GenericId? = maybeUniqueChildNamed("Id") {
        (maybeUniqueChildNamed("PrvtId") { focusElement } ?: maybeUniqueChildNamed("OrgId") { focusElement })?.run {
            maybeUniqueChildNamed("Othr") {
                extractGenericId()
            }
        }
    }

    val privateId = maybeUniqueChildNamed("Id") {
        maybeUniqueChildNamed("PrvtId") {
            maybeUniqueChildNamed("DtAndPlcOfBirth") {
                PrivateIdentification(
                    birthDate = maybeUniqueChildNamed("BirthDt") { focusElement.textContent },
                    cityOfBirth = maybeUniqueChildNamed("CityOfBirth") { focusElement.textContent },
                    countryOfBirth = maybeUniqueChildNamed("CtryOfBirth") { focusElement.textContent },
                    provinceOfBirth = maybeUniqueChildNamed("PrvcOfBirth") { focusElement.textContent }
                )
            }
        }
    }

    val organizationId = maybeUniqueChildNamed("Id") {
        maybeUniqueChildNamed("OrgId") {
            OrganizationIdentification(
                bic = maybeUniqueChildNamed("BICOrBEI") { focusElement.textContent }
                    ?: maybeUniqueChildNamed("AnyBIC") { focusElement.textContent },
                lei = maybeUniqueChildNamed("LEI") { focusElement.textContent }
            )
        }
    }

    return PartyIdentification(
        name = maybeUniqueChildNamed("Nm") { focusElement.textContent },
        otherId = otherId,
        privateId = privateId,
        organizationId = organizationId,
        countryOfResidence = maybeUniqueChildNamed("CtryOfRes") { focusElement.textContent },
        postalAddress = maybeUniqueChildNamed("PstlAdr") { extractInnerPostalAddress() }
    )
}

private fun XmlElementDestructor.extractCurrencyAmount(): CurrencyAmount {
    return CurrencyAmount(
        value = requireUniqueChildNamed("Amt") { focusElement.textContent },
        currency = requireUniqueChildNamed("Amt") { focusElement.getAttribute("Ccy") }
    )
}

private fun XmlElementDestructor.maybeExtractCurrencyAmount(): CurrencyAmount? {
    return maybeUniqueChildNamed("Amt") {
        CurrencyAmount(
            focusElement.getAttribute("Ccy"),
            focusElement.textContent
        )
    }
}

private fun XmlElementDestructor.extractMaybeCurrencyExchange(): CurrencyExchange? {
    return maybeUniqueChildNamed("CcyXchg") {
        CurrencyExchange(
            sourceCurrency = requireUniqueChildNamed("SrcCcy") { focusElement.textContent },
            targetCurrency = requireUniqueChildNamed("TrgtCcy") { focusElement.textContent },
            contractId = maybeUniqueChildNamed("CtrctId") { focusElement.textContent },
            exchangeRate = requireUniqueChildNamed("XchgRate") { focusElement.textContent },
            quotationDate = maybeUniqueChildNamed("QtnDt") { focusElement.textContent },
            unitCurrency = maybeUniqueChildNamed("UnitCcy") { focusElement.textContent }
        )
    }
}

private fun XmlElementDestructor.extractBatches(
    inheritableAmount: CurrencyAmount,
    outerCreditDebitIndicator: CreditDebitIndicator,
    acctSvcrRef: String
): List<Batch> {
    if (mapEachChildNamed("NtryDtls") {}.size != 1) throw CamtParsingError(
        "This money movement (AcctSvcrRef: $acctSvcrRef) is not a singleton #0"
    )
    val txs = requireUniqueChildNamed("NtryDtls") {
        if (mapEachChildNamed("TxDtls") {}.size != 1) {
            throw CamtParsingError("This money movement (AcctSvcrRef: $acctSvcrRef) is not a singleton #1")
        }
         requireUniqueChildNamed("TxDtls") {
            val details = extractTransactionDetails(outerCreditDebitIndicator)
            mutableListOf(
                BatchTransaction(
                    inheritableAmount,
                    outerCreditDebitIndicator,
                    details
                )
            )
        }
    }
    return mutableListOf(
        Batch(messageId = null, paymentInformationId = null, batchTransactions = txs)
    )
}

private fun XmlElementDestructor.maybeExtractCreditDebitIndicator(): CreditDebitIndicator? {
    return maybeUniqueChildNamed("CdtDbtInd") { focusElement.textContent }?.let {
        CreditDebitIndicator.valueOf(it)
    }
}

private fun XmlElementDestructor.extractTransactionDetails(
    outerCreditDebitIndicator: CreditDebitIndicator
): TransactionDetails {
    val instructedAmount = maybeUniqueChildNamed("AmtDtls") {
        maybeUniqueChildNamed("InstdAmt") { extractCurrencyAmount() }
    }

    val creditDebitIndicator = maybeExtractCreditDebitIndicator() ?: outerCreditDebitIndicator
    val currencyExchange = maybeUniqueChildNamed("AmtDtls") {
        val cxCntrVal = maybeUniqueChildNamed("CntrValAmt") { extractMaybeCurrencyExchange() }
        val cxTx = maybeUniqueChildNamed("TxAmt") { extractMaybeCurrencyExchange() }
        val cxInstr = maybeUniqueChildNamed("InstdAmt") { extractMaybeCurrencyExchange() }
        cxCntrVal ?: cxTx ?: cxInstr
    }

    return TransactionDetails(
        instructedAmount = instructedAmount,
        counterValueAmount = maybeUniqueChildNamed("AmtDtls") {
            maybeUniqueChildNamed("CntrValAmt") { extractCurrencyAmount() }
        },
        currencyExchange = currencyExchange,
        interBankSettlementAmount = null,
        endToEndId = maybeUniqueChildNamed("Refs") {
            maybeUniqueChildNamed("EndToEndId") { focusElement.textContent }
        },
        paymentInformationId = maybeUniqueChildNamed("Refs") {
            maybeUniqueChildNamed("PmtInfId") { focusElement.textContent }
        },
        accountServicerRef = maybeUniqueChildNamed("Refs") {
            maybeUniqueChildNamed("AcctSvcrRef") { focusElement.textContent }
        },
        unstructuredRemittanceInformation = maybeUniqueChildNamed("RmtInf") {
            val chunks = mapEachChildNamed("Ustrd") { focusElement.textContent }
            if (chunks.isEmpty()) {
                null
            } else {
                chunks.joinToString(separator = "")
            }
        },
        creditorAgent = maybeUniqueChildNamed("RltdAgts") { maybeUniqueChildNamed("CdtrAgt") { extractAgent() } },
        debtorAgent = maybeUniqueChildNamed("RltdAgts") { maybeUniqueChildNamed("DbtrAgt") { extractAgent() } },
        debtorAccount = maybeUniqueChildNamed("RltdPties") { maybeUniqueChildNamed("DbtrAcct") { extractAccount() } },
        creditorAccount = maybeUniqueChildNamed("RltdPties") { maybeUniqueChildNamed("CdtrAcct") { extractAccount() } },
        debtor = maybeUniqueChildNamed("RltdPties") { maybeUniqueChildNamed("Dbtr") { extractParty() } },
        creditor = maybeUniqueChildNamed("RltdPties") { maybeUniqueChildNamed("Cdtr") { extractParty() } },
        proprietaryPurpose = maybeUniqueChildNamed("Purp") { maybeUniqueChildNamed("Prtry") { focusElement.textContent } },
        purpose = maybeUniqueChildNamed("Purp") { maybeUniqueChildNamed("Cd") { focusElement.textContent } },
        ultimateCreditor = maybeUniqueChildNamed("RltdPties") { maybeUniqueChildNamed("UltmtCdtr") { extractParty() } },
        ultimateDebtor = maybeUniqueChildNamed("RltdPties") { maybeUniqueChildNamed("UltmtDbtr") { extractParty() } },
        returnInfo = maybeUniqueChildNamed("RtrInf") {
            ReturnInfo(
                originalBankTransactionCode = maybeUniqueChildNamed("OrgnlBkTxCd") {
                    extractInnerBkTxCd(
                        when (creditDebitIndicator) {
                            CreditDebitIndicator.DBIT -> CreditDebitIndicator.CRDT
                            CreditDebitIndicator.CRDT -> CreditDebitIndicator.DBIT
                        }
                    )
                },
                originator = maybeUniqueChildNamed("Orgtr") { extractParty() },
                reason = maybeUniqueChildNamed("Rsn") { maybeUniqueChildNamed("Cd") { focusElement.textContent } },
                proprietaryReason = maybeUniqueChildNamed("Rsn") { maybeUniqueChildNamed("Prtry") { focusElement.textContent } },
                additionalInfo = maybeUniqueChildNamed("AddtlInf") { focusElement.textContent }
            )
        }
    )
}

private fun XmlElementDestructor.extractInnerBkTxCd(creditDebitIndicator: CreditDebitIndicator): String {

    val domain = maybeUniqueChildNamed("Domn") { maybeUniqueChildNamed("Cd") { focusElement.textContent } }
    val family = maybeUniqueChildNamed("Domn") {
        maybeUniqueChildNamed("Fmly") {
            maybeUniqueChildNamed("Cd") { focusElement.textContent }
        }
    }
    val subfamily = maybeUniqueChildNamed("Domn") {
        maybeUniqueChildNamed("Fmly") {
            maybeUniqueChildNamed("SubFmlyCd") { focusElement.textContent }
        }
    }
    val proprietaryCode = maybeUniqueChildNamed("Prtry") {
        maybeUniqueChildNamed("Cd") { focusElement.textContent }
    }
    val proprietaryIssuer = maybeUniqueChildNamed("Prtry") {
        maybeUniqueChildNamed("Issr") { focusElement.textContent }
    }

    if (domain != null && family != null && subfamily != null) {
        return "$domain-$family-$subfamily"
    }
    if (proprietaryIssuer == "DK" && proprietaryCode != null) {
        val components = proprietaryCode.split("+")
        if (components.size == 1) {
            return GbicRules.getBtcFromGvc(creditDebitIndicator, components[0])
        } else {
            return GbicRules.getBtcFromGvc(creditDebitIndicator, components[1])
        }
    }
    // FIXME: log/raise this somewhere?
    return "XTND-NTAV-NTAV"
}

private fun XmlElementDestructor.extractInnerTransactions(dialect: String? = null): CamtReport {
    val account = requireUniqueChildNamed("Acct") { extractAccount() }

    val balances = mapEachChildNamed("Bal") {
        Balance(
            type = maybeUniqueChildNamed("Tp") {
                maybeUniqueChildNamed("CdOrPrtry") {
                    maybeUniqueChildNamed("Cd") { focusElement.textContent }
                }
            },
            proprietaryType = maybeUniqueChildNamed("Tp") {
                maybeUniqueChildNamed("CdOrPrtry") {
                    maybeUniqueChildNamed("Prtry") { focusElement.textContent }
                }
            },
            date = requireUniqueChildNamed("Dt") { extractDateOrDateTime() },
            creditDebitIndicator = requireUniqueChildNamed("CdtDbtInd") { focusElement.textContent }.let {
                CreditDebitIndicator.valueOf(it)
            },
            subtype = maybeUniqueChildNamed("Tp") {
                maybeUniqueChildNamed("SubTp") { maybeUniqueChildNamed("Cd") { focusElement.textContent } }
            },
            proprietarySubtype = maybeUniqueChildNamed("Tp") {
                maybeUniqueChildNamed("SubTp") { maybeUniqueChildNamed("Prtry") { focusElement.textContent } }
            },
            amount = extractCurrencyAmount()
        )
    }
    // Note: multiple Ntry's *are* allowed.  What is not allowed is
    // multiple money transactions *within* one Ntry element.
    val entries = mapEachChildNamed("Ntry") {
        val amount = extractCurrencyAmount()
        val status = requireUniqueChildNamed("Sts") {
            val textContent = if (dialect == EbicsDialects.POSTFINANCE.dialectName) {
                requireUniqueChildNamed("Cd") {
                    focusElement.textContent
                }
            } else
                focusElement.textContent
            textContent.let {
                EntryStatus.valueOf(it)
            }
        }
        val creditDebitIndicator = requireUniqueChildNamed("CdtDbtInd") { focusElement.textContent }.let {
            CreditDebitIndicator.valueOf(it)
        }
        val btc = requireUniqueChildNamed("BkTxCd") {
            extractInnerBkTxCd(creditDebitIndicator)
        }
        val acctSvcrRef = maybeUniqueChildNamed("AcctSvcrRef") { focusElement.textContent }
        val entryRef = maybeUniqueChildNamed("NtryRef") { focusElement.textContent }

        val currencyExchange = maybeUniqueChildNamed("AmtDtls") {
            val cxCntrVal = maybeUniqueChildNamed("CntrValAmt") { extractMaybeCurrencyExchange() }
            val cxTx = maybeUniqueChildNamed("TxAmt") { extractMaybeCurrencyExchange() }
            val cxInstr = maybeUniqueChildNamed("InstrAmt") { extractMaybeCurrencyExchange() }
            cxCntrVal ?: cxTx ?: cxInstr
        }

        val counterValueAmount = maybeUniqueChildNamed("AmtDtls") {
            maybeUniqueChildNamed("CntrValAmt") { extractCurrencyAmount() }
        }

        val instructedAmount = maybeUniqueChildNamed("AmtDtls") {
            maybeUniqueChildNamed("InstdAmt") { extractCurrencyAmount() }
        }

        CamtBankAccountEntry(
            amount = amount,
            status = status,
            currencyExchange = currencyExchange,
            counterValueAmount = counterValueAmount,
            instructedAmount = instructedAmount,
            creditDebitIndicator = creditDebitIndicator,
            bankTransactionCode = btc,
            batches = extractBatches(
                amount,
                creditDebitIndicator,
                acctSvcrRef ?: "AcctSvcrRef not given/found"),
            bookingDate = maybeUniqueChildNamed("BookgDt") { extractDateOrDateTime() },
            valueDate = maybeUniqueChildNamed("ValDt") { extractDateOrDateTime() },
            accountServicerRef = acctSvcrRef,
            entryRef = entryRef
        )
    }
    return CamtReport(
        account = account,
        entries = entries,
        creationDateTime = maybeUniqueChildNamed("CreDtTm") { focusElement.textContent },
        balances = balances,
        electronicSequenceNumber = maybeUniqueChildNamed("ElctrncSeqNb") { focusElement.textContent.toInt() },
        legalSequenceNumber = maybeUniqueChildNamed("LglSeqNb") { focusElement.textContent.toInt() },
        fromDate = maybeUniqueChildNamed("FrToDt") { maybeUniqueChildNamed("FrDtTm") { focusElement.textContent } },
        toDate = maybeUniqueChildNamed("FrToDt") { maybeUniqueChildNamed("ToDtTm") { focusElement.textContent } },
        id = requireUniqueChildNamed("Id") { focusElement.textContent },
        proprietaryReportingSource = maybeUniqueChildNamed("RptgSrc") { maybeUniqueChildNamed("Prtry") { focusElement.textContent } },
        reportingSource = maybeUniqueChildNamed("RptgSrc") { maybeUniqueChildNamed("Cd") { focusElement.textContent } }
    )
}

/**
 * Extract a list of transactions from
 * an ISO20022 camt.052 / camt.053 message.
 */
fun parseCamtMessage(doc: Document, dialect: String? = null): CamtParseResult {
    return destructXml(doc) {
        requireRootElement("Document") {
            // Either bank to customer statement or report
            val reports = requireOnlyChild {
                when (focusElement.localName) {
                    "BkToCstmrAcctRpt" -> {
                        mapEachChildNamed("Rpt") {
                            extractInnerTransactions(dialect)
                        }
                    }
                    "BkToCstmrStmt" -> {
                        mapEachChildNamed("Stmt") {
                            extractInnerTransactions(dialect)
                        }
                    }
                    "BkToCstmrDbtCdtNtfctn" -> {
                        mapEachChildNamed("Ntfctn") {
                            extractInnerTransactions(dialect)
                        }
                    }
                    else -> {
                        throw CamtParsingError("expected statement or report")
                    }
                }
            }
            val messageId = requireOnlyChild {
                requireUniqueChildNamed("GrpHdr") {
                    requireUniqueChildNamed("MsgId") { focusElement.textContent }
                }
            }
            val creationDateTime = requireOnlyChild {
                requireUniqueChildNamed("GrpHdr") {
                    requireUniqueChildNamed("CreDtTm") { focusElement.textContent }
                }
            }
            val messageType = requireOnlyChild {
                when (focusElement.localName) {
                    "BkToCstmrAcctRpt" -> CashManagementResponseType.Report
                    "BkToCstmrStmt" -> CashManagementResponseType.Statement
                    "BkToCstmrDbtCdtNtfctn" -> CashManagementResponseType.Notification
                    else -> {
                        throw CamtParsingError("expected statement or report")
                    }
                }
            }
            CamtParseResult(
                reports = reports,
                messageId = messageId,
                messageType = messageType,
                creationDateTime = creationDateTime
            )
        }
    }
}

// Get timestamp in milliseconds, according to the EBICS+camt dialect.
fun getTimestampInMillis(
    dateTimeFromCamt: String,
    dialect: String? = null
): Long {
    return when(dialect) {
        EbicsDialects.POSTFINANCE.dialectName -> {
            val withoutTimezone = LocalDateTime.parse(
                dateTimeFromCamt,
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
            )
            ZonedDateTime.of(
                withoutTimezone,
                ZoneId.of("Europe/Zurich")).toInstant().toEpochMilli()
        }
        else -> {
            ZonedDateTime.parse(
                dateTimeFromCamt,
                DateTimeFormatter.ISO_DATE_TIME
            ).toInstant().toEpochMilli()
        }
    }
}

/**
 * Extracts the UID from the payment, according to dialect
 * and direction.  It returns the _qualified_ string from such
 * ID.  A qualified string has the format "$qualifier:$extracted_id".
 * $qualifier is a constant that gives more context about the
 * actual $extracted_id;  for example, it may indicate that the
 * ID was assigned by the bank, or by Nexus when it uploaded
 * the payment initiation in the first place.
 *
 * NOTE: this version _still_ expect only singleton transactions
 * in the input.  That means _only one_ element is expected at the
 * lowest level of the camt.05x report.  This may/should change in
 * future versions.
 */
fun extractPaymentUidFromSingleton(
    ntry: CamtBankAccountEntry,
    camtMessageId: String, // used to print errors.
    dialect: String?
    ): String {
    // First check if the input is a singleton.
    val batchTransactions: List<BatchTransaction>? = ntry.batches?.get(0)?.batchTransactions
    val tx: BatchTransaction = if (ntry.batches?.size != 1 || batchTransactions?.size != 1) {
        logger.error("camt message ${camtMessageId} has non singleton transactions.")
        throw internalServerError("Dialect $dialect sent camt with non singleton transactions.")
    } else
        batchTransactions[0]

    when(dialect) {
        EbicsDialects.POSTFINANCE.dialectName -> {
            if (tx.creditDebitIndicator == CreditDebitIndicator.DBIT) {
                val expectedEndToEndId = tx.details.endToEndId
                /**
                 * Because this is an outgoing transaction, and because
                 * Nexus should have included the EndToEndId in the original
                 * pain.001, this transaction must have it (recall: EndToEndId
                 * is mandatory in the pain.001).  A null value means therefore
                 * that the payment was done via another mean than pain.001.
                 */
                if (expectedEndToEndId == null) {
                    logger.error("Camt '$camtMessageId' shows outgoing payment _without_ the EndToEndId." +
                            "  This likely wasn't initiated via pain.001"
                    )
                    throw internalServerError("Internal reconciliation error (no EndToEndId)")
                }
                return "${PaymentUidQualifiers.USER_GIVEN}:$expectedEndToEndId"
            }
            // Didn't return/throw before, it must be an incoming payment.
            val maybeAcctSvcrRef = tx.details.accountServicerRef
            // Expecting this value to be at the lowest level, as observed on the test platform.
            val expectedAcctSvcrRef = tx.details.accountServicerRef
            if (expectedAcctSvcrRef == null) {
                logger.error("AcctSvcrRef was expected at the lowest tx level for dialect: $dialect, but wasn't found")
                throw internalServerError("Internal reconciliation error (no AcctSvcrRef at lowest tx level)")
            }
            return "${PaymentUidQualifiers.BANK_GIVEN}:$expectedAcctSvcrRef"
        }
        // This is the default dialect, the one tested with GLS.
        null -> {
            /**
             * This dialect has shown the AcctSvcrRef to be always given
             * at the level that _contains_ the (singleton) transaction(s).
             * This occurs _regardless_ of the payment direction.
             */
            val expectedAcctSvcrRef = ntry.accountServicerRef
            if (expectedAcctSvcrRef == null) {
                logger.error("AcctSvcrRef was expected at the outer tx level for dialect: GLS, but wasn't found.")
                throw internalServerError("Internal reconciliation error: AcctSvcrRef not found at outer level.")
            }
            return "${PaymentUidQualifiers.BANK_GIVEN}:$expectedAcctSvcrRef"
        }
        else -> throw internalServerError("Dialect $dialect is not supported.")
    }
}

/**
 * Given that every CaMt is a collection of reports/statements
 * where each of them carries the bank account balance and a list
 * of transactions, this function:
 *
 * - extracts the balance (storing a NexusBankBalanceEntity)
 * - updates timestamps in NexusBankAccountEntity to the last seen
 *   report/statement.
 * - finds which transactions were already downloaded.
 * - stores a new NexusBankTransactionEntity for each new tx
 *   accounted in the report/statement.
 * - tries to link the new transaction with a submitted one, in
 *   case of DBIT transaction.
 * - returns a IngestedTransactionCount object.
 */
fun ingestCamtMessageIntoAccount(
    bankAccountId: String,
    camtDoc: Document,
    fetchLevel: FetchLevel,
    dialect: String? = null
): IngestedTransactionsCount {
    /**
     * Ensure that the level is not ALL, as the parser expects
     * the exact type for the one message being parsed.
     */
    if (fetchLevel == FetchLevel.ALL)
        throw internalServerError("Parser needs exact camt type (ALL not permitted).")

    var newTransactions = 0
    var downloadedTransactions = 0
    transaction {
        val acct = NexusBankAccountEntity.findByName(bankAccountId)
        if (acct == null) {
            throw NexusError(HttpStatusCode.NotFound, "user not found")
        }
        val res = try { parseCamtMessage(camtDoc, dialect) } catch (e: CamtParsingError) {
            logger.warn("Invalid CAMT received from bank: ${e.message}")
            newTransactions = -1
            return@transaction
        }
        res.reports.forEach {
            NexusAssert(
                it.account.iban == acct.iban,
                "Nexus hit a report or statement of a wrong IBAN!"
            )
            it.balances.forEach { b ->
                if (b.type == "CLBD") {
                    val lastBalance = NexusBankBalanceEntity.all().lastOrNull()
                    /**
                     * Store balances different from the one that came from the bank,
                     * or the very first balance.  This approach has the following inconvenience:
                     * the 'balance' held at Nexus does not differentiate between one
                     * coming from a statement and one coming from a report.  As a consequence,
                     * the two types of balances may override each other without notice.
                     */
                    if ((lastBalance == null) ||
                        (b.amount.toPlainString() != lastBalance.balance)) {
                        NexusBankBalanceEntity.new {
                            bankAccount = acct
                            balance = b.amount.toPlainString()
                            creditDebitIndicator = b.creditDebitIndicator.name
                            date = b.date
                        }
                    }
                }
            }
        }
        // Updating the local bank account state timestamps according to the current document.
        val stamp = getTimestampInMillis(res.creationDateTime, dialect = dialect)
        when (fetchLevel) {
            FetchLevel.REPORT -> {
                val s = acct.lastReportCreationTimestamp
                if (s == null || stamp > s) {
                    acct.lastReportCreationTimestamp = stamp
                }
            }
            FetchLevel.STATEMENT -> {
                val s = acct.lastStatementCreationTimestamp
                if (s == null || stamp > s) {
                    acct.lastStatementCreationTimestamp = stamp
                }
            }
            FetchLevel.NOTIFICATION -> {
                val s = acct.lastNotificationCreationTimestamp
                if (s == null || stamp > s) {
                    acct.lastNotificationCreationTimestamp = stamp
                }
            }
            // Silencing the compiler: the 'ALL' case was checked at the top of this function.
            else -> {}
        }
        val entries: List<CamtBankAccountEntry> = res.reports.map { it.entries }.flatten()
        var newPaymentsLog = ""
        downloadedTransactions = entries.size
        txloop@ for (entry: CamtBankAccountEntry in entries) {
            val singletonBatchedTransaction: BatchTransaction = entry.batches?.get(0)?.batchTransactions?.get(0)
                ?: throw NexusError(
                    HttpStatusCode.InternalServerError,
                    "Singleton money movements policy wasn't respected"
                )
            if (entry.status != EntryStatus.BOOK) {
                logger.info("camt message '${res.messageId}' has a " +
                        "non-BOOK transaction, ignoring it."
                )
                continue
            }
            val paymentUid = extractPaymentUidFromSingleton(
                ntry = entry,
                camtMessageId = res.messageId,
                dialect = dialect
            )
            val duplicate = findDuplicate(bankAccountId, paymentUid)
            if (duplicate != null) {
                logger.info("Found a duplicate, UID is $paymentUid")
                // https://bugs.gnunet.org/view.php?id=6381
                continue@txloop
            }
            val rawEntity = NexusBankTransactionEntity.new {
                bankAccount = acct
                accountTransactionId = paymentUid
                amount = singletonBatchedTransaction.amount.value
                currency = singletonBatchedTransaction.amount.currency
                transactionJson = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(entry)
                creditDebitIndicator = singletonBatchedTransaction.creditDebitIndicator.name
                status = entry.status
            }
            rawEntity.flush()
            newTransactions++
            newPaymentsLog += "\n- ${entry.getSingletonSubject()}"

            // This block tries to acknowledge a former outgoing payment as booked.
            if (singletonBatchedTransaction.creditDebitIndicator == CreditDebitIndicator.DBIT) {
                val t0 = singletonBatchedTransaction.details
                val endToEndId = t0.endToEndId
                if (endToEndId != null) {
                    logger.debug("Reconciling outgoing payment with EndToEndId: $endToEndId")
                    val paymentInitiation = PaymentInitiationEntity.find {
                        PaymentInitiationsTable.bankAccount eq acct.id and (
                                // pmtInfId is a value that the payment submitter
                                // asked the bank to associate with the payment to be made.
                                PaymentInitiationsTable.endToEndId eq endToEndId)

                    }.firstOrNull()
                    if (paymentInitiation != null) {
                        logger.info("Could confirm one initiated payment: $endToEndId")
                        paymentInitiation.confirmationTransaction = rawEntity
                    }
                }
                // Every payment initiated by Nexus has EndToEndId.  Warn if not found.
                else
                    logger.warn("Camt ${res.messageId} has outgoing payment without EndToEndId..")
            }
        }
        if (newTransactions > 0)
            logger.debug("Camt $fetchLevel '${res.messageId}' has new payments:${newPaymentsLog}")
    }

    return IngestedTransactionsCount(
        newTransactions = newTransactions,
        downloadedTransactions = downloadedTransactions
    )
}