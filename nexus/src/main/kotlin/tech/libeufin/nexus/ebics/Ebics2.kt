/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

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

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.ebics.*
import tech.libeufin.ebics.ebics_h004.EbicsKeyManagementResponse
import tech.libeufin.ebics.ebics_h004.EbicsNpkdRequest
import tech.libeufin.ebics.ebics_h004.EbicsRequest
import tech.libeufin.ebics.ebics_h004.EbicsUnsecuredRequest
import tech.libeufin.nexus.BankPublicKeysFile
import tech.libeufin.nexus.ClientPrivateKeysFile
import tech.libeufin.nexus.EbicsSetupConfig
import java.io.InputStream
import java.security.interfaces.RSAPrivateCrtKey
import java.time.Instant
import java.time.ZoneId
import java.util.*
import javax.xml.datatype.DatatypeFactory

private val logger: Logger = LoggerFactory.getLogger("libeufin-nexus-ebics2")

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
    xml: InputStream
): EbicsKeyManagementResponseContent? {
    // TODO throw instead of null
    val jaxb = try {
        XMLUtil.convertToJaxb<EbicsKeyManagementResponse>(xml)
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
            ).readBytes()
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
fun generateIniMessage(cfg: EbicsSetupConfig, clientKeys: ClientPrivateKeysFile): ByteArray {
    val iniRequest = EbicsUnsecuredRequest.createIni(
        cfg.ebicsHostId,
        cfg.ebicsUserId,
        cfg.ebicsPartnerId,
        clientKeys.signature_private_key
    )
    val doc = XMLUtil.convertJaxbToDocument(iniRequest)
    return XMLUtil.convertDomToBytes(doc)
}

/**
 * Generates the HIA message: uploads the authentication and
 * encryption keys.
 *
 * @param cfg handle to the configuration.
 * @param clientKeys set of all the client keys.
 * @return the raw EBICS HIA message.
 */
fun generateHiaMessage(cfg: EbicsSetupConfig, clientKeys: ClientPrivateKeysFile): ByteArray {
    val hiaRequest = EbicsUnsecuredRequest.createHia(
        cfg.ebicsHostId,
        cfg.ebicsUserId,
        cfg.ebicsPartnerId,
        clientKeys.authentication_private_key,
        clientKeys.encryption_private_key
    )
    val doc = XMLUtil.convertJaxbToDocument(hiaRequest)
    return XMLUtil.convertDomToBytes(doc)
}

/**
 * Generates the HPB message: downloads the bank keys.
 *
 * @param cfg handle to the configuration.
 * @param clientKeys set of all the client keys.
 * @return the raw EBICS HPB message.
 */
fun generateHpbMessage(cfg: EbicsSetupConfig, clientKeys: ClientPrivateKeysFile): ByteArray {
    val hpbRequest = EbicsNpkdRequest.createRequest(
        cfg.ebicsHostId,
        cfg.ebicsPartnerId,
        cfg.ebicsUserId,
        getNonce(128),
        DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar())
    )
    val doc = XMLUtil.convertJaxbToDocument(hpbRequest)
    XMLUtil.signEbicsDocument(doc, clientKeys.authentication_private_key)
    return XMLUtil.convertDomToBytes(doc)
}