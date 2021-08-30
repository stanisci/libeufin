import org.junit.Test
import tech.libeufin.util.validateBic

class StringsTest {

    @Test
    fun bicTest() {
        assert(validateBic("GENODEM1GLS"))
        assert(validateBic("AUTOATW1XXX"))
    }
}