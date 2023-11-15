import org.junit.Test
import org.junit.jupiter.api.assertThrows
import tech.libeufin.nexus.TalerAmount
import tech.libeufin.nexus.getAmountNoCurrency
import tech.libeufin.nexus.getTalerAmount
import tech.libeufin.nexus.isReservePub
import tech.libeufin.util.parseCamtTime
import java.lang.StringBuilder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Parsing {

    @Test
    fun gregorianTime() {
        parseCamtTime("2023-11-06T20:00:00")
        assertThrows<Exception> { parseCamtTime("2023-11-06T20:00:00+01:00") }
        assertThrows<Exception> { parseCamtTime("2023-11-06T20:00:00Z") }
    }
    @Test // Could be moved in a dedicated Amounts.kt test module.
    fun generateCurrencyAgnosticAmount() {
        assertThrows<Exception> {
            // Too many fractional digits.
            getAmountNoCurrency(TalerAmount(1, 123456789, "KUDOS"))
        }
        assertThrows<Exception> {
            // Nexus doesn't support sub-cents.
            getAmountNoCurrency(TalerAmount(1, 12345678, "KUDOS"))
        }
        assertThrows<Exception> {
            // Nexus doesn't support sub-cents.
            getAmountNoCurrency(TalerAmount(0, 1, "KUDOS"))
        }
        assertEquals(
            "0.01",
            getAmountNoCurrency(TalerAmount(0, 1000000, "KUDOS"))
        )
        assertEquals(
            "0.1",
            getAmountNoCurrency(TalerAmount(0, 10000000, "KUDOS"))
        )
    }
    @Test // parses amounts as found in the camt.05x documents.
    fun parseCurrencyAgnosticAmount() {
        assertTrue {
            getTalerAmount("1.00", "KUDOS").run {
                this.value == 1L && this.fraction == 0 && this.currency == "KUDOS"
            }
        }
        assertTrue {
            getTalerAmount("1", "KUDOS").run {
                this.value == 1L && this.fraction == 0 && this.currency == "KUDOS"
            }
        }
        assertTrue {
            getTalerAmount("0.99", "KUDOS").run {
                this.value == 0L && this.fraction == 99000000 && this.currency == "KUDOS"
            }
        }
        assertTrue {
            getTalerAmount("0.01", "KUDOS").run {
                this.value == 0L && this.fraction == 1000000 && this.currency == "KUDOS"
            }
        }
        assertThrows<Exception> {
            getTalerAmount("", "")
        }
        assertThrows<Exception> {
            getTalerAmount(".1", "KUDOS")
        }
        assertThrows<Exception> {
            getTalerAmount("1.", "KUDOS")
        }
        assertThrows<Exception> {
            getTalerAmount("0.123456789", "KUDOS")
        }
        assertThrows<Exception> {
            getTalerAmount("noise", "KUDOS")
        }
        assertThrows<Exception> {
            getTalerAmount("1.noise", "KUDOS")
        }
        assertThrows<Exception> {
            getTalerAmount("5", "")
        }
    }

    // Checks that the input decodes to a 32-bytes value.
    @Test
    fun validateReservePub() {
        val valid = "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"
        val validBytes = isReservePub(valid)
        assertNotNull(validBytes)
        assertEquals(32, validBytes.size)
        assertNull(isReservePub("noise"))
        val trimmedInput = valid.dropLast(10)
        assertNull(isReservePub(trimmedInput))
        val invalidChar = StringBuilder(valid)
        invalidChar.setCharAt(10, '*')
        assertNull(isReservePub(invalidChar.toString()))
        // assertNull(isReservePub(valid.dropLast(1))) // FIXME: this fails now because the decoder is buggy.
    }
}