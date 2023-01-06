import org.junit.Test
import tech.libeufin.util.parseAmount
import tech.libeufin.util.validatePlainAmount

inline fun <reified ExceptionType> assertException(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        assert(e.javaClass == ExceptionType::class.java)
        return
    }
    return assert(false)
}
class AmountTest {
    @Test
    fun parse() {
        var res = parseAmount("KUDOS:5.5")
        assert(res.amount == "5.5")
        assert(res.currency == "KUDOS")
        assert(validatePlainAmount("1.0"))
        assert(validatePlainAmount("1.00"))
        assert(!validatePlainAmount("1.000"))
        res = parseAmount("TESTKUDOS:1.11")
        assert(res.amount == "1.11")
        assert(res.currency == "TESTKUDOS")
        assertException<UtilError> { parseAmount("TESTKUDOS:1.") }
        assertException<UtilError> { parseAmount("TESTKUDOS:.1") }
        assertException<UtilError> { parseAmount("TESTKUDOS:1.000") }
        assertException<UtilError> { parseAmount("TESTKUDOS:1..") }
    }
}