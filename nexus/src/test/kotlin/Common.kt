import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import tech.libeufin.nexus.*
import tech.libeufin.util.DatabaseConfig
import tech.libeufin.util.initializeDatabaseTables
import tech.libeufin.util.resetDatabaseTables
import java.security.interfaces.RSAPrivateCrtKey
import java.time.Instant

val j = Json {
    this.serializersModule = SerializersModule {
        contextual(RSAPrivateCrtKey::class) { RSAPrivateCrtKeySerializer }
    }
}

val config: EbicsSetupConfig = run {
    val handle = TalerConfig(NEXUS_CONFIG_SOURCE)
    handle.load()
    EbicsSetupConfig(handle)
}

fun prepDb(cfg: TalerConfig): Database {
    cfg.loadDefaults()
    val dbCfg = DatabaseConfig(
        dbConnStr = "postgresql:///libeufincheck",
        sqlDir = cfg.requirePath("paths", "datadir") + "sql"
    )
    println("SQL dir for testing: ${dbCfg.sqlDir}")
    try {
        resetDatabaseTables(dbCfg, "libeufin-nexus")
    } catch (e: Exception) {
        logger.warn("Resetting an empty database throws, tolerating this...")
        logger.warn(e.message)
    }
    initializeDatabaseTables(dbCfg, "libeufin-nexus")
    return Database(dbCfg.dbConnStr)
}

val clientKeys = generateNewKeys()

// Gets an HTTP client whose requests are going to be served by 'handler'.
fun getMockedClient(
    handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
): HttpClient {
    return HttpClient(MockEngine) {
        followRedirects = false
        engine {
            addHandler {
                    request -> handler(request)
            }
        }
    }
}

// Partial config to talk to PostFinance.
fun getPofiConfig(userId: String, partnerId: String) = """
    [nexus-ebics]
    CURRENCY = KUDOS
    HOST_BASE_URL = https://isotest.postfinance.ch/ebicsweb/ebicsweb
    HOST_ID = PFEBICS
    USER_ID = $userId
    PARTNER_ID = $partnerId
    SYSTEM_ID = not-used
    ACCOUNT_NUMBER = not-used-yet
    BANK_PUBLIC_KEYS_FILE = /tmp/enc-auth-keys.json
    CLIENT_PRIVATE_KEYS_FILE = /tmp/my-private-keys.json
    ACCOUNT_META_DATA_FILE = /tmp/ebics-meta.json
    BANK_DIALECT = postfinance
""".trimIndent()

// Generates a payment initiation, given its subject.
fun genInitPay(subject: String? = null, rowUuid: String? = null) =
    InitiatedPayment(
        amount = TalerAmount(44, 0, "KUDOS"),
        creditPaytoUri = "payto://iban/not-used",
        wireTransferSubject = subject,
        initiationTime = Instant.now(),
        clientRequestUuid = rowUuid
    )

// Generates an incoming payment, given its subject.
fun genIncPay(subject: String? = null, rowUuid: String? = null) =
    IncomingPayment(
        amount = TalerAmount(44, 0, "KUDOS"),
        debitPaytoUri = "payto://iban/not-used",
        wireTransferSubject = subject,
        executionTime = Instant.now(),
        bounced = false,
        bankTransferId = "entropic"
    )

// Generates an outgoing payment, given its subject.
fun genOutPay(subject: String? = null) =
    OutgoingPayment(
        amount = TalerAmount(44, 0, "KUDOS"),
        creditPaytoUri = "payto://iban/not-used",
        wireTransferSubject = subject,
        executionTime = Instant.now(),
        bankTransferId = "entropic"
    )