package tech.libeufin.sandbox

import io.ktor.http.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.sandbox.BankAccountTransactionsTable.amount
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
fun balanceForAccount(history: List<RawPayment>): BigDecimal {
    var ret = BigDecimal.ZERO
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
        BankAccountTransactionsTable.select {
            BankAccountTransactionsTable.direction eq "CRDT" and (
                    BankAccountTransactionsTable.account eq bankAccount.id)
        }.forEach {
            val amount = parseDecimal(it[amount])
            balance += amount
        }
        BankAccountTransactionsTable.select {
            BankAccountTransactionsTable.direction eq "DBIT" and (
                    BankAccountTransactionsTable.account eq bankAccount.id)
        }.forEach {
            val amount = parseDecimal(it[amount])
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

fun historyForAccount(bankAccount: BankAccountEntity): List<RawPayment> {
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
        BankAccountTransactionsTable.select { BankAccountTransactionsTable.account eq bankAccount.id }.forEach {
            history.add(
                RawPayment(
                    subject = it[BankAccountTransactionsTable.subject],
                    creditorIban = it[BankAccountTransactionsTable.creditorIban],
                    creditorBic = it[BankAccountTransactionsTable.creditorBic],
                    creditorName = it[BankAccountTransactionsTable.creditorName],
                    debtorIban = it[BankAccountTransactionsTable.debtorIban],
                    debtorBic = it[BankAccountTransactionsTable.debtorBic],
                    debtorName = it[BankAccountTransactionsTable.debtorName],
                    date = importDateFromMillis(it[BankAccountTransactionsTable.date]).toDashedDate(),
                    amount = it[BankAccountTransactionsTable.amount],
                    currency = it[BankAccountTransactionsTable.currency],
                    // The line below produces a value too long (>35 chars),
                    // and it makes the document invalid!
                    // uid = "${it[pmtInfId]}-${it[msgId]}"
                    uid = it[BankAccountTransactionsTable.accountServicerReference],
                    direction = it[BankAccountTransactionsTable.direction],
                    pmtInfId = it[BankAccountTransactionsTable.pmtInfId]
                )
            )

        }
    }
    return history
}