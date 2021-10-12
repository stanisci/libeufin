import org.junit.Test
import tech.libeufin.util.extractToken
import java.lang.Exception

class AuthTokenTest {
    @Test
    fun test() {
        val tok = extractToken("Bearer secret-token:XXX")
        assert(tok == "secret-token:XXX")
        val tok_0 = extractToken("Bearer secret-token:XXX%20YYY")
        assert(tok_0 == "secret-token:XXX YYY")
        val tok_1 = extractToken("Bearer secret-token:XXX YYY")
        assert(tok_1 == "secret-token:XXX YYY")
        val tok_2 = extractToken("Bearer secret-token:XXX ")
        assert(tok_2 == "secret-token:XXX ")

        val malformedAuths = listOf(
            "", "XXX", "Bearer", "Bearer ", "Bearer XXX",
            "BearerXXX", "XXXBearer", "Bearer secret-token",
            "Bearer secret-token:", " Bearer", " Bearer secret-token:XXX",
            ":: ::"
        )
        for (token in malformedAuths) {
            try {
                extractToken(token)
            } catch (e: Exception) {
                assert(e is UtilError)
                continue
            }
            println("Error: '$token' made it through")
            assert(false) // should never arrive here.
        }
    }
}