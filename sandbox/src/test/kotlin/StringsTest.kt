import org.junit.Test
import tech.libeufin.util.validateBic

class StringsTest {

    @Test
    fun bicTest() {
        assert(validateBic("GENODEM1GLS"))
        assert(validateBic("AUTOATW1XXX"))
    }

    @Test
    fun replaceLinks() {
        val spa = ClassLoader.getSystemClassLoader().getResourceAsStream("static/spa.html")
        var content = String(spa.readBytes(), Charsets.UTF_8)
        content = content.replace(
            "%DEMO_SITE_SURVEY_URL%",
            "foo"
        )
        println(content)
    }
}