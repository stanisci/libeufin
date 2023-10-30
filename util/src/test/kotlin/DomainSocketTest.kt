import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.junit.Test
import org.junit.Ignore

class DomainSocketTest {
    @Test @Ignore
    fun bind() {
        startServer("/tmp/java.sock") {
            routing {
                get("/") {
                    this.call.respond(object {})
                    return@get
                }
                post("/") {
                    this.call.respond(object {})
                    return@post
                }
            }
        }
    }
}