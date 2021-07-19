package tech.libeufin.sandbox

import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.sandbox.BankAccountTransactionsTable.amount
import tech.libeufin.util.RawPayment
import tech.libeufin.util.importDateFromMillis
import tech.libeufin.util.toDashedDate
import java.math.BigInteger

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")

fun balanceForAccount(iban: String): java.math.BigDecimal {
    logger.debug("Calculating balance for account: ${iban}")
    var balance = java.math.BigDecimal.ZERO
    transaction {
        BankAccountTransactionsTable.select {
            BankAccountTransactionsTable.creditorIban eq iban
        }.forEach {
            val amount = java.math.BigDecimal(it[amount])
            balance += amount
        }
        BankAccountTransactionsTable.select {
            BankAccountTransactionsTable.debtorIban eq iban
        }.forEach {
            val amount = java.math.BigDecimal(it[amount])
            balance -= amount
        }
    }
    return balance
}

fun historyForAccount(iban: String): List<RawPayment> {
    val history = mutableListOf<RawPayment>()
    logger.debug("Querying transactions involving: ${iban}")
    transaction {
        BankAccountTransactionsTable.select {
            BankAccountTransactionsTable.creditorIban eq iban or
                    (BankAccountTransactionsTable.debtorIban eq iban)
            /**
            FIXME: add the following condition too:
            and (BankAccountTransactionsTable.date.between(start.millis, end.millis))
             */
            /**
            FIXME: add the following condition too:
            and (BankAccountTransactionsTable.date.between(start.millis, end.millis))
             */

        }.forEach {
            history.add(
                RawPayment(
                    subject = it[BankAccountTransactionsTable.subject],
                    creditorIban = it[BankAccountTransactionsTable.creditorIban],
                    creditorBic = it[BankAccountTransactionsTable.creditorBic],
                    creditorName = it[BankAccountTransactionsTable.creditorName],
                    debitorIban = it[BankAccountTransactionsTable.debtorIban],
                    debitorBic = it[BankAccountTransactionsTable.debtorBic],
                    debitorName = it[BankAccountTransactionsTable.debtorName],
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
