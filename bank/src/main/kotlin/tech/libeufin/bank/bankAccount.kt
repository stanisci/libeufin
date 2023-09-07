package tech.libeufin.bank

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
    val balance = getBalance(accountLabel, demobankName)
    val maxDebt = if (accountLabel == "admin") {
        demobank.config.bankDebtLimit
    } else demobank.config.usersDebtLimit
    val balanceCheck = balance - requestedAmount
    if (balanceCheck < BigDecimal.ZERO && balanceCheck.abs() > BigDecimal.valueOf(maxDebt.toLong())) {
        logger.warn("User '$accountLabel' would surpass the debit" +
                " threshold of $maxDebt, given the requested amount of ${requestedAmount.toPlainString()}")
        return true
    }
    return false
}

fun getMaxDebitForUser(
    username: String,
    demobankName: String = "default"
): Int {
    val bank = getDemobank(demobankName) ?: throw internalServerError(
        "demobank $demobankName not found"
    )
    if (username == "admin") return bank.config.bankDebtLimit
    return bank.config.usersDebtLimit
}

fun getBalanceForJson(value: BigDecimal, currency: String): BalanceJson {
    return BalanceJson(
        amount = "${currency}:${value.abs()}",
        credit_debit_indicator = if (value < BigDecimal.ZERO) "debit" else "credit"
    )
}

fun getBalance(bankAccount: BankAccountEntity): BigDecimal {
    return BigDecimal(bankAccount.balance)
}

/**
 * This function balances _in bank account statements_.  A statement
 * witnesses the bank account after a given business time slot.  Therefore
 * _this_ type of balance is not guaranteed to hold the _actual_ and
 * more up-to-date bank account.  It'll be used when Sandbox will support
 * the issuing of bank statement.
 */
fun getBalanceForStatement(
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

// Gets the balance of 'accountLabel', which is hosted at 'demobankName'.
fun getBalance(accountLabel: String,
               demobankName: String = "default"
): BigDecimal {
    val demobank = getDemobank(demobankName) ?: throw SandboxError(
        HttpStatusCode.InternalServerError,
        "Demobank '$demobankName' not found"
    )

    /**
     * Setting withBankFault to true for the following reason:
     * when asking for a balance, the bank should have made sure
     * that the user has a bank account (together with a customer profile).
     * If that's not the case, it's bank's fault, since it didn't check
     * earlier.
     */
    val account = getBankAccountFromLabel(
        accountLabel,
        demobank,
        withBankFault = true
    )
    return getBalance(account)
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
    pmtInfId: String? = null,
    endToEndId: String? = null
): String {
    logger.debug("Maybe wire transfer (endToEndId: $endToEndId): $debitAccount -> $creditAccount, $subject, $amount")
    return transaction {
        val demobankDb = ensureDemobank(demobank)
        val debitAccountDb = getBankAccountFromLabel(debitAccount, demobankDb)
        val creditAccountDb = getBankAccountFromLabel(creditAccount, demobankDb)
        val parsedAmount = parseAmount(amount)
        // Potential amount to transfer.
        val amountAsNumber = BigDecimal(parsedAmount.amount)
        if (amountAsNumber == BigDecimal.ZERO)
            throw badRequest("Wire transfers of zero not possible.")
        if (parsedAmount.currency != demobankDb.config.currency)
            throw badRequest(
                "Won't wire transfer with currency: ${parsedAmount.currency}." +
                        "  Only ${demobankDb.config.currency} allowed."
            )
        // Check funds are sufficient.
        if (
            maybeDebit(
                debitAccountDb.label,
                amountAsNumber,
                demobankDb.name
            )) {
            logger.error("Account ${debitAccountDb.label} would surpass debit threshold.  Rollback wire transfer")
            throw SandboxError(HttpStatusCode.Conflict, "Insufficient funds")
        }
        val timeStamp = getNowMillis()
        val transactionRef = getRandomString(8)
        BankAccountTransactionEntity.new {
            creditorIban = creditAccountDb.iban
            creditorBic = creditAccountDb.bic
            this.creditorName = getPersonNameFromCustomer(creditAccountDb.owner)
            debtorIban = debitAccountDb.iban
            debtorBic = debitAccountDb.bic
            debtorName = getPersonNameFromCustomer(debitAccountDb.owner)
            this.subject = subject
            this.amount = parsedAmount.amount
            this.currency = demobankDb.config.currency
            date = timeStamp
            accountServicerReference = transactionRef
            account = creditAccountDb
            direction = "CRDT"
            this.demobank = demobankDb
            this.pmtInfId = pmtInfId
        }
        BankAccountTransactionEntity.new {
            creditorIban = creditAccountDb.iban
            creditorBic = creditAccountDb.bic
            this.creditorName = getPersonNameFromCustomer(creditAccountDb.owner)
            debtorIban = debitAccountDb.iban
            debtorBic = debitAccountDb.bic
            debtorName = getPersonNameFromCustomer(debitAccountDb.owner)
            this.subject = subject
            this.amount = parsedAmount.amount
            this.currency = demobankDb.config.currency
            date = timeStamp
            accountServicerReference = transactionRef
            account = debitAccountDb
            direction = "DBIT"
            this.demobank = demobankDb
            this.pmtInfId = pmtInfId
            this.endToEndId = endToEndId
        }

        // Adjusting the balances (acceptable debit conditions checked before).
        // Debit:
        val newDebitBalance = (BigDecimal(debitAccountDb.balance) - amountAsNumber).roundToTwoDigits()
        debitAccountDb.balance = newDebitBalance.toPlainString()
        // Credit:
        val newCreditBalance = (BigDecimal(creditAccountDb.balance) + amountAsNumber).roundToTwoDigits()
        creditAccountDb.balance = newCreditBalance.toPlainString()

        // Signaling this wire transfer's event.
        if (this.isPostgres()) {
            val creditChannel = buildChannelName(
                NotificationsChannelDomains.LIBEUFIN_REGIO_TX,
                creditAccountDb.label
            )
            this.postgresNotify(creditChannel, "CRDT")
            val debitChannel = buildChannelName(
                NotificationsChannelDomains.LIBEUFIN_REGIO_TX,
                debitAccountDb.label
            )
            this.postgresNotify(debitChannel, "DBIT")
        }
        transactionRef
    }
}

/**
 * Helper that constructs a transactions history page
 * according to the URI parameters passed to Access API's
 * GET /transactions.
 */
data class HistoryParams(
    val pageNumber: Int,
    val pageSize: Int,
    val fromMs: Long,
    val untilMs: Long,
    val bankAccount: BankAccountEntity
)

fun extractTxHistory(params: HistoryParams): List<XLibeufinBankTransaction> {
    val ret = mutableListOf<XLibeufinBankTransaction>()

    /**
     * Helper that gets transactions earlier than the 'firstElementId'
     * transaction AND that match the URI parameters.
     */
    fun getPage(firstElementId: Long): Iterable<BankAccountTransactionEntity> {
        return BankAccountTransactionEntity.find {
            (BankAccountTransactionsTable.id lessEq firstElementId) and
                    (BankAccountTransactionsTable.account eq params.bankAccount.id) and
                    (BankAccountTransactionsTable.date.between(params.fromMs, params.untilMs))
        }.sortedByDescending { it.id.value }.take(params.pageSize)
    }
    // Gets a pointer to the last transaction of this bank account.
    val lastTransaction: BankAccountTransactionEntity? = params.bankAccount.lastTransaction
    if (lastTransaction == null) return ret
    var nextPageIdUpperLimit: Long = lastTransaction.id.value

    // This loop fetches (and discards) pages until the desired one is found.
    for (i in 1..(params.pageNumber)) {
        val pageBuf = getPage(nextPageIdUpperLimit)
        logger.debug("pageBuf #$i follows.  Request wants #${params.pageNumber}:")
        pageBuf.forEach { logger.debug("ID: ${it.id}, subject: ${it.subject}, amount: ${it.currency}:${it.amount}") }
        if (pageBuf.none()) return ret
        nextPageIdUpperLimit = pageBuf.last().id.value - 1
        if (i == params.pageNumber) pageBuf.forEach {
            ret.add(getHistoryElementFromTransactionRow(it))
        }
    }
    return ret
}