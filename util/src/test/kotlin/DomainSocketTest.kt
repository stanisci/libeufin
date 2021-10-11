import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import org.junit.Test
import io.ktor.jackson.jackson
import io.ktor.request.*
import org.junit.Assert
import org.junit.Ignore

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