import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

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

}