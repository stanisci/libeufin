import org.junit.Test
import tech.libeufin.util.getIban

class IbanTest {

    @Test
    fun genIban() {
        println(getIban())
    }
}