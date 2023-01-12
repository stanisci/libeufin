import org.junit.Ignore
import org.junit.Test
import tech.libeufin.util.getNow
import tech.libeufin.util.setClock
import java.time.*
import java.time.format.DateTimeFormatter

// https://stackoverflow.com/questions/32437550/whats-the-difference-between-instant-and-localdatetime

// Ignoring because no assert takes place here.
@Ignore
class TimeTest {
    @Test
    fun mock() {
        println(getNow())
        setClock(Duration.ofHours(2))
        println(getNow())
    }

    @Test
    fun importMillis() {
        fun fromLong(millis: Long): LocalDateTime {
            return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(millis),
                ZoneId.systemDefault()
            )
        }
        val ret = fromLong(0)
        println(ret.toString())
    }

    @Test
    fun printLong() {
        val l = 1111111L
        println(l.javaClass)
        println(l.toString())
    }

    @Test
    fun formatDateTime() {
        fun formatDashed(dateTime: LocalDateTime): String {
            val dtf = DateTimeFormatter.ISO_LOCAL_DATE
            return dtf.format(dateTime)
        }
        fun formatZonedWithOffset(dateTime: ZonedDateTime): String {
            val dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            return dtf.format(dateTime)
        }
        val str = formatDashed(LocalDateTime.now())
        println(str)
        val str0 = formatZonedWithOffset(LocalDateTime.now().atZone(ZoneId.systemDefault()))
        println(str0)
    }

    @Test
    fun parseDashedDate() {
        fun parse(dashedDate: String): LocalDate {
            val dtf = DateTimeFormatter.ISO_LOCAL_DATE
            return LocalDate.parse(dashedDate, dtf)
        }
        val ret = parse("1970-01-01")
        println(ret.toString())
    }
}