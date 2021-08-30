import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.sandbox.*
import tech.libeufin.util.millis
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
                val one = BankAccountEntity.new {
                    name = "Person 1"
                    iban = "IBAN 1"
                    bic = "BIC"
                    label = "label 1"
                    currency = "EUR"
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
                }
                assert(java.math.BigDecimal.ONE == balanceForAccount(one))
            }
        }
    }
}
