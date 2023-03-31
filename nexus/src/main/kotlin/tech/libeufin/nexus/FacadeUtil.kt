package tech.libeufin.nexus

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.iso20022.CamtBankAccountEntry
import tech.libeufin.nexus.iso20022.CreditDebitIndicator
import tech.libeufin.nexus.iso20022.TransactionDetails
import tech.libeufin.nexus.server.NexusFacadeType

// Mainly used to resort the last processed transaction ID.
fun getFacadeState(fcid: String): FacadeStateEntity {
    return transaction {
        val facade = FacadeEntity.find {
            FacadesTable.facadeName eq fcid
        }.firstOrNull() ?: throw NexusError(
            HttpStatusCode.NotFound,
            "Could not find facade '${fcid}'"
        )
        FacadeStateEntity.find {
            FacadeStateTable.facade eq facade.id.value
        }.firstOrNull() ?: throw NexusError(
            HttpStatusCode.NotFound,
            "Could not find any state for facade: $fcid"
        )
    }
}

fun getFacadeBankAccount(fcid: String): NexusBankAccountEntity {
    return transaction {
        val facadeState = getFacadeState(fcid)
        NexusBankAccountEntity.findByName(facadeState.bankAccount) ?: throw NexusError(
            HttpStatusCode.NotFound,
            "The facade: $fcid doesn't manage bank account: ${facadeState.bankAccount}"
        )
    }
}

/**
 * Ingests transactions for those facades accounting for bankAccountId.
 * 'incomingFilterCb' decides whether the facade accepts the payment;
 * if not, refundCb prepares a refund.  The 'txStatus' parameter decides
 * at which state one transaction deserve to fuel Taler transactions. BOOK
 * is conservative, and with some banks the delay can be significant.  PNDG
 * instead reacts faster, but risks that one transaction gets undone by the
 * bank and never reach the BOOK state; this would mean a loss and/or admin
 * burden.
 */
fun ingestFacadeTransactions(
    bankAccountId: String,
    facadeType: NexusFacadeType,
    incomingFilterCb: ((NexusBankTransactionEntity, TransactionDetails) -> Unit)?,
    refundCb: ((NexusBankAccountEntity, Long) -> Unit)?,
    txStatus: EntryStatus = EntryStatus.BOOK
) {
    fun ingest(bankAccount: NexusBankAccountEntity, facade: FacadeEntity) {
        logger.debug(
            "Ingesting transactions for Taler facade ${facade.id.value}," +
                    " and bank account: ${bankAccount.bankAccountName}"
        )
        val facadeState = getFacadeState(facade.facadeName)
        var lastId = facadeState.highestSeenMessageSerialId
        NexusBankTransactionEntity.find {
            /** Those with "our" bank account involved */
            NexusBankTransactionsTable.bankAccount eq bankAccount.id.value and
                    /** Those that are booked */
                    (NexusBankTransactionsTable.status eq txStatus) and
                    /** Those that came later than the latest processed payment */
                    (NexusBankTransactionsTable.id.greater(lastId))
        }.orderBy(Pair(NexusBankTransactionsTable.id, SortOrder.ASC)).forEach {
            // Incoming payment.
            val tx = jacksonObjectMapper().readValue(
                it.transactionJson,
                CamtBankAccountEntry::class.java
            )
            /**
             * Need transformer from "JSON tx" to TransactionDetails?.
             */
            val details: TransactionDetails? = tx.batches?.get(0)?.batchTransactions?.get(0)?.details
            if (details == null) {
                logger.warn("A void money movement (${tx.accountServicerRef}) made it through the ingestion: VERY strange")
                return@forEach
            }
            when (tx.creditDebitIndicator) {
                CreditDebitIndicator.CRDT -> {
                    if (incomingFilterCb != null) {
                        incomingFilterCb(
                            it, // payment DB object
                            details // wire transfer details
                        )
                    }
                }
                else -> Unit
            }
            lastId = it.id.value
        }
        try {
            if (refundCb != null) {
                refundCb(
                    bankAccount,
                    facadeState.highestSeenMessageSerialId
                )
            }
        } catch (e: Exception) {
            logger.warn("Sending refund payment failed: ${e.message}")
        }
        facadeState.highestSeenMessageSerialId = lastId
    }
    // invoke ingestion for all the facades
    transaction {
        FacadeEntity.find { FacadesTable.type eq facadeType.facadeType }.forEach {
            val facadeBankAccount = getFacadeBankAccount(it.facadeName)
            if (facadeBankAccount.bankAccountName == bankAccountId)
                ingest(facadeBankAccount, it)
            flushCache()
        }
    }
}