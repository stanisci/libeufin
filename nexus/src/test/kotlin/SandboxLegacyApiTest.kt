import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.util.*
import io.ktor.utils.io.*
import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.sandbox.sandboxApp
import tech.libeufin.util.buildBasicAuthLine
import tech.libeufin.util.getIban
import java.io.ByteArrayOutputStream

class SandboxLegacyApiTest {
    fun dbHelper (f: () -> Unit) {
        withTestDatabase {
            prepSandboxDb()
            f()
        }
    }
    val mapper = ObjectMapper()

    // EBICS Subscribers API.
    @Test
    fun adminEbicsSubscribers() {
        dbHelper {
            testApplication {
                application(sandboxApp)
                runBlocking {
                    /**
                     * Create a EBICS subscriber.  That conflicts because
                     * MakeEnv.kt created it already, but tests access control
                     * and conflict detection.
                     */
                    var body = mapper.writeValueAsString(object {
                        val hostID = "eufinSandbox"
                        val userID = "foo"
                        val systemID = "foo"
                        val partnerID = "foo"
                    })
                    var r: HttpResponse = client.post("/admin/ebics/subscribers") {
                        expectSuccess = false
                        contentType(ContentType.Application.Json)
                        basicAuth("admin", "foo")
                        setBody(body)
                    }
                    assert(r.status.value == HttpStatusCode.Conflict.value)

                    // Check that EBICS subscriber indeed exists.
                    r = client.get("/admin/ebics/subscribers") {
                        basicAuth("admin", "foo")
                    }
                    assert(r.status.value == HttpStatusCode.OK.value)
                    val respObj = mapper.readTree(r.bodyAsText())
                    assert("foo" == respObj.get("subscribers").get(0).get("userID").asText())

                    // Try same operations as above, with wrong admin credentials
                    r = client.get("/admin/ebics/subscribers") {
                        expectSuccess = false
                        basicAuth("admin", "wrong")
                    }
                    assert(r.status.value == HttpStatusCode.Unauthorized.value)
                    r = client.post("/admin/ebics/subscribers") {
                        expectSuccess = false
                        basicAuth("admin", "wrong")
                    }
                    assert(r.status.value == HttpStatusCode.Unauthorized.value)
                    // Good credentials, but insufficient rights.
                    r = client.get("/admin/ebics/subscribers") {
                        expectSuccess = false
                        basicAuth("foo", "foo")
                    }
                    assert(r.status.value == HttpStatusCode.Forbidden.value)
                    r = client.post("/admin/ebics/subscribers") {
                        expectSuccess = false
                        basicAuth("foo", "foo")
                    }
                    assert(r.status.value == HttpStatusCode.Forbidden.value)
                    /**
                     * Give a bank account to the existing subscriber.  Bank account
                     * is (implicitly / hard-coded) hosted at default demobank.
                     */
                    // Create new subscriber.  No need to have the related customer.
                    body = mapper.writeValueAsString(object {
                        val hostID = "eufinSandbox"
                        val userID = "baz"
                        val partnerID = "baz"
                        val systemID = "foo"
                    })
                    client.post("/admin/ebics/subscribers") {
                        expectSuccess = true
                        contentType(ContentType.Application.Json)
                        basicAuth("admin", "foo")
                        setBody(body)
                    }
                    // Associate new bank account to it.
                    body = mapper.writeValueAsString(object {
                        val subscriber = object {
                            val userID = "baz"
                            val partnerID = "baz"
                            val systemID = "baz"
                            val hostID = "eufinSandbox"
                        }
                        val iban = getIban()
                        val bic = "SANDBOXX"
                        val name = "Now Have Account"
                        val label = "baz"
                    })
                    client.post("/admin/ebics/bank-accounts") {
                        expectSuccess = true
                        contentType(ContentType.Application.Json)
                        basicAuth("admin", "foo")
                        setBody(body)
                    }
                    r = client.get("/admin/ebics/subscribers") {
                        basicAuth("admin", "foo")
                    }
                    assert(r.status.value == HttpStatusCode.OK.value)
                    val respObj_ = mapper.readTree(r.bodyAsText())
                    val bankAccountLabel = respObj_.get("subscribers").get(1).get("demobankAccountLabel").asText()
                    assert("baz" == bankAccountLabel)
                    // Same operation, wrong/unauth credentials.
                    r = client.post("/admin/ebics/bank-accounts") {
                        expectSuccess = false
                        basicAuth("admin", "wrong")
                    }
                    assert(r.status.value == HttpStatusCode.Unauthorized.value)
                    r = client.post("/admin/ebics/bank-accounts") {
                        expectSuccess = false
                        basicAuth("foo", "foo")
                    }
                    assert(r.status.value == HttpStatusCode.Forbidden.value)
                }
            }
        }
    }

    // EBICS Hosts API.
    @Test
    fun adminEbicsCreateHost() {
        dbHelper {
            testApplication {
                application(sandboxApp)
                runBlocking {
                    val body = mapper.writeValueAsString(
                        object {
                            val hostID = "www"
                            var ebicsVersion = "www"
                        }
                    )
                    // Valid request, good credentials.
                    client.post("/admin/ebics/hosts") {
                        expectSuccess = true
                        setBody(body)
                        contentType(ContentType.Application.Json)
                        basicAuth("admin", "foo")
                    }
                    var r = client.get("/admin/ebics/hosts") { expectSuccess = false }
                    assert(r.status.value == HttpResponseStatus.UNAUTHORIZED.code())
                    client.get("/admin/ebics/hosts") {
                        basicAuth("admin", "foo")
                        expectSuccess = true
                    }
                    // Invalid, with good credentials.
                    r = client.post("/admin/ebics/hosts") {
                        expectSuccess = false
                        setBody("invalid")
                        contentType(ContentType.Application.Json)
                        basicAuth("admin", "foo")
                    }
                    assert(r.status.value == HttpStatusCode.BadRequest.value)
                    // Unauth: admin with wrong password.
                    r = client.post("/admin/ebics/hosts") {
                        expectSuccess = false
                        basicAuth("admin", "bar")
                    }
                    assert(r.status.value == HttpStatusCode.Unauthorized.value)
                    // Auth & forbidden resource.
                    r = client.post("/admin/ebics/hosts") {
                        expectSuccess = false
                        basicAuth("foo", "foo")
                    }
                    assert(r.status.value == HttpStatusCode.Forbidden.value)
                }
            }
        }
    }
}