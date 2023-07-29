import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.sandbox.*
import tech.libeufin.util.millis
import tech.libeufin.util.roundToTwoDigits
import java.math.BigDecimal
import java.time.LocalDateTime

class BalanceTest {
    @Test
    fun balanceTest() {
        val config = DemobankConfig(
            currency = "EUR",
            bankDebtLimit = 1000000,
            usersDebtLimit = 10000,
            allowRegistrations = true,
            demobankName = "default",
            withSignupBonus = false
        )
        withTestDatabase {
            transaction {
                insertConfigPairs(config)
                val demobank = DemobankConfigEntity.new {
                    name = "default"
                }
                val one = BankAccountEntity.new {
                    iban = "IBAN 1"
                    bic = "BIC"
                    label = "label 1"
                    owner = "admin"
                    this.demoBank = demobank
                }
                val other = BankAccountEntity.new {
                    iban = "IBAN 2"
                    bic = "BIC"
                    label = "label 2"
                    owner = "admin"
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
                wireTransfer(
                    other.label, one.label, demobank.name, "one gets 1", "EUR:1"
                )
                wireTransfer(
                    other.label, one.label, demobank.name, "one gets another 1", "EUR:1"
                )
                wireTransfer(
                    one.label, other.label, demobank.name, "one gives 1", "EUR:1"
                )
                val maybeOneBalance: BigDecimal = getBalance(one)
                println(maybeOneBalance)
                assert(BigDecimal.ONE.roundToTwoDigits() == maybeOneBalance.roundToTwoDigits())
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
