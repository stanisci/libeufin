import com.fasterxml.jackson.databind.JsonNode
import org.junit.Test
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import tech.libeufin.nexus.server.CreateBankConnectionFromBackupRequestJson


class JsonTest {

    @Test
    fun testJackson() {
        val mapper = jacksonObjectMapper()
        val backupObj = CreateBankConnectionFromBackupRequestJson(
            name = "backup", passphrase = "secret", data = mapper.readTree("{}")
        )
        val roundTrip = mapper.readValue<CreateBankConnectionFromBackupRequestJson>(mapper.writeValueAsString(backupObj))
        assert(roundTrip.data.toString() == "{}" && roundTrip.passphrase == "secret" && roundTrip.name == "backup")

    }


}