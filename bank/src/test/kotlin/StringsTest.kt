import org.junit.Test
import tech.libeufin.util.hasWopidPlaceholder
import tech.libeufin.util.validateBic

class StringsTest {

    @Test
    fun hasWopidTest() {
        assert(hasWopidPlaceholder("http://example.com/#/{wopid}"))
        assert(!hasWopidPlaceholder("http://example.com"))
        assert(hasWopidPlaceholder("http://example.com/#/{WOPID}"))
        assert(!hasWopidPlaceholder("{ W O P I D }"))
    }

    @Test
    fun replaceWopidPlaceholderTest() {
        assert(
            "http://example.com/#/operation/{wopid}".replace("{wopid}", "987")
            == "http://example.com/#/operation/987"
        )
        assert("http://example.com".replace("{wopid}", "not-replaced")
                == "http://example.com"
        )
    }

    @Test
    fun bicTest() {
        assert(validateBic("GENODEM1GLS"))
        assert(validateBic("AUTOATW1XXX"))
    }

    @Test
    fun booleanToString() {
        assert(true.toString() == "true")
        assert(false.toString() == "false")
    }
}