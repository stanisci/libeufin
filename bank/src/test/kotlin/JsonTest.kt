import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.junit.Test
import tech.libeufin.bank.RelativeTime
import tech.libeufin.bank.RelativeTimeSerializer
import tech.libeufin.bank.TokenRequest
import tech.libeufin.bank.TokenScope

@Serializable
data class MyJsonType(
    val content: String,
    val n: Int
)

// Running (de)serialization, only checking that no exceptions are raised.
class JsonTest {
    @Test
    fun serializationTest() {
        Json.encodeToString(MyJsonType("Lorem Ipsum", 3))
    }
    @Test
    fun deserializationTest() {
        val serialized = """
            {"content": "Lorem Ipsum", "n": 3}
        """.trimIndent()
        Json.decodeFromString<MyJsonType>(serialized)
    }
    @Test
    fun unionTypeTest() {
        val jsonCfg = Json {
            serializersModule = SerializersModule {
                contextual(RelativeTime::class) {
                    RelativeTimeSerializer
                }
            }
        }
        assert(jsonCfg.decodeFromString<RelativeTime>("{\"d_us\": 3}").d_us == 3L)
        assert(jsonCfg.decodeFromString<RelativeTime>("{\"d_us\": \"forever\"}").d_us == Long.MAX_VALUE)
        val tokenReq = jsonCfg.decodeFromString<TokenRequest>("""
            {
              "scope": "readonly",
              "duration": {"d_us": 30}
            }
        """.trimIndent())
        assert(tokenReq.scope == TokenScope.readonly && tokenReq.duration.d_us == 30L)
    }
}