import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.sandbox.BankAccountTransactionsTable
import tech.libeufin.sandbox.BankAccountsTable
import tech.libeufin.sandbox.balanceForAccount
import tech.libeufin.util.millis
import java.math.BigInteger
import java.time.LocalDateTime

class BalanceTest {

    @Test
    fun balanceTest() {
        withTestDatabase {
            transaction {
                SchemaUtils.create(BankAccountTransactionsTable)
                BankAccountTransactionsTable.insert {
                    it[account] = EntityID(0, BankAccountsTable)
                    it[creditorIban] = "earns"
                    it[creditorBic] = "BIC"
                    it[creditorName] = "Creditor Name"
                    it[debtorIban] = "spends"
                    it[debtorBic] = "BIC"
                    it[debtorName] = "Debitor Name"
                    it[subject] = "deal"
                    it[amount] = "1"
                    it[date] = LocalDateTime.now().millis()
                    it[currency] = "EUR"
                    it[pmtInfId] = "0"
                    it[direction] = "DBIT"
                    it[accountServicerReference] = "test-account-servicer-reference"
                }
                BankAccountTransactionsTable.insert {
                    it[account] = EntityID(0, BankAccountsTable)
                    it[creditorIban] = "earns"
                    it[creditorBic] = "BIC"
                    it[creditorName] = "Creditor Name"
                    it[debtorIban] = "spends"
                    it[debtorBic] = "BIC"
                    it[debtorName] = "Debitor Name"
                    it[subject] = "deal"
                    it[amount] = "1"
                    it[date] = LocalDateTime.now().millis()
                    it[currency] = "EUR"
                    it[pmtInfId] = "0"
                    it[direction] = "DBIT"
                    it[accountServicerReference] = "test-account-servicer-reference"
                }
                BankAccountTransactionsTable.insert {
                    it[account] = EntityID(0, BankAccountsTable)
                    it[creditorIban] = "other"
                    it[creditorBic] = "BIC"
                    it[creditorName] = "Creditor Name"
                    it[debtorIban] = "earns"
                    it[debtorBic] = "BIC"
                    it[debtorName] = "Debitor Name"
                    it[subject] = "deal"
                    it[amount] = "1"
                    it[date] = LocalDateTime.now().millis()
                    it[currency] = "EUR"
                    it[pmtInfId] = "0"
                    it[direction] = "DBIT"
                    it[accountServicerReference] = "test-account-servicer-reference"
                }
                BankAccountTransactionsTable.insert {
                    it[account] = EntityID(0, BankAccountsTable)
                    it[creditorIban] = "other"
                    it[creditorBic] = "BIC"
                    it[creditorName] = "Creditor Name"
                    it[debtorIban] = "earns-bad-amount"
                    it[debtorBic] = "BIC"
                    it[debtorName] = "Debitor Name"
                    it[subject] = "deal"
                    it[amount] = "not a number"
                    it[date] = LocalDateTime.now().millis()
                    it[currency] = "EUR"
                    it[pmtInfId] = "0"
                    it[direction] = "DBIT"
                    it[accountServicerReference] = "test-account-servicer-reference"
                }
                assert(java.math.BigDecimal.ONE == balanceForAccount("earns"))
                try {
                    balanceForAccount("earns-bad-amount")
                } catch (e: UtilError) {
                    return@transaction
                }
                // here the expected exception wasn't thrown.
                assert(false)
            }
        }
    }
}