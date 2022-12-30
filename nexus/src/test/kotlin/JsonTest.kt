import com.fasterxml.jackson.databind.JsonNode
import org.junit.Test
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import tech.libeufin.nexus.server.CreateBankConnectionFromBackupRequestJson
import tech.libeufin.nexus.server.CreateBankConnectionFromNewRequestJson
import tech.libeufin.sandbox.sandboxApp


class JsonTest {

    @Test
    fun testJackson() {
        val mapper = jacksonObjectMapper()
        val backupObj = CreateBankConnectionFromBackupRequestJson(
            name = "backup", passphrase = "secret", data = mapper.readTree("{}")
        )
        val roundTrip = mapper.readValue<CreateBankConnectionFromBackupRequestJson>(mapper.writeValueAsString(backupObj))
        assert(roundTrip.data.toString() == "{}" && roundTrip.passphrase == "secret" && roundTrip.name == "backup")
        val newConnectionObj = CreateBankConnectionFromNewRequestJson(
            name = "new-connection", type = "ebics", data = mapper.readTree("{}")
        )
        val roundTripNew = mapper.readValue<CreateBankConnectionFromNewRequestJson>(mapper.writeValueAsString(newConnectionObj))
        assert(roundTripNew.data.toString() == "{}" && roundTripNew.type == "ebics" && roundTripNew.name == "new-connection")
    }

    /*@Test
    fun testSandboxJsonParsing() {
        testApplication {
            application(sandboxApp)
            client.post("/admin/ebics/subscribers") {
                basicAuth("admin", "foo")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }
    }*/
}