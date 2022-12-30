import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.junit.Test
import io.ktor.serialization.jackson.*
import io.ktor.server.request.*
import org.junit.Assert
import org.junit.Ignore
import io.ktor.server.plugins.contentnegotiation.*

class DomainSocketTest {
    @Test @Ignore
    fun bind() {
        startServer("/tmp/java.sock") {
            install(ContentNegotiation) { jackson() }
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