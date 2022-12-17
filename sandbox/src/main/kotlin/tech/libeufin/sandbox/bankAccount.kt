package tech.libeufin.sandbox

import io.ktor.http.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.experimental.suspendedTransactionAsync
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.*
import java.math.BigDecimal

// Mainly useful inside the Camt generator.
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
            "A payment direction was found neither CRDT nor DBIT",
            LibeufinErrorCode.LIBEUFIN_EC_INVALID_STATE
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
    return balance
}