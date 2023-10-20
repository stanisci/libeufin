import kotlinx.coroutines.runBlocking
import org.junit.Test
import tech.libeufin.nexus.InitiatedPayment
import tech.libeufin.nexus.NEXUS_CONFIG_SOURCE
import tech.libeufin.nexus.PaymentInitiationOutcome
import tech.libeufin.nexus.TalerAmount
import java.time.Instant
import kotlin.test.assertEquals

class DatabaseTest {

    @Test
    fun paymentInitiation() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        val initPay = InitiatedPayment(
            amount = TalerAmount(44, 0, "KUDOS"),
            creditPaytoUri = "payto://iban/not-used",
            executionTime = Instant.now(),
            wireTransferSubject = "test",
            clientRequestUuid = "unique"
        )
        runBlocking {
            assertEquals(db.initiatePayment(initPay), PaymentInitiationOutcome.SUCCESS)
            assertEquals(db.initiatePayment(initPay), PaymentInitiationOutcome.UNIQUE_CONSTRAINT_VIOLATION)
        }
    }
}