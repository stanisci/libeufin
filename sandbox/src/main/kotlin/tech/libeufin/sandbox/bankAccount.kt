package tech.libeufin.sandbox

import io.ktor.http.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.RawPayment
import tech.libeufin.util.importDateFromMillis
import tech.libeufin.util.parseDecimal
import tech.libeufin.util.toDashedDate
import java.math.BigDecimal

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")

fun getAccountFromLabel(accountLabel: String): BankAccountEntity {
    return transaction {
        val account = BankAccountEntity.find {
            BankAccountsTable.label eq accountLabel
        }.firstOrNull()
        if (account == null) throw SandboxError(
            HttpStatusCode.NotFound, "Account '$accountLabel' not found"
        )
        account
    }
}
// Mainly useful inside the CAMT generator.
fun balanceForAccount(
    history: MutableList<RawPayment>,
    baseBalance: BigDecimal
): BigDecimal {
    var ret = baseBalance
    history.forEach direction@ {
        if (it.direction == "CRDT") {
            val amount = parseDecimal(it.amount)
            ret += amount
            return@direction
        }
        if (it.direction == "DBIT") {
            val amount = parseDecimal(it.amount)
            ret -= amount
            return@direction
        }
        throw SandboxError(
            HttpStatusCode.InternalServerError,
            "A payment direction was found neither CRDT not DBIT"
        )
    }
    return ret
}

fun balanceForAccount(bankAccount: BankAccountEntity): BigDecimal {
    var balance = BigDecimal.ZERO
    transaction {
        BankAccountTransactionEntity.find {
            BankAccountTransactionsTable.direction eq "CRDT" and (
                    BankAccountTransactionsTable.account eq bankAccount.id)
        }.forEach {
            val amount = parseDecimal(it.amount)
            balance += amount
        }
        BankAccountTransactionEntity.find {
            BankAccountTransactionsTable.direction eq "DBIT" and (
                    BankAccountTransactionsTable.account eq bankAccount.id)
        }.forEach {
            val amount = parseDecimal(it.amount)
            balance -= amount
        }
    }
    /**
     * FIXME: for negative accounts, temporarily return 0, so as to make
     * the current CAMT generator happy.  Negative amounts need to have their
     * onw sub-tree in the report, see bug: #6962
     */
    if (balance < BigDecimal.ZERO) return BigDecimal.ZERO
    return balance
}

// For now, returns everything.
fun historyForAccount(bankAccount: BankAccountEntity): MutableList<RawPayment> {
    val history = mutableListOf<RawPayment>()
    transaction {
        /**
        FIXME: add the following condition too:
        and (BankAccountTransactionsTable.date.between(start.millis, end.millis))
         */
        /**
        FIXME: add the following condition too:
        and (BankAccountTransactionsTable.date.between(start.millis, end.millis))
         */
        BankAccountTransactionEntity.find {
            BankAccountTransactionsTable.account eq bankAccount.id
        }.forEach {
            history.add(
                RawPayment(
                    subject = it.subject,
                    creditorIban = it.creditorIban,
                    creditorBic = it.creditorBic,
                    creditorName = it.creditorName,
                    debtorIban = it.debtorIban,
                    debtorBic = it.debtorBic,
                    debtorName = it.debtorName,
                    date = importDateFromMillis(it.date).toDashedDate(),
                    amount = it.amount,
                    currency = it.currency,
                    // The line below produces a value too long (>35 chars),
                    // and it makes the document invalid!
                    // uid = "${it.pmtInfId}-${it.msgId}"
                    uid = it.accountServicerReference,
                    direction = it.direction,
                    pmtInfId = it.pmtInfId
                )
            )

        }
    }
    return history
}
