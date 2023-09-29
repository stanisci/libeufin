import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import tech.libeufin.bank.*
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

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

    /**
     * Testing the custom absolute and relative time serializers.
     */
    @Test
    fun timeSerializers() {
        // from JSON to time types
        assert(Json.decodeFromString<RelativeTime>("{\"d_us\": 3}").d_us.toNanos() == 3000L)
        assert(Json.decodeFromString<RelativeTime>("{\"d_us\": \"forever\"}").d_us == ChronoUnit.FOREVER.duration)
        assert(Json.decodeFromString<TalerProtocolTimestamp>("{\"t_s\": 3}").t_s == Instant.ofEpochSecond(3))
        assert(Json.decodeFromString<TalerProtocolTimestamp>("{\"t_s\": \"never\"}").t_s == Instant.MAX)

        // from time types to JSON
        val oneDay = RelativeTime(d_us = Duration.of(1, ChronoUnit.DAYS))
        val oneDaySerial = Json.encodeToString(oneDay)
        assert(Json.decodeFromString<RelativeTime>(oneDaySerial).d_us == oneDay.d_us)
        val forever = RelativeTime(d_us = ChronoUnit.FOREVER.duration)
        val foreverSerial = Json.encodeToString(forever)
        assert(Json.decodeFromString<RelativeTime>(foreverSerial).d_us == forever.d_us)
    }

    @Test
    fun enumSerializer() {
        assert("\"credit\"" == Json.encodeToString(CorebankCreditDebitInfo.credit))
        assert("\"debit\"" == Json.encodeToString(CorebankCreditDebitInfo.debit))
    }
}