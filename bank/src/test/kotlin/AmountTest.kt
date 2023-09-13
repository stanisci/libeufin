import org.junit.Test
import tech.libeufin.bank.parseTalerAmount

class AmountTest {
    @Test
    fun parseTalerAmountTest() {
        val one = "EUR:1"
        var obj = parseTalerAmount(one)
        assert(obj.value == 1L && obj.frac == 0)
        val onePointZero = "EUR:1.00"
        obj = parseTalerAmount(onePointZero)
        assert(obj.value == 1L && obj.frac == 0)
        val onePointZeroOne = "EUR:1.01"
        obj = parseTalerAmount(onePointZeroOne)
        assert(obj.value == 1L && obj.frac == 1000000)
        // Invalid tries
        obj = parseTalerAmount("EUR:0.00000001")
        assert(obj.value == 0L && obj.frac == 1)
    }
}