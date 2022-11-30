import org.junit.Test
import tech.libeufin.util.validateBic

class StringsTest {

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