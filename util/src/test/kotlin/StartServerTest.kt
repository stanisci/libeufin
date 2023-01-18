import org.junit.Ignore
import org.junit.Test
import tech.libeufin.nexus.server.nexusApp
import tech.libeufin.sandbox.sandboxApp
import tech.libeufin.util.StartServerOptions
import tech.libeufin.util.startServerWithIPv4Fallback

@Ignore
class StartServerTest {
    @Test
    fun sandboxStart() {
        startServerWithIPv4Fallback(
            options = StartServerOptions(
                ipv4OnlyOpt = false,
                localhostOnlyOpt = false,
                portOpt = 5000
            ),
            app = sandboxApp
        )
    }
    @Test
    fun nexusStart() {
        startServerWithIPv4Fallback(
            options = StartServerOptions(
                ipv4OnlyOpt = false,
                localhostOnlyOpt = true,
                portOpt = 5000
            ),
            app = nexusApp
        )
    }
}