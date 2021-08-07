import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.sandbox.BankAccountTransactionsTable
import tech.libeufin.sandbox.BankAccountsTable
import tech.libeufin.sandbox.balanceForAccount
import tech.libeufin.util.millis
import tech.libeufin.util.validateBic
import java.math.BigInteger
import java.time.LocalDateTime

class StringsTest {

    @Test
    fun bicTest() {
        assert(validateBic("GENODEM1GLS"))
        assert(validateBic("AUTOATW1XXX"))
    }
}