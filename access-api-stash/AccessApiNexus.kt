package tech.libeufin.nexus.`access-api`

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.*
import tech.libeufin.nexus.server.AccessApiNewTransport
import tech.libeufin.nexus.server.EbicsNewTransport
import tech.libeufin.nexus.server.FetchSpecJson
import tech.libeufin.nexus.server.client
import tech.libeufin.util.*
import java.net.URL
import java.nio.charset.Charset

private fun getAccessApiClient(connId: String): AccessApiClientEntity {
    val conn = NexusBankConnectionEntity.find {
        NexusBankConnectionsTable.connectionId eq connId
    }.firstOrNull() ?: throw notFound("Connection '$connId' not found.")
    val client = AccessApiClientEntity.find {
        AccessApiClientsTable.nexusBankConnection eq conn.id.value
    }.firstOrNull() ?: throw notFound("Connection '$connId' has no client data.")
    return client
}

suspend fun HttpClient.accessApiReq(
    method: HttpMethod,
    url: String,
    body: Any? = null,
    // username, password
    credentials: Pair<String, String>): String? {

    val reqBuilder: HttpRequestBuilder.() -> Unit = {
        contentType(ContentType.Application.Json)
        if (body != null)
            this.body = body

        headers.apply {
            this.set(
                "Authorization",
                "Basic " + bytesToBase64("${credentials.first}:${credentials.second}".toByteArray(Charsets.UTF_8))
            )
        }
    }
    return try {
        when(method) {
            HttpMethod.Get -> {
                this.get(url, reqBuilder)
            }
            HttpMethod.Post -> {
                this.post(url, reqBuilder)
            }
            else -> throw internalServerError("Method $method not supported.")
        }
    } catch (e: ClientRequestException) {
        logger.error(e.message)
        throw NexusError(
            HttpStatusCode.BadGateway,
            e.message
        )
    }
}

/**
 * Talk to the Sandbox via native Access API.  The main reason
 * for this class was to still allow x-taler-bank as a wire method
 * (to accommodate wallet harness tests), and therefore skip the
 * Camt+Pain schemas.
 */
class JsonBankConnectionProtocol: BankConnectionProtocol {

    override fun createConnection(connId: String, user: NexusUserEntity, data: JsonNode) {
        val bankConn = NexusBankConnectionEntity.new {
            this.connectionId = connId
            owner = user
            type = "access-api"
        }
        val newTransportData = jacksonObjectMapper(
        ).treeToValue(data, AccessApiNewTransport::class.java) ?: throw NexusError(
            HttpStatusCode.BadRequest, "Access Api details not found in request"
        )
        AccessApiClientEntity.new {
            username = newTransportData.username
            bankURL = newTransportData.bankURL
            remoteBankAccountLabel = newTransportData.remoteBankAccountLabel
            nexusBankConnection = bankConn
            password = newTransportData.password
        }
    }

    override fun getConnectionDetails(conn: NexusBankConnectionEntity): JsonNode {
        val details = transaction { getAccessApiClient(conn.connectionId) }
        val ret = ObjectMapper().createObjectNode()
        ret.put("username", details.username)
        ret.put("bankURL", details.bankURL)
        ret.put("passwordHash", CryptoUtil.hashpw(details.password))
        ret.put("remoteBankAccountLabel", details.remoteBankAccountLabel)
        return ret
    }

    override suspend fun submitPaymentInitiation(
        httpClient: HttpClient,
        paymentInitiationId: Long // must refer to an x-taler-bank payto://-instruction.
    ) {
        val payInit = XTalerBankPaymentInitiationEntity.findById(paymentInitiationId) ?: throw notFound(
            "Payment initiation '$paymentInitiationId' not found."
        )
        val conn = payInit.defaultBankConnection ?: throw notFound(
            "No default bank connection for payment initiation '${paymentInitiationId}' was found."
        )
        val details = getAccessApiClient(conn.connectionId)

        client.accessApiReq(
            method = HttpMethod.Post,
            url = urlJoinNoDrop(
                details.bankURL,
                "accounts/${details.remoteBankAccountLabel}/transactions"
            ),
            body = object {
                val paytoUri = payInit.paytoUri
                val amount = payInit.amount
                val subject = payInit.subject
            },
            credentials = Pair(details.username, details.password)
        )
    }
    /**
     * This function gets always the fresh transactions from
     * the bank.  Any other Wire Gateway API policies will be
     * implemented by the respective facade (XTalerBank.kt) */
    override suspend fun fetchTransactions(
        fetchSpec: FetchSpecJson,
        client: HttpClient,
        bankConnectionId: String,
        /**
         * Label of the local bank account that mirrors
         * the remote bank account pointed to by 'bankConnectionId' */
        accountId: String
    ) {
        val details = getAccessApiClient(bankConnectionId)
        val txsRaw = client.accessApiReq(
            method = HttpMethod.Get,
            url = urlJoinNoDrop(
                details.bankURL,
                "accounts/${details.remoteBankAccountLabel}/transactions"
            ),
            credentials = Pair(details.username, details.password)
        )
        // What format does Access API communicates the records in?
        /**
         * NexusXTalerBankTransactions.new {
         *
         *     .. details ..
         * }
         */
    }

    override fun exportBackup(bankConnectionId: String, passphrase: String): JsonNode {
        throw NexusError(
            HttpStatusCode.NotImplemented,
            "Operation not needed."
        )
    }

    override fun exportAnalogDetails(conn: NexusBankConnectionEntity): ByteArray {
        throw NexusError(
            HttpStatusCode.NotImplemented,
            "Operation not needed."
        )
    }

    override suspend fun fetchAccounts(client: HttpClient, connId: String) {
        throw NexusError(
            HttpStatusCode.NotImplemented,
            "access-api connections assume that remote and local bank" +
                    " accounts are called the same.  No need to 'fetch'"
        )
    }

    override fun createConnectionFromBackup(
        connId: String,
        user: NexusUserEntity,
        passphrase: String?,
        backup: JsonNode
    ) {
        throw NexusError(
            HttpStatusCode.NotImplemented,
            "Operation not needed."
        )
    }

    override suspend fun connect(client: HttpClient, connId: String) {
        /**
         * Future versions might create a bank account at this step.
         * Right now, all the tests do create those accounts beforehand.
         */
        throw NexusError(
            HttpStatusCode.NotImplemented,
            "Operation not needed."
        )
    }
}

