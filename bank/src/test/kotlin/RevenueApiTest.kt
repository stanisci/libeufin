import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.util.CryptoUtil
import net.taler.common.errorcodes.TalerErrorCode
import java.util.*
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import randHashCode

class RevenueApiTest {
    // GET /accounts/{USERNAME}/taler-revenue/history
    @Test
    fun history() = bankSetup {
        setMaxDebt("exchange", TalerAmount("KUDOS:1000000"))

        suspend fun HttpResponse.assertHistory(size: Int) {
            assertHistoryIds<MerchantIncomingHistory>(size) {
                it.incoming_transactions.map { it.row_id }
            }
        }

        // TODO auth routine

        // Check error when no transactions
        client.get("/accounts/merchant/taler-revenue/history?delta=7") {
            basicAuth("merchant", "merchant-password")
        }.assertNoContent()

        // Gen three transactions using clean transfer logic
        repeat(3) {
            transfer("KUDOS:10")
        }
        // Should not show up in the revenue API history
        tx("exchange", "KUDOS:10", "merchant", "bogus")
        // Merchant pays exchange once, but that should not appear in the result
        tx("merchant", "KUDOS:10", "exchange", "ignored")
        // Gen two transactions using raw bank transaction logic
        repeat(2) {
            tx("exchange", "KUDOS:10", "merchant", OutgoingTxMetadata(randShortHashCode(), ExchangeUrl("http://exchange.example.com/")).encode())
        }

        // Check ignore bogus subject
        client.get("/accounts/merchant/taler-revenue/history?delta=7") {
            basicAuth("merchant", "merchant-password")
        }.assertHistory(5)
        
        // Check skip bogus subject
        client.get("/accounts/merchant/taler-revenue/history?delta=5") {
            basicAuth("merchant", "merchant-password")
        }.assertHistory(5)

        // Check no useless polling
        assertTime(0, 200) {
            client.get("/accounts/merchant/taler-revenue/history?delta=-6&start=14&long_poll_ms=1000") {
                basicAuth("merchant", "merchant-password")
            }.assertHistory(5)
        }

        // Check no polling when find transaction
        assertTime(0, 200) {
            client.get("/accounts/merchant/taler-revenue/history?delta=6&long_poll_ms=1000") {
                basicAuth("merchant", "merchant-password")
            }.assertHistory(5)
        }

        coroutineScope {
            launch {  // Check polling succeed forward
                assertTime(200, 300) {
                    client.get("/accounts/merchant/taler-revenue/history?delta=2&start=13&long_poll_ms=1000") {
                        basicAuth("merchant", "merchant-password")
                    }.assertHistory(1)
                }
            }
            launch {  // Check polling timeout forward
                assertTime(200, 400) {
                    client.get("/accounts/merchant/taler-revenue/history?delta=1&start=15&long_poll_ms=300") {
                        basicAuth("merchant", "merchant-password")
                    }.assertNoContent()
                }
            }
            delay(200)
            transfer("KUDOS:10")
        }

        // Testing ranges.
        repeat(20) {
            transfer("KUDOS:10")
        }

        // forward range:
        client.get("/accounts/merchant/taler-revenue/history?delta=10&start=20") {
            basicAuth("merchant", "merchant-password")
        }.assertHistory(10)

        // backward range:
        client.get("/accounts/merchant/taler-revenue/history?delta=-10&start=25") {
            basicAuth("merchant", "merchant-password")
        }.assertHistory(10)
    }
}