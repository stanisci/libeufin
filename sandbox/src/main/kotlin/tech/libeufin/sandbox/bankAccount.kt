package tech.libeufin.sandbox

import io.ktor.http.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.util.*
import java.math.BigDecimal

/**
 * Check whether the given bank account would surpass the
 * debit threshold, in case the potential amount gets transferred.
 * Returns true when the debit WOULD be surpassed.  */
fun maybeDebit(
    accountLabel: String,
    requestedAmount: BigDecimal,
    demobankName: String = "default"
): Boolean {
    val demobank = getDemobank(demobankName) ?: throw notFound(
        "Demobank '${demobankName}' not found when trying to check the debit threshold" +
                " for user $accountLabel"
    )
    val balance = getBalance(accountLabel, withPending = true)
    val maxDebt = if (accountLabel == "admin") {
        demobank.bankDebtLimit
    } else demobank.usersDebtLimit
    val balanceCheck = balance - requestedAmount
    if (balanceCheck < BigDecimal.ZERO && balanceCheck.abs() > BigDecimal.valueOf(maxDebt.toLong())) {
        logger.warn("User '$accountLabel' would surpass the debit" +
                " threshold of $maxDebt, given the requested amount of ${requestedAmount.toPlainString()}")
        return true
    }
    return false
}

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
fun getBalance(accountLabel: String, withPending: Boolean = true): BigDecimal {
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

// Book a CRDT and a DBIT transaction and return the unique reference thereof.
fun wireTransfer(
    debitAccount: BankAccountEntity,
    creditAccount: BankAccountEntity,
    demobank: DemobankConfigEntity,
    subject: String,
    amount: String, // $currency:$value
    pmtInfId: String? = null
): String {
    val parsedAmount = parseAmount(amount)
    val amountAsNumber = BigDecimal(parsedAmount.amount)
    if (amountAsNumber == BigDecimal.ZERO)
        throw badRequest("Wire transfers of zero not possible.")
    if (parsedAmount.currency != demobank.currency)
        throw badRequest(
            "Won't wire transfer with currency: ${parsedAmount.currency}." +
                    "  Only ${demobank.currency} allowed."
        )
    // Check funds are sufficient.
    if (maybeDebit(debitAccount.label, amountAsNumber)) {
        logger.error("Account ${debitAccount.label} would surpass debit threshold.  Rollback wire transfer")
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
            this.amount = parsedAmount.amount
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
            this.amount = parsedAmount.amount
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