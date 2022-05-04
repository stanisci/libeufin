import org.junit.Test
import tech.libeufin.util.parseAmountAsString
import kotlin.reflect.typeOf

class AmountTest {
    @Test
    fun parse() {
        val resWithCurrency = parseAmountAsString("CURRENCY:5.5")
        assert(resWithCurrency.first == "5.5")
        assert(resWithCurrency.second == "CURRENCY")
        val resWithoutCurrency = parseAmountAsString("5.5")
        assert(resWithoutCurrency.first == "5.5")
        assert(resWithoutCurrency.second == null)
    }
}