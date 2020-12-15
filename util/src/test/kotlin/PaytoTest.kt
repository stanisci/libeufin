import org.junit.Test
import tech.libeufin.util.EbicsProtocolError
import tech.libeufin.util.InvalidPaytoError
import tech.libeufin.util.parsePayto

class PaytoTest {

    @Test
    fun wrongCases() {
        try {
            parsePayto("http://iban/BIC123/IBAN123?receiver-name=The%20Name")
        } catch (e: InvalidPaytoError) {
            println(e)
            println("wrong scheme was caught")
        }
        try {
            parsePayto(
                "payto://iban/BIC123/IBAN123?receiver-name=The%20Name&address=house"
            )
        } catch (e: InvalidPaytoError) {
            println(e)
            println("more than one parameter isn't allowed")
        }
        try {
            parsePayto(
                "payto:iban/BIC123/IBAN123?receiver-name=The%20Name&address=house"
            )
        } catch (e: InvalidPaytoError) {
            println(e)
            println("'://' missing, invalid Payto")
        }
    }

    @Test
    fun parsePaytoTest() {
        val withBic = parsePayto("payto://iban/BIC123/IBAN123?receiver-name=The%20Name")
        assert(withBic.iban == "IBAN123")
        assert(withBic.bic == "BIC123")
        assert(withBic.name == "The Name")
    }
}