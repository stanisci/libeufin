import org.junit.Test
import tech.libeufin.util.maxTimestamp
import tech.libeufin.util.minTimestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeTest {
    @Test
    fun cmp() {
        val now = Instant.now()
        val inOneMinute = now.plus(1, ChronoUnit.MINUTES)

        // testing the "min" function
        assertNull(minTimestamp(null, null))
        assertEquals(now, minTimestamp(now, inOneMinute))
        assertNull(minTimestamp(now, null))
        assertNull(minTimestamp(null, now))
        assertEquals(inOneMinute, minTimestamp(inOneMinute, inOneMinute))

        // testing the "max" function
        assertNull(maxTimestamp(null, null))
        assertEquals(inOneMinute, maxTimestamp(now, inOneMinute))
        assertEquals(now, maxTimestamp(now, null))
        assertEquals(now, maxTimestamp(null, now))
        assertEquals(now, minTimestamp(now, now))
    }
}