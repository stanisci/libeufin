/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Stanisci and Dold.

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

/**
 * This file contains helpers to construct EBICS 2.x requests.
 */

package tech.libeufin.nexus.ebics

import io.ktor.client.*
import tech.libeufin.nexus.BankPublicKeysFile
import tech.libeufin.nexus.ClientPrivateKeysFile
import tech.libeufin.nexus.EbicsSetupConfig
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.*
import java.security.interfaces.RSAPrivateCrtKey
import java.time.ZoneId
import java.util.*
import javax.xml.datatype.DatatypeFactory

/**
 * Request EBICS (2.x) HTD to the bank.  This message type
 * gets the list of bank accounts that are owned by the EBICS
 * client.
 *
 * @param cfg configuration handle
 * @param client client EBICS keys.
 * @param bankKeys bank EBICS keys.
 * @param client HTTP client handle.
 * @return internal representation of the HTD response, or
 *         null in case of errors.
 */
suspend fun fetchBankAccounts(
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile,
    client: HttpClient
): HTDResponseOrderData? {
    val xmlReq = createEbics25DownloadInit(cfg, clientKeys, bankKeys, "HTD")
    val xmlResp = doEbicsDownload(client, cfg, clientKeys, bankKeys, xmlReq, false)
    if (xmlResp == null) {
        logger.error("EBICS HTD transaction failed.")
        return null
    }
    return try {
        XMLUtil.convertStringToJaxb<HTDResponseOrderData>(xmlResp).value
    } catch (e: Exception) {
        logger.error("Could not parse the HTD payload, detail: ${e.message}")
        return null
    }
}
/**
 * Creates a EBICS 2.5 download init. message.  So far only used
 * to fetch the PostFinance bank accounts.
 */
fun createEbics25DownloadInit(
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile,
    orderType: String,
    orderParams: EbicsOrderParams = EbicsStandardOrderParams()
): String {
    val nonce = getNonce(128)
    val req = EbicsRequest.createForDownloadInitializationPhase(
        cfg.ebicsUserId,
        cfg.ebicsPartnerId,
        cfg.ebicsHostId,
        nonce,
        DatatypeFactory.newInstance().newXMLGregorianCalendar(
            GregorianCalendar(
            TimeZone.getTimeZone(ZoneId.systemDefault())
        )
        ),
        bankKeys.bank_encryption_public_key,
        bankKeys.bank_authentication_public_key,
        orderType,
        makeOrderParams(orderParams)
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(
        doc,
        clientKeys.authentication_private_key,
        withEbics3 = false
    )
    return XMLUtil.convertDomToString(doc)
}

/**
 * Creates raw XML for an EBICS receipt phase.
 *
 * @param cfg configuration handle.
 * @param clientKeys user EBICS private keys.
 * @param transactionId transaction ID of the EBICS communication that
 *        should receive this receipt.
 * @return receipt request in XML.
 */
fun createEbics25ReceiptPhase(
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    transactionId: String
): String {
    val req = EbicsRequest.createForDownloadReceiptPhase(
        transactionId,
        cfg.ebicsHostId
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(
        doc,
        clientKeys.authentication_private_key,
        withEbics3 = false
    )
    return XMLUtil.convertDomToString(doc)
}

/**
 * Creates raw XML for an EBICS transfer phase.
 *
 * @param cfg configuration handle.
 * @param clientKeys user EBICS private keys.
 * @param segNumber which segment we ask to the bank.
 * @param totalSegments how many segments compose the whole EBICS transaction.
 * @param transactionId ID of the EBICS transaction that transports all the segments.
 * @return raw XML string of the request.
 */
fun createEbics25TransferPhase(
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    segNumber: Int,
    totalSegments: Int,
    transactionId: String
): String {
    val req = EbicsRequest.createForDownloadTransferPhase(
        hostID = cfg.ebicsHostId,
        segmentNumber = segNumber,
        numSegments = totalSegments,
        transactionID = transactionId
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(
        doc,
        clientKeys.authentication_private_key,
        withEbics3 = false
    )
    return XMLUtil.convertDomToString(doc)
}

/**
 * Parses the raw XML that came from the bank into the Nexus representation.
 *
 * @param clientEncryptionKey client private encryption key, used to decrypt
 *                            the transaction key.
 * @param xml the bank raw XML response
 * @return the internal representation of the XML response, or null if the parsing or the decryption failed.
 *         Note: it _is_ possible to successfully return the internal repr. of this response, where
 *         the payload is null.  That's however still useful, because the returned type provides bank
 *         and EBICS return codes.
 */
fun parseKeysMgmtResponse(
    clientEncryptionKey: RSAPrivateCrtKey,
    xml: String
): EbicsKeyManagementResponseContent? {
    val jaxb = try {
        XMLUtil.convertStringToJaxb<EbicsKeyManagementResponse>(xml)
    } catch (e: Exception) {
        tech.libeufin.nexus.logger.error("Could not parse the raw response from bank into JAXB.")
        return null
    }
    var payload: ByteArray? = null
    jaxb.value.body.dataTransfer?.dataEncryptionInfo.apply {
        // non-null indicates that an encrypted payload should be found.
        if (this != null) {
            val encOrderData = jaxb.value.body.dataTransfer?.orderData?.value
            if (encOrderData == null) {
                tech.libeufin.nexus.logger.error("Despite a non-null DataEncryptionInfo, OrderData could not be found, can't decrypt any payload!")
                return null
            }
            payload = decryptAndDecompressPayload(
                clientEncryptionKey,
                DataEncryptionInfo(this.transactionKey, this.encryptionPubKeyDigest.value),
                listOf(encOrderData)
            )
        }
    }
    val bankReturnCode = EbicsReturnCode.lookup(jaxb.value.body.returnCode.value) // business error
    val ebicsReturnCode = EbicsReturnCode.lookup(jaxb.value.header.mutable.returnCode) // ebics error
    return EbicsKeyManagementResponseContent(ebicsReturnCode, bankReturnCode, payload)
}

/**
 * Generates the INI message to upload the signature key.
 *
 * @param cfg handle to the configuration.
 * @param clientKeys set of all the client keys.
 * @return the raw EBICS INI message.
 */
fun generateIniMessage(cfg: EbicsSetupConfig, clientKeys: ClientPrivateKeysFile): String {
    val iniRequest = EbicsUnsecuredRequest.createIni(
        cfg.ebicsHostId,
        cfg.ebicsUserId,
        cfg.ebicsPartnerId,
        clientKeys.signature_private_key
    )
    val doc = XMLUtil.convertJaxbToDocument(iniRequest)
    return XMLUtil.convertDomToString(doc)
}

/**
 * Generates the HIA message: uploads the authentication and
 * encryption keys.
 *
 * @param cfg handle to the configuration.
 * @param clientKeys set of all the client keys.
 * @return the raw EBICS HIA message.
 */
fun generateHiaMessage(cfg: EbicsSetupConfig, clientKeys: ClientPrivateKeysFile): String {
    val hiaRequest = EbicsUnsecuredRequest.createHia(
        cfg.ebicsHostId,
        cfg.ebicsUserId,
        cfg.ebicsPartnerId,
        clientKeys.authentication_private_key,
        clientKeys.encryption_private_key
    )
    val doc = XMLUtil.convertJaxbToDocument(hiaRequest)
    return XMLUtil.convertDomToString(doc)
}

/**
 * Generates the HPB message: downloads the bank keys.
 *
 * @param cfg handle to the configuration.
 * @param clientKeys set of all the client keys.
 * @return the raw EBICS HPB message.
 */
fun generateHpbMessage(cfg: EbicsSetupConfig, clientKeys: ClientPrivateKeysFile): String {
    val hpbRequest = EbicsNpkdRequest.createRequest(
        cfg.ebicsHostId,
        cfg.ebicsPartnerId,
        cfg.ebicsUserId,
        getNonce(128),
        DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar())
    )
    val doc = XMLUtil.convertJaxbToDocument(hpbRequest)
    XMLUtil.signEbicsDocument(doc, clientKeys.authentication_private_key)
    return XMLUtil.convertDomToString(doc)
}