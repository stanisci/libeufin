import org.junit.Test
import org.junit.jupiter.api.assertThrows
import tech.libeufin.nexus.EbicsSetupConfig
import tech.libeufin.nexus.NEXUS_CONFIG_SOURCE
import tech.libeufin.nexus.getFrequencyInSeconds
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigLoading {
    /**
     * Tests that the default configuration has _at least_ the options
     * that are expected by the memory representation of config.
     */
    @Test
    fun loadRequiredValues() {
        val handle = TalerConfig(NEXUS_CONFIG_SOURCE)
        handle.load()
        val cfg = EbicsSetupConfig(handle)
        cfg._dump()
    }

    @Test
    fun loadPath() {
        val handle = TalerConfig(NEXUS_CONFIG_SOURCE)
        handle.load()
        val cfg = EbicsSetupConfig(handle)
        cfg.config.requirePath("nexus-ebics-fetch", "statement_log_directory")
    }


    /**
     * Tests that if the configuration lacks at least one option, then
     * the config loader throws exception.
     */
    @Test
    fun detectMissingValues() {
        val handle = TalerConfig(NEXUS_CONFIG_SOURCE)
        handle.loadFromString("""
            [ebics-nexus]
            # All the other defaults won't be loaded.
            BANK_DIALECT = postfinance
        """.trimIndent())
        assertThrows<TalerConfigError> {
            EbicsSetupConfig(handle)
        }
    }

    // Checks converting human-readable durations to seconds.
    @Test
    fun timeParsing() {
        assertEquals(1, getFrequencyInSeconds("1s"))
        assertEquals(1, getFrequencyInSeconds("       1           s       "))
        assertEquals(10*60, getFrequencyInSeconds("10m"))
        assertEquals(10*60, getFrequencyInSeconds("10 m"))
        assertEquals(24*60*60, getFrequencyInSeconds("24h"))
        assertEquals(24*60*60, getFrequencyInSeconds(" 24h"))
        assertEquals(60*60, getFrequencyInSeconds("      1h      "))
        assertEquals(60*60, getFrequencyInSeconds("01h"))
        assertNull(getFrequencyInSeconds("1.1s"))
        assertNull(getFrequencyInSeconds("         "))
        assertNull(getFrequencyInSeconds("m"))
        assertNull(getFrequencyInSeconds(""))
    }
}