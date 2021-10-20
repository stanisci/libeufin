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

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")

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

/**
 * https://github.com/JetBrains/Exposed/wiki/Transactions#working-with-coroutines
 * https://medium.com/androiddevelopers/threading-models-in-coroutines-and-android-sqlite-api-6cab11f7eb90
 *
 * FIXME: This version will be deprecated.  It was made before introducing the demobank configuration
 */
fun wireTransfer(
    debitAccount: String,
    creditAccount: String,
    amount: String,
    subjectArg: String
) {
    transaction {
        // check accounts exist
        val credit = BankAccountEntity.find {
            BankAccountsTable.label eq creditAccount
        }.firstOrNull() ?: run {
            throw SandboxError(HttpStatusCode.NotFound, "Credit account: $creditAccount, not found")
        }
        val debit = BankAccountEntity.find {
            BankAccountsTable.label eq debitAccount
        }.firstOrNull() ?: run {
            throw SandboxError(HttpStatusCode.NotFound, "Debit account: $debitAccount, not found")
        }
        if (credit.currency != debit.currency) {
            throw SandboxError(HttpStatusCode.InternalServerError,
                "Sandbox has inconsistent state: " +
                        "currency of credit (${credit.currency}) and debit (${debit.currency}) account differs."
            )
        }
        val amountObj = try {
            parseAmount(amount)
        } catch (e: Exception) {
            throw SandboxError(HttpStatusCode.BadRequest, "Amount given not valid: $amount")
        }
        // Extra check on the currency's consistency
        if (credit.currency != debit.currency) throw SandboxError(
            HttpStatusCode.InternalServerError,
            "Credit and debit account have different currency (${credit.currency} vs ${debit.currency})!",
            LibeufinErrorCode.LIBEUFIN_EC_CURRENCY_INCONSISTENT
        )
        if (amountObj.currency != credit.currency || amountObj.currency != debit.currency) {
            throw SandboxError(
                HttpStatusCode.BadRequest,
                "Currency (${amountObj.currency}) is not supported",
                LibeufinErrorCode.LIBEUFIN_EC_BAD_CURRENCY
            )
        }
        val randId = getRandomString(16)
        BankAccountTransactionEntity.new {
            creditorIban = credit.iban
            creditorBic = credit.bic
            creditorName = "Creditor Name"
            debtorIban = debit.iban
            debtorBic = debit.bic
            debtorName = "Debitor Name"
            subject = subjectArg
            this.amount = amountObj.amount.toString()
            currency = amountObj.currency
            date = getUTCnow().toInstant().toEpochMilli()
            accountServicerReference = "sandbox-$randId"
            account = debit
            direction = "DBIT"
        }
        BankAccountTransactionEntity.new {
            creditorIban = credit.iban
            creditorBic = credit.bic
            creditorName = "Creditor Name"
            debtorIban = debit.iban
            debtorBic = debit.bic
            debtorName = "Debitor Name"
            subject = subjectArg
            this.amount = amountObj.amount.toString()
            currency = amountObj.currency
            date = getUTCnow().toInstant().toEpochMilli()
            accountServicerReference = "sandbox-$randId"
            account = credit
            direction = "CRDT"
        }
    }
}
