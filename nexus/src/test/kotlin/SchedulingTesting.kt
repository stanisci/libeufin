import io.ktor.client.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.nexus.whileTrueOperationScheduler
import tech.libeufin.sandbox.sandboxApp
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SchedulingTesting {
    // Testing the 'sleep' technique of the scheduler, to watch with TOP(1)
    @Ignore // Just an experimental piece.  No assertion takes place, nor its logic is used anywhere.
    @Test
    fun sleep1SecWithDelay() {
        val sched = Executors.newScheduledThreadPool(1)
        sched.scheduleAtFixedRate(
            { println(".") },
            1,
            1,
            TimeUnit.SECONDS
        )
        runBlocking {
            launch { awaitCancellation() }
        }
    }
    // Launching the scheduler to measure its perf with TOP(1)
    @Test
    fun normalOperation() {
        withTestDatabase {
            prepNexusDb()
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                whileTrueOperationScheduler(client)
            }
        }
        runBlocking {
            launch { awaitCancellation() }
        }
    }
}