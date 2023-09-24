import org.junit.Test
import tech.libeufin.util.IbanPayto
import tech.libeufin.util.parsePayto

class PaytoTest {

    @Test
    fun wrongCases() {
        assert(parsePayto("http://iban/BIC123/IBAN123?receiver-name=The%20Name") == null)
        assert(parsePayto("payto:iban/BIC123/IBAN123?receiver-name=The%20Name&address=house") == null)
        assert(parsePayto("payto://wrong/BIC123/IBAN123?sender-name=Foo&receiver-name=Foo") == null)
    }

    @Test
    fun parsePaytoTest() {
        val withBic: IbanPayto = parsePayto("payto://iban/BIC123/IBAN123?receiver-name=The%20Name")!!
        assert(withBic.iban == "IBAN123")
        assert(withBic.bic == "BIC123")
        assert(withBic.receiverName == "The Name")
        val complete = parsePayto("payto://iban/BIC123/IBAN123?sender-name=The%20Name&amount=EUR:1&message=donation")!!
        assert(withBic.iban == "IBAN123")
        assert(withBic.bic == "BIC123")
        assert(withBic.receiverName == "The Name")
        assert(complete.message == "donation")
        assert(complete.amount == "EUR:1")
        val withoutOptionals = parsePayto("payto://iban/IBAN123")!!
        assert(withoutOptionals.bic == null)
        assert(withoutOptionals.message == null)
        assert(withoutOptionals.receiverName == null)
        assert(withoutOptionals.amount == null)
    }
}