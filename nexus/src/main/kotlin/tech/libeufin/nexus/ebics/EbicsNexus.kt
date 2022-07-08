/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

/**
 * Handlers for EBICS-related endpoints offered by the nexus for EBICS
 * connections.
 */
package tech.libeufin.nexus.ebics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Paragraph
import io.ktor.application.call
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receiveOrNull
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.post
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.*
import tech.libeufin.nexus.iso20022.NexusPaymentInitiationData
import tech.libeufin.nexus.iso20022.createPain001document
import tech.libeufin.nexus.logger
import tech.libeufin.nexus.server.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.EbicsTypes
import tech.libeufin.util.ebics_h004.HTDResponseOrderData
import java.io.ByteArrayOutputStream
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.crypto.EncryptedPrivateKeyInfo


private data class EbicsFetchSpec(
    val orderType: String,
    val orderParams: EbicsOrderParams
)

fun storeCamt(bankConnectionId: String, camt: String, historyType: String) {
    val camt53doc = XMLUtil.parseStringIntoDom(camt)
    val msgId = camt53doc.pickStringWithRootNs("/*[1]/*[1]/root:GrpHdr/root:MsgId")
    logger.info("msg id $msgId")
    transaction {
        val conn = NexusBankConnectionEntity.findByName(bankConnectionId)
        if (conn == null) {
            throw NexusError(HttpStatusCode.InternalServerError, "bank connection missing")
        }
        val oldMsg = NexusBankMessageEntity.find { NexusBankMessagesTable.messageId eq msgId }.firstOrNull()
        if (oldMsg == null) {
            NexusBankMessageEntity.new {
                this.bankConnection = conn
                this.code = historyType
                this.messageId = msgId
                this.message = ExposedBlob(camt.toByteArray(Charsets.UTF_8))
            }
        }
    }

}

/**
 * Fetch EBICS C5x and store it locally, but do not update bank accounts.
 */
private suspend fun fetchEbicsC5x(
    historyType: String,
    client: HttpClient,
    bankConnectionId: String,
    orderParams: EbicsOrderParams,
    subscriberDetails: EbicsClientSubscriberDetails
) {
    logger.debug("Requesting $historyType")
    val response = try {
        doEbicsDownloadTransaction(
            client,
            subscriberDetails,
            historyType,
            orderParams
        )
    } catch (e: EbicsProtocolError) {
        /**
         * This error type is not an actual error in this handler.
         */
        if (e.ebicsTechnicalCode == EbicsReturnCode.EBICS_NO_DOWNLOAD_DATA_AVAILABLE) {
            logger.info("Could not find new transactions to download")
            return
        }
        // re-throw in any other error case.
        throw e
    }

    when (historyType) {
        "C52" -> {
        }
        "C53" -> {
        }
        else -> {
            throw NexusError(HttpStatusCode.BadRequest, "history type '$historyType' not supported")
        }
    }
    when (response) {
        is EbicsDownloadSuccessResult -> {
            response.orderData.unzipWithLambda {
                // logger.debug("Camt entry (filename (in the Zip archive): ${it.first}): ${it.second}")
                storeCamt(bankConnectionId, it.second, historyType)
            }
        }
        is EbicsDownloadBankErrorResult -> {
            throw NexusError(
                HttpStatusCode.BadGateway,
                response.returnCode.errorCode
            )
        }
    }
}

private fun getEbicsSubscriberDetailsInternal(subscriber: EbicsSubscriberEntity): EbicsClientSubscriberDetails {
    var bankAuthPubValue: RSAPublicKey? = null
    if (subscriber.bankAuthenticationPublicKey != null) {
        bankAuthPubValue = CryptoUtil.loadRsaPublicKey(
            subscriber.bankAuthenticationPublicKey?.bytes!!
        )
    }
    var bankEncPubValue: RSAPublicKey? = null
    if (subscriber.bankEncryptionPublicKey != null) {
        bankEncPubValue = CryptoUtil.loadRsaPublicKey(
            subscriber.bankEncryptionPublicKey?.bytes!!
        )
    }
    return EbicsClientSubscriberDetails(
        bankAuthPub = bankAuthPubValue,
        bankEncPub = bankEncPubValue,

        ebicsUrl = subscriber.ebicsURL,
        hostId = subscriber.hostID,
        userId = subscriber.userID,
        partnerId = subscriber.partnerID,

        customerSignPriv = CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.bytes),
        customerAuthPriv = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.bytes),
        customerEncPriv = CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.bytes),
        ebicsIniState = subscriber.ebicsIniState,
        ebicsHiaState = subscriber.ebicsHiaState
    )
}

/**
 * Retrieve Ebics subscriber details given a bank connection.
 */
private fun getEbicsSubscriberDetails(bankConnectionId: String): EbicsClientSubscriberDetails {
    val transport = NexusBankConnectionEntity.findByName(bankConnectionId)
    if (transport == null) {
        throw NexusError(HttpStatusCode.NotFound, "transport not found")
    }
    val subscriber = EbicsSubscriberEntity.find {
        NexusEbicsSubscribersTable.nexusBankConnection eq transport.id
    }.first()
    // transport exists and belongs to caller.
    return getEbicsSubscriberDetailsInternal(subscriber)
}

fun Route.ebicsBankProtocolRoutes(client: HttpClient) {
    post("test-host") {
        val r = call.receiveJson<EbicsHostTestRequest>()
        val qr = doEbicsHostVersionQuery(client, r.ebicsBaseUrl, r.ebicsHostId)
        call.respond(qr)
        return@post
    }
}


fun Route.ebicsBankConnectionRoutes(client: HttpClient) {
    post("/send-ini") {
        val subscriber = transaction {
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(
                    HttpStatusCode.BadRequest,
                    "bank connection is not of type 'ebics' (but '${conn.type}')"
                )
            }
            getEbicsSubscriberDetails(conn.connectionId)
        }
        val resp = doEbicsIniRequest(client, subscriber)
        call.respond(resp)
    }

    post("/send-hia") {
        val subscriber = transaction {
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.connectionId)
        }
        val resp = doEbicsHiaRequest(client, subscriber)
        call.respond(resp)
    }

    post("/send-hev") {
        val subscriber = transaction {
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.connectionId)
        }
        val resp = doEbicsHostVersionQuery(client, subscriber.ebicsUrl, subscriber.hostId)
        call.respond(resp)
    }

    post("/send-hpb") {
        val subscriberDetails = transaction {
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.connectionId)
        }
        val hpbData = doEbicsHpbRequest(client, subscriberDetails)
        transaction {
            val conn = requireBankConnection(call, "connid")
            val subscriber =
                EbicsSubscriberEntity.find { NexusEbicsSubscribersTable.nexusBankConnection eq conn.id }.first()
            subscriber.bankAuthenticationPublicKey = ExposedBlob((hpbData.authenticationPubKey.encoded))
            subscriber.bankEncryptionPublicKey = ExposedBlob((hpbData.encryptionPubKey.encoded))
        }
        call.respond(object {})
    }

    /**
     * Directly import accounts.  Used for testing.
     */
    post("/import-accounts") {
        val subscriberDetails = transaction {
            authenticateRequest(call.request)
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.connectionId)
        }
        val response = doEbicsDownloadTransaction(
            client, subscriberDetails, "HTD", EbicsStandardOrderParams()
        )
        when (response) {
            is EbicsDownloadBankErrorResult -> {
                throw NexusError(
                    HttpStatusCode.BadGateway,
                    response.returnCode.errorCode
                )
            }
            is EbicsDownloadSuccessResult -> {
                val payload = XMLUtil.convertStringToJaxb<HTDResponseOrderData>(
                    response.orderData.toString(Charsets.UTF_8)
                )
                transaction {
                    val conn = requireBankConnection(call, "connid")
                    payload.value.partnerInfo.accountInfoList?.forEach {
                        NexusBankAccountEntity.new {
                            bankAccountName = it.id
                            accountHolder = it.accountHolder ?: "NOT-GIVEN"
                            iban = it.accountNumberList?.filterIsInstance<EbicsTypes.GeneralAccountNumber>()
                                ?.find { it.international }?.value
                                ?: throw NexusError(HttpStatusCode.NotFound, reason = "bank gave no IBAN")
                            bankCode = it.bankCodeList?.filterIsInstance<EbicsTypes.GeneralBankCode>()
                                ?.find { it.international }?.value
                                ?: throw NexusError(
                                    HttpStatusCode.NotFound,
                                    reason = "bank gave no BIC"
                                )
                            defaultBankConnection = conn
                            highestSeenBankMessageSerialId = 0
                        }
                    }
                }
                response.orderData.toString(Charsets.UTF_8)
            }
        }
        call.respond(object {})
    }

    post("/download/{msgtype}") {
        val orderType = requireNotNull(call.parameters["msgtype"]).uppercase(Locale.ROOT)
        if (orderType.length != 3) {
            throw NexusError(HttpStatusCode.BadRequest, "ebics order type must be three characters")
        }
        val paramsJson = call.receiveOrNull<EbicsStandardOrderParamsDateJson>()
        val orderParams = if (paramsJson == null) {
            EbicsStandardOrderParams()
        } else {
            paramsJson.toOrderParams()
        }
        val subscriberDetails = transaction {
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.connectionId)
        }
        val response = doEbicsDownloadTransaction(
            client,
            subscriberDetails,
            orderType,
            orderParams
        )
        when (response) {
            is EbicsDownloadSuccessResult -> {
                call.respondText(
                    response.orderData.toString(Charsets.UTF_8),
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
            }
            is EbicsDownloadBankErrorResult -> {
                call.respond(
                    HttpStatusCode.BadGateway,
                    NexusErrorJson(
                        error = NexusErrorDetailJson(
                            type = "bank-error",
                            description = response.returnCode.errorCode
                        )
                    )
                )
            }
        }
    }
}

/**
 * Do the Hpb request when we don't know whether our keys have been submitted or not.
 *
 * Return true when the tentative HPB request succeeded, and thus key initialization is done.
 */
private suspend fun tentativeHpb(client: HttpClient, connId: String): Boolean {
    val subscriber = transaction { getEbicsSubscriberDetails(connId) }
    val hpbData = try {
        doEbicsHpbRequest(client, subscriber)
    } catch (e: EbicsProtocolError) {
        logger.info("failed tentative hpb request", e)
        return false
    }
    transaction {
        val conn = NexusBankConnectionEntity.findByName(connId)
        if (conn == null) {
            throw NexusError(HttpStatusCode.NotFound, "bank connection '$connId' not found")
        }
        val subscriberEntity =
            EbicsSubscriberEntity.find { NexusEbicsSubscribersTable.nexusBankConnection eq conn.id }.first()
        subscriberEntity.ebicsIniState = EbicsInitState.SENT
        subscriberEntity.ebicsHiaState = EbicsInitState.SENT
        subscriberEntity.bankAuthenticationPublicKey =
            ExposedBlob((hpbData.authenticationPubKey.encoded))
        subscriberEntity.bankEncryptionPublicKey = ExposedBlob((hpbData.encryptionPubKey.encoded))
    }
    return true
}

fun formatHex(ba: ByteArray): String {
    var out = ""
    for (i in ba.indices) {
        val b = ba[i]
        if (i > 0 && i % 16 == 0) {
            out += "\n"
        }
        out += java.lang.String.format("%02X", b)
        out += " "
    }
    return out
}

class EbicsBankConnectionProtocol: BankConnectionProtocol {
    override suspend fun fetchTransactions(
        fetchSpec: FetchSpecJson,
        client: HttpClient,
        bankConnectionId: String,
        accountId: String
    ) {
        val subscriberDetails = transaction { getEbicsSubscriberDetails(bankConnectionId) }
        val lastTimes = transaction {
            val acct = NexusBankAccountEntity.findByName(accountId)
            if (acct == null) {
                throw NexusError(
                    HttpStatusCode.NotFound,
                    "Account '$accountId' not found"
                )
            }
            object {
                val lastStatement = acct.lastStatementCreationTimestamp?.let {
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC)
                }
                val lastReport = acct.lastReportCreationTimestamp?.let {
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC)
                }
            }
        }
        val specs = mutableListOf<EbicsFetchSpec>()

        fun addForLevel(l: FetchLevel, p: EbicsOrderParams) {
            when (l) {
                FetchLevel.ALL -> {
                    specs.add(EbicsFetchSpec("C52", p))
                    specs.add(EbicsFetchSpec("C53", p))
                }
                FetchLevel.REPORT -> {
                    specs.add(EbicsFetchSpec("C52", p))
                }
                FetchLevel.STATEMENT -> {
                    specs.add(EbicsFetchSpec("C53", p))
                }
            }
        }

        when (fetchSpec) {
            is FetchSpecLatestJson -> {
                val p = EbicsStandardOrderParams()
                addForLevel(fetchSpec.level, p)
            }
            is FetchSpecAllJson -> {
                val p = EbicsStandardOrderParams(
                    EbicsDateRange(
                        ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
                        ZonedDateTime.now(ZoneOffset.UTC)
                    )
                )
                addForLevel(fetchSpec.level, p)
            }
            is FetchSpecSinceLastJson -> {
                val pRep = EbicsStandardOrderParams(
                    EbicsDateRange(
                        lastTimes.lastReport ?: ZonedDateTime.ofInstant(
                            Instant.EPOCH,
                            ZoneOffset.UTC
                        ), ZonedDateTime.now(ZoneOffset.UTC)
                    )
                )
                val pStmt = EbicsStandardOrderParams(
                    EbicsDateRange(
                        lastTimes.lastStatement ?: ZonedDateTime.ofInstant(
                            Instant.EPOCH,
                            ZoneOffset.UTC
                        ), ZonedDateTime.now(ZoneOffset.UTC)
                    )
                )
                when (fetchSpec.level) {
                    FetchLevel.ALL -> {
                        specs.add(EbicsFetchSpec("C52", pRep))
                        specs.add(EbicsFetchSpec("C53", pStmt))
                    }
                    FetchLevel.REPORT -> {
                        specs.add(EbicsFetchSpec("C52", pRep))
                    }
                    FetchLevel.STATEMENT -> {
                        specs.add(EbicsFetchSpec("C53", pStmt))
                    }
                }
            }
        }
        /* Not handling errors here because
          sub-calls should throw and get caught by
          global handlers.
         */
        for (spec in specs)
            fetchEbicsC5x(
                spec.orderType,
                client,
                bankConnectionId,
                spec.orderParams,
                subscriberDetails
            )
    }

    override suspend fun submitPaymentInitiation(httpClient: HttpClient, paymentInitiationId: Long) {
        val r = transaction {
            val paymentInitiation = PaymentInitiationEntity.findById(paymentInitiationId)
                ?: throw NexusError(HttpStatusCode.NotFound, "payment initiation not found")
            val conn = paymentInitiation.bankAccount.defaultBankConnection
                ?: throw NexusError(HttpStatusCode.NotFound, "no default bank connection available for submission")
            val subscriberDetails = getEbicsSubscriberDetails(conn.connectionId)
            val painMessage = createPain001document(
                NexusPaymentInitiationData(
                    debtorIban = paymentInitiation.bankAccount.iban,
                    debtorBic = paymentInitiation.bankAccount.bankCode,
                    debtorName = paymentInitiation.bankAccount.accountHolder,
                    currency = paymentInitiation.currency,
                    amount = paymentInitiation.sum.toString(),
                    creditorIban = paymentInitiation.creditorIban,
                    creditorName = paymentInitiation.creditorName,
                    creditorBic = paymentInitiation.creditorBic,
                    paymentInformationId = paymentInitiation.paymentInformationId,
                    preparationTimestamp = paymentInitiation.preparationDate,
                    subject = paymentInitiation.subject,
                    instructionId = paymentInitiation.instructionId,
                    endToEndId = paymentInitiation.endToEndId,
                    messageId = paymentInitiation.messageId
                )
            )
            if (!XMLUtil.validateFromString(painMessage)) throw NexusError(
                HttpStatusCode.InternalServerError, "Pain.001 message is invalid."
            )
            object {
                val subscriberDetails = subscriberDetails
                val painMessage = painMessage
            }
        }
        doEbicsUploadTransaction(
            httpClient,
            r.subscriberDetails,
            "CCT",
            r.painMessage.toByteArray(Charsets.UTF_8),
            EbicsStandardOrderParams()
        )
        transaction {
            val paymentInitiation = PaymentInitiationEntity.findById(paymentInitiationId)
                ?: throw NexusError(HttpStatusCode.NotFound, "payment initiation not found")
            paymentInitiation.submitted = true
            paymentInitiation.submissionDate = LocalDateTime.now().millis()
        }
    }

    override fun exportAnalogDetails(conn: NexusBankConnectionEntity): ByteArray {
        val ebicsSubscriber = transaction { getEbicsSubscriberDetails(conn.connectionId) }
        val po = ByteArrayOutputStream()
        val pdfWriter = PdfWriter(po)
        val pdfDoc = PdfDocument(pdfWriter)
        val date = LocalDateTime.now()
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun writeCommon(doc: Document) {
            doc.add(
                Paragraph(
                    """
            Datum: $dateStr
            Teilnehmer: ${conn.id.value}
            Host-ID: ${ebicsSubscriber.hostId}
            User-ID: ${ebicsSubscriber.userId}
            Partner-ID: ${ebicsSubscriber.partnerId}
            ES version: A006
        """.trimIndent()
                )
            )
        }

        fun writeKey(doc: Document, priv: RSAPrivateCrtKey) {
            val pub = CryptoUtil.getRsaPublicFromPrivate(priv)
            val hash = CryptoUtil.getEbicsPublicKeyHash(pub)
            doc.add(Paragraph("Exponent:\n${formatHex(pub.publicExponent.toByteArray())}"))
            doc.add(Paragraph("Modulus:\n${formatHex(pub.modulus.toByteArray())}"))
            doc.add(Paragraph("SHA-256 hash:\n${formatHex(hash)}"))
        }

        fun writeSigLine(doc: Document) {
            doc.add(Paragraph("Ort / Datum: ________________"))
            doc.add(Paragraph("Firma / Name: ________________"))
            doc.add(Paragraph("Unterschrift: ________________"))
        }

        Document(pdfDoc).use {
            it.add(Paragraph("Signaturschlüssel").setFontSize(24f))
            writeCommon(it)
            it.add(Paragraph("Öffentlicher Schlüssel (Public key for the electronic signature)"))
            writeKey(it, ebicsSubscriber.customerSignPriv)
            it.add(Paragraph("\n"))
            writeSigLine(it)
            it.add(AreaBreak())

            it.add(Paragraph("Authentifikationsschlüssel").setFontSize(24f))
            writeCommon(it)
            it.add(Paragraph("Öffentlicher Schlüssel (Public key for the identification and authentication signature)"))
            writeKey(it, ebicsSubscriber.customerAuthPriv)
            it.add(Paragraph("\n"))
            writeSigLine(it)
            it.add(AreaBreak())

            it.add(Paragraph("Verschlüsselungsschlüssel").setFontSize(24f))
            writeCommon(it)
            it.add(Paragraph("Öffentlicher Schlüssel (Public encryption key)"))
            writeKey(it, ebicsSubscriber.customerEncPriv)
            it.add(Paragraph("\n"))
            writeSigLine(it)
        }
        pdfWriter.flush()
        return po.toByteArray()
    }

    override fun exportBackup(bankConnectionId: String, passphrase: String): JsonNode {
        val subscriber = transaction { getEbicsSubscriberDetails(bankConnectionId) }
        val ret = EbicsKeysBackupJson(
            type = "ebics",
            userID = subscriber.userId,
            hostID = subscriber.hostId,
            partnerID = subscriber.partnerId,
            ebicsURL = subscriber.ebicsUrl,
            authBlob = bytesToBase64(
                CryptoUtil.encryptKey(
                    subscriber.customerAuthPriv.encoded,
                    passphrase
                )
            ),
            encBlob = bytesToBase64(
                CryptoUtil.encryptKey(
                    subscriber.customerEncPriv.encoded,
                    passphrase
                )
            ),
            sigBlob = bytesToBase64(
                CryptoUtil.encryptKey(
                    subscriber.customerSignPriv.encoded,
                    passphrase
                )
            )
        )
        val mapper = ObjectMapper()
        return mapper.valueToTree(ret)
    }

    override fun getConnectionDetails(conn: NexusBankConnectionEntity): JsonNode {
        val ebicsSubscriber = transaction { getEbicsSubscriberDetails(conn.connectionId) }
        val mapper = ObjectMapper()
        val details = mapper.createObjectNode()
        details.put("ebicsUrl", ebicsSubscriber.ebicsUrl)
        details.put("ebicsHostId", ebicsSubscriber.hostId)
        details.put("partnerId", ebicsSubscriber.partnerId)
        details.put("userId", ebicsSubscriber.userId)
        val node = mapper.createObjectNode()
        node.put("type", conn.type)
        node.put("owner", conn.owner.username)
        node.put("ready", true) // test with #6715 needed.
        node.set<JsonNode>("details", details)
        return node
    }

    override fun createConnection(connId: String, user: NexusUserEntity, data: JsonNode) {
        val bankConn = NexusBankConnectionEntity.new {
            this.connectionId = connId
            owner = user
            type = "ebics"
        }
        val newTransportData = jacksonObjectMapper(
        ).treeToValue(data, EbicsNewTransport::class.java) ?: throw NexusError(
            HttpStatusCode.BadRequest, "Ebics details not found in request"
        )
        val pairA = CryptoUtil.generateRsaKeyPair(2048)
        val pairB = CryptoUtil.generateRsaKeyPair(2048)
        val pairC = CryptoUtil.generateRsaKeyPair(2048)
        EbicsSubscriberEntity.new {
            ebicsURL = newTransportData.ebicsURL
            hostID = newTransportData.hostID
            partnerID = newTransportData.partnerID
            userID = newTransportData.userID
            systemID = newTransportData.systemID
            signaturePrivateKey = ExposedBlob((pairA.private.encoded))
            encryptionPrivateKey = ExposedBlob((pairB.private.encoded))
            authenticationPrivateKey = ExposedBlob((pairC.private.encoded))
            nexusBankConnection = bankConn
            ebicsIniState = EbicsInitState.NOT_SENT
            ebicsHiaState = EbicsInitState.NOT_SENT
        }
    }

    override fun createConnectionFromBackup(
        connId: String,
        user: NexusUserEntity,
        passphrase: String?,
        backup: JsonNode
    ) {
        if (passphrase === null) {
            throw NexusError(HttpStatusCode.BadRequest, "EBICS backup needs passphrase")
        }
        val bankConn = NexusBankConnectionEntity.new {
            connectionId = connId
            owner = user
            type = "ebics"
        }
        val ebicsBackup = jacksonObjectMapper().treeToValue(backup, EbicsKeysBackupJson::class.java)
        val (authKey, encKey, sigKey) = try {
            Triple(
                CryptoUtil.decryptKey(
                    EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.authBlob)),
                    passphrase
                ),
                CryptoUtil.decryptKey(
                    EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.encBlob)),
                    passphrase
                ),
                CryptoUtil.decryptKey(
                    EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.sigBlob)),
                    passphrase
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            logger.info("Restoring keys failed, probably due to wrong passphrase")
            throw NexusError(
                HttpStatusCode.BadRequest,
                "Bad backup given"
            )
        }
        try {
            EbicsSubscriberEntity.new {
                ebicsURL = ebicsBackup.ebicsURL
                hostID = ebicsBackup.hostID
                partnerID = ebicsBackup.partnerID
                userID = ebicsBackup.userID
                signaturePrivateKey = ExposedBlob(sigKey.encoded)
                encryptionPrivateKey = ExposedBlob((encKey.encoded))
                authenticationPrivateKey = ExposedBlob((authKey.encoded))
                nexusBankConnection = bankConn
                ebicsIniState = EbicsInitState.UNKNOWN
                ebicsHiaState = EbicsInitState.UNKNOWN
            }
        } catch (e: Exception) {
            throw NexusError(
                HttpStatusCode.BadRequest,
                "exception: $e"
            )
        }
        return
    }

    override suspend fun fetchAccounts(client: HttpClient, connId: String) {
        val subscriberDetails = transaction { getEbicsSubscriberDetails(connId) }
        val response = doEbicsDownloadTransaction(
            client, subscriberDetails, "HTD", EbicsStandardOrderParams()
        )
        when (response) {
            is EbicsDownloadBankErrorResult -> {
                throw NexusError(
                    HttpStatusCode.BadGateway,
                    response.returnCode.errorCode
                )
            }
            is EbicsDownloadSuccessResult -> {
                val payload = XMLUtil.convertStringToJaxb<HTDResponseOrderData>(
                    response.orderData.toString(Charsets.UTF_8)
                )
                transaction {
                    payload.value.partnerInfo.accountInfoList?.forEach { accountInfo ->
                        val conn = NexusBankConnectionEntity.findByName(connId) ?: throw NexusError(
                            HttpStatusCode.NotFound,
                            "bank connection not found"
                        )

                        val isDuplicate = OfferedBankAccountsTable.select {
                            OfferedBankAccountsTable.bankConnection eq conn.id and (
                                    OfferedBankAccountsTable.offeredAccountId eq accountInfo.id)
                        }.firstOrNull()
                        if (isDuplicate != null) return@forEach
                        OfferedBankAccountsTable.insert { newRow ->
                            newRow[accountHolder] = accountInfo.accountHolder ?: "NOT GIVEN"
                            newRow[iban] =
                                accountInfo.accountNumberList?.filterIsInstance<EbicsTypes.GeneralAccountNumber>()
                                    ?.find { it.international }?.value
                                    ?: throw NexusError(HttpStatusCode.NotFound, reason = "bank gave no IBAN")
                            newRow[bankCode] = accountInfo.bankCodeList?.filterIsInstance<EbicsTypes.GeneralBankCode>()
                                ?.find { it.international }?.value
                                ?: throw NexusError(
                                    HttpStatusCode.NotFound,
                                    reason = "bank gave no BIC"
                                )
                            newRow[bankConnection] = requireBankConnectionInternal(connId).id
                            newRow[offeredAccountId] = accountInfo.id
                        }
                    }
                }
            }
        }
    }

    override suspend fun connect(client: HttpClient, connId: String) {
        val subscriber = transaction { getEbicsSubscriberDetails(connId) }
        if (subscriber.bankAuthPub != null && subscriber.bankEncPub != null) {
            return
        }
        if (subscriber.ebicsIniState == EbicsInitState.UNKNOWN || subscriber.ebicsHiaState == EbicsInitState.UNKNOWN) {
            if (tentativeHpb(client, connId)) {
                /**
                 * NOTE/FIXME: in case the HIA/INI did succeed (state is UNKNOWN but Sandbox
                 * has somehow the keys), here the state should be set to SENT, because later -
                 * when the Sandbox will respond to the INI/HIA requests - we'll get a
                 * EBICS_INVALID_USER_OR_USER_STATE.  Hence, the state will never switch to
                 * SENT again.
                 */
                return
            }
        }
        val iniDone = when (subscriber.ebicsIniState) {
            EbicsInitState.NOT_SENT, EbicsInitState.UNKNOWN -> {
                val iniResp = doEbicsIniRequest(client, subscriber)
                iniResp.bankReturnCode == EbicsReturnCode.EBICS_OK && iniResp.technicalReturnCode == EbicsReturnCode.EBICS_OK
            }
            EbicsInitState.SENT -> true
        }
        val hiaDone = when (subscriber.ebicsHiaState) {
            EbicsInitState.NOT_SENT, EbicsInitState.UNKNOWN -> {
                val hiaResp = doEbicsHiaRequest(client, subscriber)
                hiaResp.bankReturnCode == EbicsReturnCode.EBICS_OK && hiaResp.technicalReturnCode == EbicsReturnCode.EBICS_OK
            }
            EbicsInitState.SENT -> true
        }
        val hpbData = try {
            doEbicsHpbRequest(client, subscriber)
        } catch (e: EbicsProtocolError) {
            logger.warn("failed HPB request", e)
            null
        }
        transaction {
            val conn = NexusBankConnectionEntity.findByName(connId)
            if (conn == null) {
                throw NexusError(HttpStatusCode.NotFound, "bank connection '$connId' not found")
            }
            val subscriberEntity =
                EbicsSubscriberEntity.find { NexusEbicsSubscribersTable.nexusBankConnection eq conn.id }.first()
            if (iniDone) {
                subscriberEntity.ebicsIniState = EbicsInitState.SENT
            }
            if (hiaDone) {
                subscriberEntity.ebicsHiaState = EbicsInitState.SENT
            }
            if (hpbData != null) {
                subscriberEntity.bankAuthenticationPublicKey =
                    ExposedBlob((hpbData.authenticationPubKey.encoded))
                subscriberEntity.bankEncryptionPublicKey = ExposedBlob((hpbData.encryptionPubKey.encoded))
            }
        }
    }
}
