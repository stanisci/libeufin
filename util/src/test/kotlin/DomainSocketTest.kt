import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import org.junit.Test
import io.ktor.jackson.jackson
import io.ktor.request.*

class DomainSocketTest {
    // @Test
    fun bind() {
        startServer("/tmp/java.sock") {
            install(ContentNegotiation) { jackson() }
            routing {
                // responds with a empty JSON object.
                get("/") {
                    this.call.respond(object {})
                    return@get
                }
                // echoes what it read in the request.
                post("/") {
                    val body = this.call.receiveText()
                    this.call.respondText(body)
                    return@post
                }
            }
        }
    }
}