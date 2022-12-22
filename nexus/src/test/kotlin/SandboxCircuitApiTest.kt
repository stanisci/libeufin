import io.ktor.client.features.*
import io.ktor.client.features.get
import io.ktor.client.request.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tech.libeufin.sandbox.sandboxApp

class SandboxCircuitApiTest {
    // Get /config
    @Test
    fun config() {
        withSandboxTestDatabase {
            withTestApplication(sandboxApp) {
                runBlocking {
                    val r: String = client.get("/demobanks/default/circuit-api/config33")
                    println(r)
                }
            }
        }
    }
}