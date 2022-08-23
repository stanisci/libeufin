import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.sandbox.*
import tech.libeufin.util.millis
import java.math.BigDecimal
import java.time.LocalDateTime

class BalanceTest {

    @Test
    fun balanceTest() {
        withTestDatabase {
            transaction {
                SchemaUtils.create(
                    BankAccountsTable,
                    BankAccountTransactionsTable,
                    BankAccountFreshTransactionsTable
                )
                val demobank = DemobankConfigEntity.new {
                    currency = "EUR"
                    bankDebtLimit = 1000000
                    usersDebtLimit = 10000
                    allowRegistrations = true
                    name = "default"
                    withSignupBonus = false
                    uiTitle = "test"
                }
                val one = BankAccountEntity.new {
                    iban = "IBAN 1"
                    bic = "BIC"
                    label = "label 1"
                    owner = "test"
                    this.demoBank = demobank
                }
                BankAccountTransactionEntity.new {
                    account = one
                    creditorIban = "earns"
                    creditorBic = "BIC"
                    creditorName = "Creditor Name"
                    debtorIban = "spends"
                    debtorBic = "BIC"
                    debtorName = "Debitor Name"
                    subject = "deal"
                    amount = "1"
                    date = LocalDateTime.now().millis()
                    currency = "EUR"
                    pmtInfId = "0"
                    direction = "CRDT"
                    accountServicerReference = "test-account-servicer-reference"
                    this.demobank = demobank
                }
                BankAccountTransactionEntity.new {
                    account = one
                    creditorIban = "earns"
                    creditorBic = "BIC"
                    creditorName = "Creditor Name"
                    debtorIban = "spends"
                    debtorBic = "BIC"
                    debtorName = "Debitor Name"
                    subject = "deal"
                    amount = "1"
                    date = LocalDateTime.now().millis()
                    currency = "EUR"
                    pmtInfId = "0"
                    direction = "CRDT"
                    accountServicerReference = "test-account-servicer-reference"
                    this.demobank = demobank
                }
                BankAccountTransactionEntity.new {
                    account = one
                    creditorIban = "earns"
                    creditorBic = "BIC"
                    creditorName = "Creditor Name"
                    debtorIban = "spends"
                    debtorBic = "BIC"
                    debtorName = "Debitor Name"
                    subject = "deal"
                    amount = "1"
                    date = LocalDateTime.now().millis()
                    currency = "EUR"
                    pmtInfId = "0"
                    direction = "DBIT"
                    accountServicerReference = "test-account-servicer-reference"
                    this.demobank = demobank
                }
                assert(BigDecimal.ONE == balanceForAccount(one))
            }
        }
    }
    @Test
    fun balanceAbsTest() {
        val minus = BigDecimal.ZERO - BigDecimal.ONE
        val plus = BigDecimal.ONE
        println(minus.abs().toPlainString())
        println(plus.abs().toPlainString())
    }
}
