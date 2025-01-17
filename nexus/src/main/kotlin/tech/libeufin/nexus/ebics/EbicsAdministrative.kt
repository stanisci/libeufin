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
import tech.libeufin.nexus.NexusConfig
import tech.libeufin.nexus.XmlBuilder
import tech.libeufin.nexus.XmlDestructor
import java.io.InputStream

data class VersionNumber(val number: Float, val schema: String) {
    override fun toString(): String = "$number:$schema"
}

data class HKD (
    val account: AccountInfo,
    val orders: List<OrderInfo>
)
data class AccountInfo (
    val currency: String?,
    val iban: String?,
    val name: String?
)
data class OrderInfo (
    val type: String,
    val params: String,
    val description: String,
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

    fun parseHKD(stream: InputStream): HKD { 
        return XmlDestructor.fromStream(stream, "HKDResponseOrderData") {
            one("PartnerInfo") {
                var currency: String? = null
                var iban: String? = null
                val name = opt("AddressInfo")?.one("Name")?.text()
                opt("AccountInfo") {
                    currency = attr("Currency")
                    each("AccountNumber") {
                        if (attr("international") == "true") {
                            iban = text()
                        }
                    }
                }
                val orders = map("OrderInfo") {
                    OrderInfo(
                        one("AdminOrderType").text(),
                        opt("Service") {
                            var params = StringBuilder()
                            opt("ServiceName")?.run {
                                params.append(" ${text()}")
                            }
                            opt("Scope")?.run {
                                params.append(" ${text()}")
                            }
                            opt("ServiceOption")?.run {
                                params.append(" ${text()}")
                            }
                            opt("MsgName")?.run {
                                params.append(" ${text()}")
                            }
                            opt("Container")?.run {
                                params.append(" ${attr("containerType")}")
                            }
                            params.toString()
                        } ?: "",
                        one("Description").text()
                    )
                }
                HKD(AccountInfo(currency, iban, name), orders)
            }
        }
    }
}
