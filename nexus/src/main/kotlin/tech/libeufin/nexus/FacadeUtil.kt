package tech.libeufin.nexus

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.iso20022.CamtBankAccountEntry
import tech.libeufin.nexus.iso20022.CreditDebitIndicator
import tech.libeufin.nexus.iso20022.EntryStatus
import tech.libeufin.nexus.iso20022.TransactionDetails


/**
 * Mainly used to resort the last processed transaction ID.
 */
fun getFacadeState(fcid: String): FacadeStateEntity {
    val facade = FacadeEntity.find { FacadesTable.facadeName eq fcid }.firstOrNull() ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Could not find facade '${fcid}'"
    )
    return FacadeStateEntity.find {
        FacadeStateTable.facade eq facade.id.value
    }.firstOrNull() ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Could not find any state for facade: $fcid"
    )
}

fun getFacadeBankAccount(fcid: String): NexusBankAccountEntity {
    val facadeState = getFacadeState(fcid)
    return NexusBankAccountEntity.findByName(facadeState.bankAccount) ?: throw NexusError(
        HttpStatusCode.NotFound,
        "The facade: $fcid doesn't manage bank account: ${facadeState.bankAccount}"
    )
}

/**
 * Ingests transactions for those facades accounting for bankAccountId.
 */
fun ingestFacadeTransactions(
    bankAccountId: String,
    facadeType: String,
    incomingFilterCb: ((NexusBankTransactionEntity, TransactionDetails) -> Unit)?,
    refundCb: ((NexusBankAccountEntity, Long) -> Unit)?
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
                    (NexusBankTransactionsTable.status eq EntryStatus.BOOK) and
                    /** Those that came later than the latest processed payment */
                    (NexusBankTransactionsTable.id.greater(lastId))
        }.orderBy(Pair(NexusBankTransactionsTable.id, SortOrder.ASC)).forEach {
            // Incoming payment.
            logger.debug("Facade checks payment: ${it.transactionJson}")
            val tx = jacksonObjectMapper().readValue(
                it.transactionJson, CamtBankAccountEntry::class.java
            )
            val details = tx.batches?.get(0)?.batchTransactions?.get(0)?.details
            if (details == null) {
                logger.warn("A void money movement made it through the ingestion: VERY strange")
                return@forEach
            }
            when (tx.creditDebitIndicator) {
                CreditDebitIndicator.CRDT -> {
                    if (incomingFilterCb != null) {
                        incomingFilterCb(it, details)
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
            logger.warn("sending refund payment failed", e)
        }
        facadeState.highestSeenMessageSerialId = lastId
    }
    // invoke ingestion for all the facades
    transaction {
        FacadeEntity.find { FacadesTable.type eq facadeType }.forEach {
            val facadeBankAccount = getFacadeBankAccount(it.facadeName)
            if (facadeBankAccount.bankAccountName == bankAccountId)
                ingest(facadeBankAccount, it)
        }
    }
}