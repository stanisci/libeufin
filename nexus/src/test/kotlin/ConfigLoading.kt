import org.junit.Test
import org.junit.jupiter.api.assertThrows
import tech.libeufin.nexus.EbicsSetupConfig
import tech.libeufin.nexus.NEXUS_CONFIG_SOURCE

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
}