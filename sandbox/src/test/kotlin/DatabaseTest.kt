import org.junit.Test
import tech.libeufin.sandbox.Database
import tech.libeufin.util.execCommand

class DatabaseTest {
    fun initDb() {
        execCommand(
            listOf(
                "libeufin-bank-dbinit",
                "-d",
                "libeufincheck",
                "-r"
            ),
            throwIfFails = true
        )
    }
    @Test
    fun configTest() {
        initDb()
        val db = Database("jdbc:postgresql:///libeufincheck")
        assert(db.configGet("bar") == null)
        assert(db.configGet("bar") == null)
        db.configSet("foo", "bar")
        assert(db.configGet("foo") == "bar")
    }
}