import org.junit.Test
import tech.libeufin.util.EbicsProtocolError
import tech.libeufin.util.InvalidPaytoError
import tech.libeufin.util.parsePayto

class PaytoTest {

    @Test
    fun wrongCases() {
        try {
            parsePayto("payto://iban/IBAN/BIC")
        } catch (e: InvalidPaytoError) {
            println(e)
            println("must give IBAN _and_ BIC")
        }
        try {
            parsePayto("http://iban/BIC123/IBAN123?receiver-name=The%20Name")
        } catch (e: InvalidPaytoError) {
            println(e)
            println("wrong scheme was caught")
        }
        try {
            parsePayto(
                "payto:iban/BIC123/IBAN123?receiver-name=The%20Name&address=house"
            )
        } catch (e: InvalidPaytoError) {
            println(e)
            println("'://' missing, invalid Payto")
        }
        try {
            parsePayto("payto://iban/BIC123/IBAN123?sender-name=Foo&receiver-name=Foo")
        } catch (e: InvalidPaytoError) {
            println(e)
        }
        try {
            parsePayto("payto://wrong/BIC123/IBAN123?sender-name=Foo&receiver-name=Foo")
        } catch (e: InvalidPaytoError) {
            println(e)
        }
    }

    @Test
    fun parsePaytoTest() {
        val withBic = parsePayto("payto://iban/BIC123/IBAN123?receiver-name=The%20Name")
        assert(withBic.iban == "IBAN123")
        assert(withBic.bic == "BIC123")
        assert(withBic.name == "The Name")
        val complete = parsePayto("payto://iban/BIC123/IBAN123?sender-name=The%20Name&amount=EUR:1&message=donation")
        assert(withBic.iban == "IBAN123")
        assert(withBic.bic == "BIC123")
        assert(withBic.name == "The Name")
        assert(complete.message == "donation")
        assert(complete.amount == "EUR:1")
        val withoutOptionals = parsePayto(
            "payto://iban/IBAN123"
        )
        assert(withoutOptionals.bic == null)
        assert(withoutOptionals.message == null)
        assert(withoutOptionals.name == null)
        assert(withoutOptionals.amount == null)
    }
}