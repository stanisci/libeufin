import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tech.libeufin.sandbox.sandboxApp

class SandboxCircuitApiTest {
    // Get /config, fails if != 200.
    @Test
    fun config() {
        withSandboxTestDatabase {
            testApplication {
                application(sandboxApp)
                runBlocking {
                    val r= client.get("/demobanks/default/circuit-api/config")
                    println(r.bodyAsText())
                }
            }
        }
    }

    // Tests the registration logic.  Triggers
    // any error code, following at least one execution
    // path.
    @Test
    fun registration() {
        withSandboxTestDatabase {
            testApplication {
                application(sandboxApp)
                runBlocking {
                    client.post("/demobanks/default/circuit-api/accounts") {
                        basicAuth("admin", "foo")
                    }
                }
            }
        }

    }
}