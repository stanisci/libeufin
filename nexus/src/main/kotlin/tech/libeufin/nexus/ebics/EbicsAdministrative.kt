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

package tech.libeufin.nexus.ebics

import org.w3c.dom.Document
import tech.libeufin.common.crypto.CryptoUtil
import tech.libeufin.common.*
import tech.libeufin.nexus.*
import tech.libeufin.nexus.BankPublicKeysFile
import tech.libeufin.nexus.ClientPrivateKeysFile
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.util.*
import javax.xml.datatype.DatatypeFactory
import java.security.interfaces.*
import tech.libeufin.nexus.ebics.EbicsKeyMng.Order.*

data class VersionNumber(val number: Float, val schema: String) {
    override fun toString(): String = "$number:$schema"
}

data class AccountInfo(
    val currency: String?,
    val iban: String?,
    val name: String?
)

object EbicsAdministrative {
    fun HEV(cfg: NexusConfig): ByteArray {
        return XmlBuilder.toBytes("ebicsHEVRequest") {
            attr("xmlns", "http://www.ebics.org/H000")
            el("HostID", cfg.ebicsHostId)
        }
    }

    fun parseHEV(doc: Document): EbicsResponse<List<VersionNumber>> {
        return XmlDestructor.fromDoc(doc, "ebicsHEVResponse") {
            val technicalCode = one("SystemReturnCode") {
                EbicsReturnCode.lookup(one("ReturnCode").text())
            }
            val versions = map("VersionNumber") {
                VersionNumber(text().toFloat(), attr("ProtocolVersion"))
            }
            EbicsResponse(
                technicalCode = technicalCode, 
                bankCode = EbicsReturnCode.EBICS_OK,
                content = versions
            )
        }
    }

    fun parseHKD(stream: InputStream): AccountInfo { 
        return XmlDestructor.fromStream(stream, "HKDResponseOrderData") {
            var currency: String? = null
            var iban: String? = null
            var name: String? = null
            one("PartnerInfo") {
                name = opt("AddressInfo")?.one("Name")?.text()
                opt("AccountInfo") {
                    currency = attr("Currency")
                    each("AccountNumber") {
                        if (attr("international") == "true") {
                            iban = text()
                        }
                    }
                }
            }
            AccountInfo(currency, iban, name)
        }
    }
}
