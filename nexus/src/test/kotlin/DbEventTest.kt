import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.util.PostgresListenHandle
import tech.libeufin.util.postgresNotify


class DbEventTest {

    /**
     * LISTENs to one DB channel but only wakes up
     * if the payload is how expected.
     */
    @Test
    fun payloadTest() {
        withTestDatabase {
            val listenHandle = PostgresListenHandle("X")
            transaction { listenHandle.postgresListen() }
            runBlocking {
                launch {
                    val isArrived = listenHandle.waitOnIoDispatchersForPayload(
                        timeoutMs = 1000L,
                        expectedPayload = "Y"
                    )
                    assert(isArrived)
                }
                launch {
                    delay(500L); // Ensures the wait helper runs first.
                    transaction { this.postgresNotify("X", "Y") }
                }
            }
        }
    }
}