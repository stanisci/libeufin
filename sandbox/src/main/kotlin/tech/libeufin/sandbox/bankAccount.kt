package tech.libeufin.sandbox

import io.ktor.http.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.util.*
import java.math.BigDecimal

/**
 * The last balance is the one mentioned in the bank account's
 * last statement.  If the bank account does not have any statement
 * yet, then zero is returned.  When 'withPending' is true, it adds
 * the pending transactions to it.
 */
fun getBalance(
    bankAccount: BankAccountEntity,
    withPending: Boolean = true
): BigDecimal {
    val lastStatement = transaction {
        BankAccountStatementEntity.find {
            BankAccountStatementsTable.bankAccount eq bankAccount.id
        }.lastOrNull()
    }
    var lastBalance = if (lastStatement == null) {
        BigDecimal.ZERO
    } else { BigDecimal(lastStatement.balanceClbd) }
    if (!withPending) return lastBalance
    /**
     * Caller asks to include the pending transactions in the
     * balance.  The block below gets the transactions happened
     * later than the last statement and adds them to the balance
     * that was calculated so far.
     */
    transaction {
        val pendingTransactions = BankAccountTransactionEntity.find {
            BankAccountTransactionsTable.account eq bankAccount.id and (
                    BankAccountTransactionsTable.date.greater(lastStatement?.creationTime ?: 0L))
        }
        pendingTransactions.forEach { tx ->
            when (tx.direction) {
                "DBIT" -> lastBalance -= parseDecimal(tx.amount)
                "CRDT" -> lastBalance += parseDecimal(tx.amount)
                else -> {
                    logger.error("Transaction ${tx.id} is neither debit nor credit.")
                    throw SandboxError(
                        HttpStatusCode.InternalServerError,
                        "Error in transactions state."
                    )
                }
            }
        }
    }
    return lastBalance
}

// Wrapper offering to get bank accounts from a string.
fun getBalance(accountLabel: String, withPending: Boolean = false): BigDecimal {
    val defaultDemobank = getDefaultDemobank()
    val account = getBankAccountFromLabel(accountLabel, defaultDemobank)
    return getBalance(account, withPending)
}

/**
 * 'debitAccount' and 'creditAccount' are customer usernames
 * and ALSO labels of the bank accounts owned by them.  They are
 * used to both resort a bank account and the legal name owning
 * the bank accounts.
 */
fun wireTransfer(
    debitAccount: String,
    creditAccount: String,
    demobank: String = "default",
    subject: String,
    amount: String, // $currency:x.y
    pmtInfId: String? = null
): String {
    logger.debug("Maybe wire transfer: $debitAccount -> $creditAccount, $subject, $amount")
    val args: Triple<BankAccountEntity, BankAccountEntity, DemobankConfigEntity> = transaction {
        val demobankDb = ensureDemobank(demobank)
        val debitAccountDb = getBankAccountFromLabel(debitAccount, demobankDb)
        val creditAccountDb = getBankAccountFromLabel(creditAccount, demobankDb)
        Triple(debitAccountDb, creditAccountDb, demobankDb)
    }
    return wireTransfer(
        debitAccount = args.first,
        creditAccount = args.second,
        demobank = args.third,
        subject = subject,
        amount = amount,
        pmtInfId
    )
}
/**
 * Book a CRDT and a DBIT transaction and return the unique reference thereof.
 *
 * At the moment there is redundancy because all the creditor / debtor details
 * are contained (directly or indirectly) already in the BankAccount parameters.
 *
 * This is kept both not to break the existing tests and to allow future versions
 * where one party of the transaction is not a customer of the running Sandbox.
 */

fun wireTransfer(
    debitAccount: BankAccountEntity,
    creditAccount: BankAccountEntity,
    demobank: DemobankConfigEntity,
    subject: String,
    amount: String, // $currency:$value
    pmtInfId: String? = null
): String {
    val checkAmount = parseAmount(amount)
    if (checkAmount.amount == BigDecimal.ZERO)
        throw badRequest("Wire transfers of zero not possible.")
    if (checkAmount.currency != demobank.currency)
        throw badRequest("Won't wire transfer with currency: ${checkAmount.currency}")
    // Check funds are sufficient.
    /**
     * Using 'pending' balance because Libeufin never books.  The
     * reason is that booking is not Taler-relevant.
     */
    val pendingBalance = getBalance(debitAccount, withPending = true)
    val maxDebt = if (debitAccount.label == "admin") {
        demobank.bankDebtLimit
    } else demobank.usersDebtLimit
    val balanceCheck = pendingBalance - checkAmount.amount
    if (balanceCheck < BigDecimal.ZERO && balanceCheck.abs() > BigDecimal.valueOf(maxDebt.toLong())) {
        logger.info("Account ${debitAccount.label} would surpass debit threshold of $maxDebt.  Rollback wire transfer")
        throw SandboxError(HttpStatusCode.PreconditionFailed, "Insufficient funds")
    }
    val timeStamp = getUTCnow().toInstant().toEpochMilli()
    val transactionRef = getRandomString(8)
    transaction {
        BankAccountTransactionEntity.new {
            creditorIban = creditAccount.iban
            creditorBic = creditAccount.bic
            this.creditorName = getPersonNameFromCustomer(creditAccount.owner)
            debtorIban = debitAccount.iban
            debtorBic = debitAccount.bic
            debtorName = getPersonNameFromCustomer(debitAccount.owner)
            this.subject = subject
            this.amount = checkAmount.amount.toPlainString()
            this.currency = demobank.currency
            date = timeStamp
            accountServicerReference = transactionRef
            account = creditAccount
            direction = "CRDT"
            this.demobank = demobank
            this.pmtInfId = pmtInfId
        }
        BankAccountTransactionEntity.new {
            creditorIban = creditAccount.iban
            creditorBic = creditAccount.bic
            this.creditorName = getPersonNameFromCustomer(creditAccount.owner)
            debtorIban = debitAccount.iban
            debtorBic = debitAccount.bic
            debtorName = getPersonNameFromCustomer(debitAccount.owner)
            this.subject = subject
            this.amount = checkAmount.amount.toPlainString()
            this.currency = demobank.currency
            date = timeStamp
            accountServicerReference = transactionRef
            account = debitAccount
            direction = "DBIT"
            this.demobank = demobank
            this.pmtInfId = pmtInfId
        }
    }
    return transactionRef
}