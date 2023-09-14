import org.junit.Test
import tech.libeufin.bank.FracDigits
import tech.libeufin.bank.parseTalerAmount

class AmountTest {
    @Test
    fun parseTalerAmountTest() {
        parseTalerAmount("KUDOS:11")
        val one = "EUR:1"
        var obj = parseTalerAmount(one)
        assert(obj.value == 1L && obj.frac == 0)
        val onePointZero = "EUR:1.00"
        obj = parseTalerAmount(onePointZero)
        assert(obj.value == 1L && obj.frac == 0)
        val onePointZeroOne = "EUR:1.01"
        obj = parseTalerAmount(onePointZeroOne)
        assert(obj.value == 1L && obj.frac == 1000000)
        obj = parseTalerAmount("EUR:0.00000001")
        assert(obj.value == 0L && obj.frac == 1)
        // Setting two fractional digits.
        obj = parseTalerAmount("EUR:0.01", FracDigits.TWO) // one cent
        assert(obj.value == 0L && obj.frac == 1000000)
        obj = parseTalerAmount("EUR:0.1", FracDigits.TWO) // ten cents
        assert(obj.value == 0L && obj.frac == 10000000)
    }
}