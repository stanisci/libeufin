import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tech.libeufin.sandbox.sandboxApp
import tech.libeufin.util.buildBasicAuthLine

class SandboxAccessApiTest {
    val mapper = ObjectMapper()
    // Check successful and failing case due to insufficient funds.
    @Test
    fun debitWithdraw() {
        withTestDatabase {
            prepSandboxDb()
            withTestApplication(sandboxApp) {
                runBlocking {
                    // Normal, successful withdrawal.
                    client.post<Any>("/demobanks/default/access-api/accounts/foo/withdrawals") {
                        expectSuccess = true
                        headers {
                            append(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json
                            )
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("foo", "foo")
                            )
                        }
                        this.body = "{\"amount\": \"TESTKUDOS:1\"}"
                    }
                    // Withdrawal over the debit threshold.
                    val r: HttpStatusCode = client.post("/demobanks/default/access-api/accounts/foo/withdrawals") {
                        expectSuccess = false
                        headers {
                            append(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json
                            )
                            append(
                                HttpHeaders.Authorization,
                                buildBasicAuthLine("foo", "foo")
                            )
                        }
                        this.body = "{\"amount\": \"TESTKUDOS:99999999999\"}"
                    }
                    assert(HttpStatusCode.Forbidden.value == r.value)
                }
            }
        }
    }

    @Test
    fun registerTest() {
        // Test IBAN conflict detection.
        withSandboxTestDatabase {
            withTestApplication(sandboxApp) {
                runBlocking {
                    val bodyFoo = mapper.writeValueAsString(object {
                        val username = "x"
                        val password = "y"
                        val iban = FOO_USER_IBAN
                    })
                    val bodyBar = mapper.writeValueAsString(object {
                        val username = "y"
                        val password = "y"
                        val iban = FOO_USER_IBAN // conflicts
                    })
                    val bodyBaz = mapper.writeValueAsString(object {
                        val username = "y"
                        val password = "y"
                        val iban = BAR_USER_IBAN
                    })
                    // The following block would allow to save many LOC,
                    // but gets somehow ignored.
                    /*client.config {
                        this.defaultRequest {
                            headers {
                                append(
                                    HttpHeaders.ContentType,
                                    ContentType.Application.Json
                                )
                            }
                            expectSuccess = false
                        }
                    }*/
                    // Succeeds.
                    client.post<HttpResponse>(
                        urlString = "/demobanks/default/access-api/testing/register",
                    ) {
                        this.body = bodyFoo
                        expectSuccess = true
                        headers {
                            append(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json
                            )
                        }
                    }
                    // Hits conflict, because of the same IBAN.
                    val r = client.post<HttpResponse>(
                        "/demobanks/default/access-api/testing/register"
                    ) {
                        this.body = bodyBar
                        expectSuccess = false
                        headers {
                            append(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json
                            )
                        }
                    }
                    assert(r.status.value == HttpResponseStatus.CONFLICT.code())
                    // Succeeds, because of a new IBAN.
                    client.post<HttpResponse>(
                        "/demobanks/default/access-api/testing/register"
                    ) {
                        this.body = bodyBaz
                        expectSuccess = true
                        headers {
                            append(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json
                            )
                        }
                    }
                }
            }

        }
    }
}