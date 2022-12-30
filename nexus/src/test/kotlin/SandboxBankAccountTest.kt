import io.ktor.http.*
import org.junit.Test
import tech.libeufin.sandbox.SandboxError
import tech.libeufin.sandbox.getBalance
import tech.libeufin.sandbox.sandboxApp
import tech.libeufin.sandbox.wireTransfer
import tech.libeufin.util.buildBasicAuthLine
import tech.libeufin.util.parseDecimal

class SandboxBankAccountTest {
    // Check if the balance shows debit.
    @Test
    fun debitBalance() {
        withTestDatabase {
            prepSandboxDb()
            wireTransfer(
                "admin",
                "foo",
                "default",
                "Show up in logging!",
                "TESTKUDOS:1"
            )
            /**
             * Bank gave 1 to foo, should be -1 debit now.  Because
             * the payment is still pending (= not booked), the pending
             * transactions must be included in the calculation.
             */
            var bankBalance = getBalance("admin", true)
            assert(bankBalance == parseDecimal("-1"))
            wireTransfer(
                "foo",
                "admin",
                "default",
                "Show up in logging!",
                "TESTKUDOS:5"
            )
            bankBalance = getBalance("admin", true)
            assert(bankBalance == parseDecimal("4"))
            // Trigger Insufficient funds case for users.
            try {
                wireTransfer(
                    "foo",
                    "admin",
                    "default",
                    "Show up in logging!",
                    "TESTKUDOS:5000"
                )
            } catch (e: SandboxError) {
                // Future versions may wrap this case into a dedicate exception type.
                assert(e.statusCode == HttpStatusCode.PreconditionFailed)
            }
            // Trigger Insufficient funds case for the bank.
            try {
                wireTransfer(
                    "admin",
                    "foo",
                    "default",
                    "Show up in logging!",
                    "TESTKUDOS:5000000"
                )
            } catch (e: SandboxError) {
                // Future versions may wrap this case into a dedicate exception type.
                assert(e.statusCode == HttpStatusCode.PreconditionFailed)
            }
            // Check balance didn't change for both parties.
            bankBalance = getBalance("admin", true)
            assert(bankBalance == parseDecimal("4"))
            val fooBalance = getBalance("foo", true)
            assert(fooBalance == parseDecimal("-4"))
        }
    }
}