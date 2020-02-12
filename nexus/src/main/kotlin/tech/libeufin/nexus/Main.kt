/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.nexus

import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.request.uri
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.util.*
import tech.libeufin.util.InvalidSubscriberStateError
import tech.libeufin.util.ebics_h004.EbicsTypes
import tech.libeufin.util.ebics_h004.HTDResponseOrderData
import java.lang.StringBuilder
import java.security.interfaces.RSAPublicKey
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.EncryptedPrivateKeyInfo
import javax.sql.rowset.serial.SerialBlob

fun testData() {
    val pairA = CryptoUtil.generateRsaKeyPair(2048)
    val pairB = CryptoUtil.generateRsaKeyPair(2048)
    val pairC = CryptoUtil.generateRsaKeyPair(2048)
    try {
        transaction {
            addLogger(StdOutSqlLogger)
            EbicsSubscriberEntity.new(id = "default-customer") {
                ebicsURL = "http://localhost:5000/ebicsweb"
                userID = "USER1"
                partnerID = "PARTNER1"
                hostID = "host01"
                signaturePrivateKey = SerialBlob(pairA.private.encoded)
                encryptionPrivateKey = SerialBlob(pairB.private.encoded)
                authenticationPrivateKey = SerialBlob(pairC.private.encoded)
            }
        }
    } catch (e: ExposedSQLException) {
        logger.info("Likely primary key collision for sample data: accepted")
    }
}

data class NotAnIdError(val statusCode: HttpStatusCode) : Exception("String ID not convertible in number")
data class BankKeyMissing(val statusCode: HttpStatusCode) : Exception("Impossible operation: bank keys are missing")
data class SubscriberNotFoundError(val statusCode: HttpStatusCode) : Exception("Subscriber not found in database")
data class UnreachableBankError(val statusCode: HttpStatusCode) : Exception("Could not reach the bank")
data class UnparsableResponse(val statusCode: HttpStatusCode, val rawResponse: String) :
    Exception("bank responded: ${rawResponse}")

class ProtocolViolationError(message: String) : Exception("protocol violation: ${message}")
class InvalidSubscriberStateError(message: String) : Exception("invalid subscriber state: ${message}")
data class EbicsError(val codeError: String) : Exception("Bank did not accepted EBICS request, error is: ${codeError}")
data class BadSignature(val statusCode: HttpStatusCode) : Exception("Signature verification unsuccessful")
data class BadBackup(val statusCode: HttpStatusCode) : Exception("Could not restore backed up keys")
data class BankInvalidResponse(val statusCode: HttpStatusCode) : Exception("Missing data from bank response")

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")

fun getSubscriberDetailsFromId(id: String): EbicsClientSubscriberDetails {
    return transaction {
        val subscriber = EbicsSubscriberEntity.findById(
            id
        ) ?: throw SubscriberNotFoundError(
            HttpStatusCode.NotFound
        )
        var bankAuthPubValue: RSAPublicKey? = null
        if (subscriber.bankAuthenticationPublicKey != null) {
            bankAuthPubValue = CryptoUtil.loadRsaPublicKey(
                subscriber.bankAuthenticationPublicKey?.toByteArray()!!
            )
        }
        var bankEncPubValue: RSAPublicKey? = null
        if (subscriber.bankEncryptionPublicKey != null) {
            bankEncPubValue = CryptoUtil.loadRsaPublicKey(
                subscriber.bankEncryptionPublicKey?.toByteArray()!!
            )
        }
        EbicsClientSubscriberDetails(
            bankAuthPub = bankAuthPubValue,
            bankEncPub = bankEncPubValue,

            ebicsUrl = subscriber.ebicsURL,
            hostId = subscriber.hostID,
            userId = subscriber.userID,
            partnerId = subscriber.partnerID,

            customerSignPriv = CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray()),
            customerAuthPriv = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray()),
            customerEncPriv = CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.toByteArray())
        )
    }
}

fun main() {
    dbCreateTables()
    testData()
    val client = HttpClient() {
        expectSuccess = false // this way, it does not throw exceptions on != 200 responses.
    }
    val server = embeddedServer(Netty, port = 5001) {
        install(CallLogging) {
            this.level = Level.DEBUG
            this.logger = tech.libeufin.nexus.logger
        }
        install(ContentNegotiation) {
            gson {
                setDateFormat(DateFormat.LONG)
                setPrettyPrinting()
            }
        }
        install(StatusPages) {
            exception<Throwable> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Internal server error.\n", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
            }

            exception<NotAnIdError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Bad request\n", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            }

            exception<BadBackup> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    "Bad backup, or passphrase incorrect\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.BadRequest
                )
            }

            exception<UnparsableResponse> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    "Could not parse bank response (${cause.message})\n", ContentType.Text.Plain, HttpStatusCode
                        .InternalServerError
                )
            }

            exception<UnreachableBankError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    "Could not reach the bank\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.InternalServerError
                )
            }

            exception<SubscriberNotFoundError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Subscriber not found\n", ContentType.Text.Plain, HttpStatusCode.NotFound)
            }

            exception<BadSignature> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    "Signature verification unsuccessful\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.NotAcceptable
                )
            }

            exception<EbicsError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    "Bank gave EBICS-error response\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.NotAcceptable
                )
            }

            exception<BankKeyMissing> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    "Impossible operation: get bank keys first\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.NotAcceptable
                )
            }

            exception<javax.xml.bind.UnmarshalException> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    "Could not convert string into JAXB (either from client or from bank)\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.NotFound
                )
            }
        }

        intercept(ApplicationCallPipeline.Fallback) {
            if (this.call.response.status() == null) {
                call.respondText("Not found (no route matched).\n", ContentType.Text.Plain, HttpStatusCode.NotFound)
                return@intercept finish()
            }
        }

        routing {
            get("/") {
                call.respondText("Hello by Nexus!\n")
                return@get
            }

            post("/ebics/subscribers/{id}/sendPTK") {
                val id = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                println("PTK order params: $orderParams")
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "PTK", orderParams)
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
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHAC") {
                val id = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "HAC", orderParams)
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
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
            }

            post("/ebics/subscribers/fetch-accounts") {
                // FIXME(marcello): fetch accounts via HTD and store it in the database
            }

            get("/ebics/subscribers/{id}/accounts") {
                // FIXME(marcello): return bank accounts associated with the subscriber,
                // this information is only avaiable *after* HTD or HKD has been called
                val id = expectId(call.parameters["id"])
                val ret = EbicsAccountsInfoResponse()
                transaction {
                    EbicsAccountInfoEntity.find {
                        EbicsAccountsInfoTable.subscriber eq id
                    }.forEach {
                        ret.accounts.add(
                            EbicsAccountInfoElement(
                                accountHolderName = it.accountHolder,
                                iban = it.iban,
                                bankCode = it.bankCode,
                                accountId = it.accountId
                            )
                        )
                    }
                }
                call.respond(
                    HttpStatusCode.OK,
                    ret
                )
                return@get
            }

            post("/ebics/subscribers/{id}/accounts/{acctid}/prepare-payment") {
                // FIXME(marcello):  Put transaction in the database, generate PAIN.001 document
            }

            get("/ebics/subscribers/{id}/payments") {
                // FIXME(marcello):  List all outgoing transfers and their status
            }

            post("/ebics/subscribers/{id}/fetch-payment-status") {
                // FIXME(marcello?):  Fetch pain.002 and mark transfers in it as "failed"
            }

            post("/ebics/subscribers/{id}/collect-transactions-c52") {
                // FIXME(florian): Download C52 and store the result in the right database table
            }

            post("/ebics/subscribers/{id}/collect-transactions-c53") {
                // FIXME(florian): Download C52 and store the result in the right database table
            }

            post("/ebics/subscribers/{id}/collect-transactions-c54") {
                // FIXME(florian): Download C52 and store the result in the right database table
            }

            get("/ebics/subscribers/{id}/transactions") {
                // FIXME(florian): Display local transaction history stored by the nexus.
            }

            post("/ebics/subscribers/{id}/sendC52") {
                val id = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "C52", orderParams)
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
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
            }

            post("/ebics/subscribers/{id}/sendC53") {
                val id = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "C53", orderParams)
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        val mem = SeekableInMemoryByteChannel(response.orderData)
                        val zipFile = ZipFile(mem)

                        val s = StringBuilder()

                        zipFile.getEntriesInPhysicalOrder().iterator().forEach { entry ->
                            s.append("<=== File ${entry.name} ===>\n")
                            s.append(zipFile.getInputStream(entry).readAllBytes().toString(Charsets.UTF_8))
                            s.append("\n")
                        }
                        call.respondText(
                            s.toString(),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
            }

            post("/ebics/subscribers/{id}/sendC54") {
                val id = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "C54", orderParams)
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
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHtd") {
                val customerIdAtNexus = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromId(customerIdAtNexus)
                val response = doEbicsDownloadTransaction(client, subscriberData, "HTD", orderParams)
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        val payload = XMLUtil.convertStringToJaxb<HTDResponseOrderData>(response.orderData.toString(Charsets.UTF_8))
                        if (null == payload.value.partnerInfo.accountInfoList) {
                            throw Exception(
                                "Inconsistent state: customers MUST have at least one bank account"
                            )
                        }
                        transaction {
                            val subscriber = EbicsSubscriberEntity.findById(customerIdAtNexus)
                            payload.value.partnerInfo.accountInfoList!!.forEach {
                                EbicsAccountInfoEntity.new {
                                    this.subscriber = subscriber!! /* Checked at the beginning of this function */
                                    accountId = it.id
                                    accountHolder = it.accountHolder
                                    /* FIXME: how to figure out whether that's a general or national account number? */
                                    iban = (it.accountNumberList?.get(0) as EbicsTypes.GeneralAccountNumber).value // FIXME: eventually get *all* of them
                                    bankCode = (it.bankCodeList?.get(0) as EbicsTypes.GeneralBankCode).value  // FIXME: eventually get *all* of them
                                }
                            }
                        }
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHAA") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "HAA", EbicsStandardOrderParams())
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
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHVZ") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                // FIXME: order params are wrong
                val response = doEbicsDownloadTransaction(client, subscriberData, "HVZ", EbicsStandardOrderParams())
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
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHVU") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                // FIXME: order params are wrong
                val response = doEbicsDownloadTransaction(client, subscriberData, "HVU", EbicsStandardOrderParams())
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
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHPD") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "HPD", EbicsStandardOrderParams())
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
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHKD") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "HKD", EbicsStandardOrderParams())
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
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendTSD") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "TSD", EbicsGenericOrderParams())
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
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            get("/ebics/subscribers/{id}/keyletter") {
                val id = expectId(call.parameters["id"])
                var usernameLine = "TODO"
                var recipientLine = "TODO"
                val customerIdLine = "TODO"
                var userIdLine = ""
                var esExponentLine = ""
                var esModulusLine = ""
                var authExponentLine = ""
                var authModulusLine = ""
                var encExponentLine = ""
                var encModulusLine = ""
                var esKeyHashLine = ""
                var encKeyHashLine = ""
                var authKeyHashLine = ""
                val esVersionLine = "A006"
                val authVersionLine = "X002"
                val encVersionLine = "E002"
                val now = Date()
                val dateFormat = SimpleDateFormat("DD.MM.YYYY")
                val timeFormat = SimpleDateFormat("HH:mm:ss")
                val dateLine = dateFormat.format(now)
                val timeLine = timeFormat.format(now)
                var hostID = ""
                transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(
                        HttpStatusCode.NotFound
                    )
                    val signPubTmp = CryptoUtil.getRsaPublicFromPrivate(
                        CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray())
                    )
                    val authPubTmp = CryptoUtil.getRsaPublicFromPrivate(
                        CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
                    )
                    val encPubTmp = CryptoUtil.getRsaPublicFromPrivate(
                        CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.toByteArray())
                    )
                    hostID = subscriber.hostID
                    userIdLine = subscriber.userID
                    esExponentLine = signPubTmp.publicExponent.toUnsignedHexString()
                    esModulusLine = signPubTmp.modulus.toUnsignedHexString()
                    encExponentLine = encPubTmp.publicExponent.toUnsignedHexString()
                    encModulusLine = encPubTmp.modulus.toUnsignedHexString()
                    authExponentLine = authPubTmp.publicExponent.toUnsignedHexString()
                    authModulusLine = authPubTmp.modulus.toUnsignedHexString()
                    esKeyHashLine = CryptoUtil.getEbicsPublicKeyHash(signPubTmp).toHexString()
                    encKeyHashLine = CryptoUtil.getEbicsPublicKeyHash(encPubTmp).toHexString()
                    authKeyHashLine = CryptoUtil.getEbicsPublicKeyHash(authPubTmp).toHexString()
                }
                val iniLetter = """
                    |Name: ${usernameLine}
                    |Date: ${dateLine}
                    |Time: ${timeLine}
                    |Recipient: ${recipientLine}
                    |Host ID: ${hostID}
                    |User ID: ${userIdLine}
                    |Partner ID: ${customerIdLine}
                    |ES version: ${esVersionLine}
                    
                    |Public key for the electronic signature:
                    
                    |Exponent:
                    |${chunkString(esExponentLine)}
                    
                    |Modulus:
                    |${chunkString(esModulusLine)}
                    
                    |SHA-256 hash:
                    |${chunkString(esKeyHashLine)}
                    
                    |I hereby confirm the above public keys for my electronic signature.
                    
                    |__________
                    |Place/date
                    
                    |__________
                    |Signature
                    |
                """.trimMargin()

                val hiaLetter = """
                    |Name: ${usernameLine}
                    |Date: ${dateLine}
                    |Time: ${timeLine}
                    |Recipient: ${recipientLine}
                    |Host ID: ${hostID}
                    |User ID: ${userIdLine}
                    |Partner ID: ${customerIdLine}
                    |Identification and authentication signature version: ${authVersionLine}
                    |Encryption version: ${encVersionLine}
                    
                    |Public key for the identification and authentication signature:
                    
                    |Exponent:
                    |${chunkString(authExponentLine)}
                    
                    |Modulus:
                    |${chunkString(authModulusLine)}
                    
                    |SHA-256 hash:
                    |${chunkString(authKeyHashLine)}
                    
                    |Public encryption key:
                    
                    |Exponent:
                    |${chunkString(encExponentLine)}
                    
                    |Modulus:
                    |${chunkString(encModulusLine)}
                    
                    |SHA-256 hash:
                    |${chunkString(encKeyHashLine)}              

                    |I hereby confirm the above public keys for my electronic signature.
                    
                    |__________
                    |Place/date
                    
                    |__________
                    |Signature
                    |
                """.trimMargin()

                call.respondText(
                    "####INI####:\n${iniLetter}\n\n\n####HIA####:\n${hiaLetter}",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
            }

            get("/ebics/subscribers") {
                val ret = EbicsSubscribersResponseJson()
                transaction {
                    EbicsSubscriberEntity.all().forEach {
                        ret.ebicsSubscribers.add(
                            EbicsSubscriberInfoResponseJson(
                                accountID = it.id.value,
                                hostID = it.hostID,
                                partnerID = it.partnerID,
                                systemID = it.systemID,
                                ebicsURL = it.ebicsURL,
                                userID = it.userID
                            )
                        )
                    }
                }
                call.respond(ret)
                return@get
            }

            get("/ebics/subscribers/{id}") {
                val id = expectId(call.parameters["id"])
                val response = transaction {
                    val tmp = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(
                        HttpStatusCode.NotFound
                    )
                    EbicsSubscriberInfoResponseJson(
                        accountID = tmp.id.value,
                        hostID = tmp.hostID,
                        partnerID = tmp.partnerID,
                        systemID = tmp.systemID,
                        ebicsURL = tmp.ebicsURL,
                        userID = tmp.userID
                    )
                }
                call.respond(HttpStatusCode.OK, response)
                return@get
            }

            get("/ebics/{id}/sendHev") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val request = makeEbicsHEVRequest(subscriberData)
                val response = client.postToBank(subscriberData.ebicsUrl, request)
                val versionDetails = parseEbicsHEVResponse(subscriberData, response)
                call.respond(
                    HttpStatusCode.OK,
                    EbicsHevResponseJson(versionDetails.versions.map { ebicsVersionSpec ->
                        ProtocolAndVersionJson(
                            ebicsVersionSpec.protocol,
                            ebicsVersionSpec.version
                        )
                    })
                )
                return@get
            }

            post("/ebics/{id}/subscribers") {
                val body = call.receive<EbicsSubscriberInfoRequestJson>()
                val pairA = CryptoUtil.generateRsaKeyPair(2048)
                val pairB = CryptoUtil.generateRsaKeyPair(2048)
                val pairC = CryptoUtil.generateRsaKeyPair(2048)
                val row = try {
                    transaction {
                        EbicsSubscriberEntity.new(id = expectId(call.parameters["id"])) {
                            ebicsURL = body.ebicsURL
                            hostID = body.hostID
                            partnerID = body.partnerID
                            userID = body.userID
                            systemID = body.systemID
                            signaturePrivateKey = SerialBlob(pairA.private.encoded)
                            encryptionPrivateKey = SerialBlob(pairB.private.encoded)
                            authenticationPrivateKey = SerialBlob(pairC.private.encoded)
                        }
                    }
                } catch (e: Exception) {
                    print(e)
                    call.respond(NexusErrorJson("Could not store the new account into database"))
                    return@post
                }
                call.respondText(
                    "Subscriber registered, ID: ${row.id.value}",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }

            post("/ebics/subscribers/{id}/sendIni") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val iniRequest = makeEbicsIniRequest(subscriberData)
                val responseStr = client.postToBank(
                    subscriberData.ebicsUrl,
                    iniRequest
                )
                val resp = parseAndDecryptEbicsKeyManagementResponse(subscriberData, responseStr)
                if (resp.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
                    throw EbicsError("Unexpected INI response code: ${resp.technicalReturnCode}")
                }
                call.respondText("Bank accepted signature key\n", ContentType.Text.Plain, HttpStatusCode.OK)
                return@post
            }

            post("/ebics/subscribers/{id}/sendHia") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val hiaRequest = makeEbicsHiaRequest(subscriberData)
                val responseStr = client.postToBank(
                    subscriberData.ebicsUrl,
                    hiaRequest
                )
                val resp = parseAndDecryptEbicsKeyManagementResponse(subscriberData, responseStr)
                if (resp.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
                    throw EbicsError("Unexpected HIA response code: ${resp.technicalReturnCode}")
                }
                call.respondText(
                    "Bank accepted authentication and encryption keys\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }

            post("/ebics/subscribers/{id}/restoreBackup") {
                val body = call.receive<EbicsKeysBackupJson>()
                val id = expectId(call.parameters["id"])
                val subscriber = transaction {
                    EbicsSubscriberEntity.findById(id)
                }
                if (subscriber != null) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        NexusErrorJson("ID exists, please choose a new one")
                    )
                    return@post
                }
                val (authKey, encKey, sigKey) = try {
                    Triple(
                        CryptoUtil.decryptKey(
                            EncryptedPrivateKeyInfo(base64ToBytes(body.authBlob)), body.passphrase!!
                        ),
                        CryptoUtil.decryptKey(
                            EncryptedPrivateKeyInfo(base64ToBytes(body.encBlob)), body.passphrase
                        ),
                        CryptoUtil.decryptKey(
                            EncryptedPrivateKeyInfo(base64ToBytes(body.sigBlob)), body.passphrase
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    logger.info("Restoring keys failed, probably due to wrong passphrase")
                    throw BadBackup(HttpStatusCode.BadRequest)
                }
                logger.info("Restoring keys, creating new user: $id")
                try {
                    transaction {
                        EbicsSubscriberEntity.new(id = expectId(call.parameters["id"])) {
                            ebicsURL = body.ebicsURL
                            hostID = body.hostID
                            partnerID = body.partnerID
                            userID = body.userID
                            signaturePrivateKey = SerialBlob(sigKey.encoded)
                            encryptionPrivateKey = SerialBlob(encKey.encoded)
                            authenticationPrivateKey = SerialBlob(authKey.encoded)
                        }
                    }
                } catch (e: Exception) {
                    print(e)
                    call.respond(NexusErrorJson("Could not store the new account $id into database"))
                    return@post
                }
                call.respondText(
                    "Keys successfully restored",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }

            get("/ebics/subscribers/{id}/pubkeys") {
                val id = expectId(call.parameters["id"])
                val response = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(
                        HttpStatusCode.NotFound
                    )
                    val authPriv = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
                    val authPub = CryptoUtil.getRsaPublicFromPrivate(authPriv)
                    val encPriv = CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.toByteArray())
                    val encPub = CryptoUtil.getRsaPublicFromPrivate(encPriv)
                    val sigPriv = CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray())
                    val sigPub = CryptoUtil.getRsaPublicFromPrivate(sigPriv)
                    EbicsPubKeyInfo(
                        bytesToBase64(authPub.encoded),
                        bytesToBase64(encPub.encoded),
                        bytesToBase64(sigPub.encoded)
                    )
                }
                call.respond(
                    HttpStatusCode.OK,
                    response
                )
            }

            /* performs a keys backup */
            post("/ebics/subscribers/{id}/backup") {
                val id = expectId(call.parameters["id"])
                val body = call.receive<EbicsBackupRequestJson>()
                val response = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(
                        HttpStatusCode.NotFound
                    )
                    EbicsKeysBackupJson(
                        userID = subscriber.userID,
                        hostID = subscriber.hostID,
                        partnerID = subscriber.partnerID,
                        ebicsURL = subscriber.ebicsURL,
                        authBlob = bytesToBase64(
                            CryptoUtil.encryptKey(
                                subscriber.authenticationPrivateKey.toByteArray(),
                                body.passphrase
                            )
                        ),
                        encBlob = bytesToBase64(
                            CryptoUtil.encryptKey(
                                subscriber.encryptionPrivateKey.toByteArray(),
                                body.passphrase
                            )
                        ),
                        sigBlob = bytesToBase64(
                            CryptoUtil.encryptKey(
                                subscriber.signaturePrivateKey.toByteArray(),
                                body.passphrase
                            )
                        )
                    )
                }
                call.response.headers.append("Content-Disposition", "attachment")
                call.respond(
                    HttpStatusCode.OK,
                    response
                )
            }

            post("/ebics/subscribers/{id}/sendTSU") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val payload = "PAYLOAD"

                doEbicsUploadTransaction(
                    client,
                    subscriberData,
                    "TSU",
                    payload.toByteArray(Charsets.UTF_8),
                    EbicsGenericOrderParams()
                )

                call.respondText(
                    "TST INITIALIZATION & TRANSACTION phases succeeded\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
            }

            post("/ebics/subscribers/{id}/sync") {
                val id = expectId(call.parameters["id"])
                val subscriberDetails = getSubscriberDetailsFromId(id)
                val hpbRequest = makeEbicsHpbRequest(subscriberDetails)
                val responseStr = client.postToBank(subscriberDetails.ebicsUrl, hpbRequest)

                val response = parseAndDecryptEbicsKeyManagementResponse(subscriberDetails, responseStr)
                val orderData =
                    response.orderData ?: throw ProtocolViolationError("expected order data in HPB response")
                val hpbData = parseEbicsHpbOrder(orderData)

                // put bank's keys into database.
                transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw InvalidSubscriberStateError()
                    subscriber.bankAuthenticationPublicKey = SerialBlob(hpbData.authenticationPubKey.encoded)
                    subscriber.bankEncryptionPublicKey = SerialBlob(hpbData.encryptionPubKey.encoded)
                }
                call.respondText("Bank keys stored in database\n", ContentType.Text.Plain, HttpStatusCode.OK)
                return@post
            }
        }
    }
    logger.info("Up and running")
    server.start(wait = true)
}
