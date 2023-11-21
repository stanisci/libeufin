import org.junit.Test
import org.junit.jupiter.api.assertThrows
import tech.libeufin.nexus.*
import tech.libeufin.util.parseBookDate
import tech.libeufin.util.parseCamtTime
import java.lang.StringBuilder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Parsing {

    @Test // move eventually to util (#7987)
    fun amountComparison() {
        val one = TalerAmount(1, 0, "KUDOS")
        val two = TalerAmount(2, 0, "KUDOS")
        val moreFrac = TalerAmount(2, 4, "KUDOS")
        val lessFrac = TalerAmount(2, 3, "KUDOS")
        val zeroMoreFrac = TalerAmount(0, 4, "KUDOS")
        val zeroLessFrac = TalerAmount(0, 3, "KUDOS")
        assertTrue(firstLessThanSecond(one, two))
        assertTrue(firstLessThanSecond(lessFrac, moreFrac))
        assertTrue(firstLessThanSecond(zeroLessFrac, zeroMoreFrac))
    }
    @Test
    fun gregorianTime() {
        parseCamtTime("2023-11-06T20:00:00")
        assertThrows<Exception> { parseCamtTime("2023-11-06T20:00:00+01:00") }
        assertThrows<Exception> { parseCamtTime("2023-11-06T20:00:00Z") }
    }

    @Test
    fun bookDateTest() {
        parseBookDate("1970-01-01")
        assertThrows<Exception> { parseBookDate("1970-01-01T00:00:01Z") }
    }

    @Test
    fun reservePublicKey() {
        assertNull(removeSubjectNoise("does not contain any reserve"))
        // 4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0
        assertNotNull(removeSubjectNoise("4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"))
        assertEquals(
            "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0",
            removeSubjectNoise(
                "noise 4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0 noise"
            )
        )
        assertEquals(
            "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0",
            removeSubjectNoise(
                "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0 noise to the right"
            )
        )
        assertEquals(
            "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0",
            removeSubjectNoise(
                "noise to the left 4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"
            )
        )
        assertEquals(
            "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0",
            removeSubjectNoise(
                "    4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0     "
            )
        )
        assertEquals(
            "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0",
            removeSubjectNoise("""
                noise
                4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0
                noise
            """)
        )
        // Got the first char removed.
        assertNull(removeSubjectNoise("MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"))
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
            getTalerAmount("0.123", "KUDOS")
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