import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.util.NotificationsChannelDomains
import tech.libeufin.util.PostgresListenHandle
import tech.libeufin.util.buildChannelName
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

    /**
     * This function tests the NOTIFY sent by a Exposed's
     * "new {}" overridden static method.
     */
    @Test
    fun automaticNotifyTest() {
        withTestDatabase {
            prepNexusDb()
            val nexusTxChannel = buildChannelName(
                NotificationsChannelDomains.LIBEUFIN_NEXUS_TX,
                "foo" // bank account label.
            )
            val listenHandle = PostgresListenHandle(nexusTxChannel)
            transaction { listenHandle.postgresListen() }
            runBlocking {
                launch {
                    val isArrived = listenHandle.waitOnIODispatchers(timeoutMs = 1000L)
                    assert(isArrived)
                }
                launch {
                    delay(500L); // Ensures the wait helper runs first.
                    transaction {
                        newNexusBankTransaction(
                            "TESTKUDOS",
                            "2",
                            "unblocking event"
                        )
                    }
                }
            }
        }
    }
}