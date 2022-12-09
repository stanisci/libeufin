import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.util.ByteBufferBackedInputStream
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
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
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Mostly checking legacy API's access control.
 */
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
    fun adminEbiscSubscribers() {
        dbHelper {
            withTestApplication(sandboxApp) {
                runBlocking {
                    /**
                     * Create a EBICS subscriber.  That conflicts because
                     * MakeEnv.kt created it already, but tests access control
                     * and conflict detection.
                     */
                    var body = mapper.writeValueAsString(object {
                        val hostID = "foo"
                        val userID = "foo"
                        val systemID = "foo"
                        val partnerID = "foo"
                    })
                    var r: HttpResponse = client.post("/admin/ebics/subscribers") {
                        expectSuccess = false
                        headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("admin", "foo")
                            )
                            append(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json
                            )
                        }
                        this.body = body
                    }
                    assert(r.status.value == HttpStatusCode.Conflict.value)
                    /**
                     * Check that EBICS subscriber indeed exists.
                     */
                    r = client.get("/admin/ebics/subscribers") {
                        headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("admin", "foo")
                            )
                        }
                    }
                    assert(r.status.value == HttpStatusCode.OK.value)
                    val buf = ByteArrayOutputStream()
                    r.content.read { buf.write(it.array()) }
                    val respObj = mapper.readTree(buf.toString())
                    assert("foo" == respObj.get("subscribers").get(0).get("userID").asText())
                    /**
                     * Try same operations as above, with wrong admin credentials
                     */
                    r = client.get("/admin/ebics/subscribers") {
                        expectSuccess = false
                        headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("admin", "wrong")
                            )
                        }
                    }
                    assert(r.status.value == HttpStatusCode.Unauthorized.value)
                    r = client.post("/admin/ebics/subscribers") {
                        expectSuccess = false
                        headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("admin", "wrong")
                            )
                        }
                    }
                    assert(r.status.value == HttpStatusCode.Unauthorized.value)
                    // Good credentials, but unauthorized user.
                    r = client.get("/admin/ebics/subscribers") {
                        expectSuccess = false
                        headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("foo", "foo")
                            )
                        }
                    }
                    assert(r.status.value == HttpStatusCode.Unauthorized.value)
                    r = client.post("/admin/ebics/subscribers") {
                        expectSuccess = false
                        headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("foo", "foo")
                            )
                        }
                    }
                    assert(r.status.value == HttpStatusCode.Unauthorized.value)
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
                    client.post<HttpResponse>("/admin/ebics/subscribers") {
                        expectSuccess = true
                        headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("admin", "foo")
                            )
                            append(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json
                            )
                        }
                        this.body = body
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
                    client.post<HttpResponse>("/admin/ebics/bank-accounts") {
                        expectSuccess = true
                        headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("admin", "foo")
                            )
                            append(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json
                            )
                        }
                        this.body = body
                    }
                    r = client.get("/admin/ebics/subscribers") {
                        headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("admin", "foo")
                            )
                        }
                    }
                    assert(r.status.value == HttpStatusCode.OK.value)
                    val buf_ = ByteArrayOutputStream()
                    r.content.read { buf_.write(it.array()) }
                    val respObj_ = mapper.readTree(buf_.toString())
                    val bankAccountLabel = respObj_.get("subscribers").get(1).get("demobankAccountLabel").asText()
                    assert("baz" == bankAccountLabel)
                    // Same operation, wrong/unauth credentials.
                    r = client.post("/admin/ebics/bank-accounts") {
                        expectSuccess = false
                        headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("admin", "wrong")
                            )
                        }
                    }
                    assert(r.status.value == HttpStatusCode.Unauthorized.value)
                    r = client.post("/admin/ebics/bank-accounts") {
                        expectSuccess = false
                        headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("foo", "foo")
                            )
                        }
                    }
                    assert(r.status.value == HttpStatusCode.Unauthorized.value)
                }
            }
        }
    }

    // EBICS Hosts API.
    @Ignore
    fun adminEbicsCreateHost() {
        dbHelper {
            withTestApplication(sandboxApp) {
                runBlocking {
                    val body = mapper.writeValueAsString(
                        object {
                            val hostID = "www"
                            var ebicsVersion = "www"
                        }
                    )
                    // Valid request, good credentials.
                    var r = client.post<HttpResponse>("/admin/ebics/hosts") {
                        this.body = body
                        this.headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("admin", "foo")
                            )
                            append(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json
                            )
                        }
                    }
                    assert(r.status.value == HttpResponseStatus.OK.code())
                    r = client.get("/admin/ebics/hosts") {
                        expectSuccess = false

                    }
                    assert(r.status.value == HttpResponseStatus.UNAUTHORIZED.code())
                    r = client.get("/admin/ebics/hosts") {
                        this.headers {
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("admin", "foo")
                            )
                        }
                    }
                    assert(r.status.value == HttpResponseStatus.OK.code())
                    // Invalid, with good credentials.
                    r = client.post("/admin/ebics/hosts") {
                        expectSuccess = false
                        this.body = "invalid"
                        this.headers {
                            append(
                                io.ktor.http.HttpHeaders.Authorization,
                                buildBasicAuthLine("admin", "foo")
                            )
                            append(
                                io.ktor.http.HttpHeaders.ContentType,
                                ContentType.Application.Json
                            )
                        }
                    }
                    assert(r.status.value == HttpResponseStatus.BAD_REQUEST.code())
                    // Unauth: admin with wrong password.
                    r = client.post("/admin/ebics/hosts") {
                        expectSuccess = false
                        this.headers {
                            append(
                                io.ktor.http.HttpHeaders.Authorization,
                                buildBasicAuthLine("admin", "bar")
                            )
                        }
                    }
                    assert(r.status.value == HttpResponseStatus.UNAUTHORIZED.code())
                    // Auth & forbidden resource.
                    r = client.post("/admin/ebics/hosts") {
                        expectSuccess = false
                        this.headers {
                            append(
                                io.ktor.http.HttpHeaders.Authorization,
                                // Exist, but no rights over the EBICS host.
                                buildBasicAuthLine("foo", "foo")
                            )
                        }
                    }
                    assert(r.status.value == HttpResponseStatus.UNAUTHORIZED.code())
                }
            }
        }
    }
}