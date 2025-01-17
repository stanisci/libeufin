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

// We will support more dialect in the future

sealed class EbicsOrder(val schema: String) {
    data class V2_5(
        val type: String,
        val attribute: String
    ): EbicsOrder("H004")
    data class V3(
        val type: String,
        val name: String? = null,
        val scope: String? = null,
        val messageName: String? = null,
        val messageVersion: String? = null,
        val container: String? = null,
        val option: String? = null
    ): EbicsOrder("H005")
}

enum class Dialect {
    postfinance,
    gls;

    fun downloadDoc(doc: SupportedDocument, ebics2: Boolean): EbicsOrder {
        return when (this) {
            postfinance -> {
                // TODO test platform need EBICS2 for HAC, should we use a separate dialect ?
                if (ebics2 || doc == SupportedDocument.PAIN_002_LOGS) {
                    when (doc) {
                        SupportedDocument.PAIN_002 -> EbicsOrder.V2_5("Z01", "DZHNN")
                        SupportedDocument.CAMT_052 -> EbicsOrder.V2_5("Z52", "DZHNN")
                        SupportedDocument.CAMT_053 -> EbicsOrder.V2_5("Z53", "DZHNN")
                        SupportedDocument.CAMT_054 -> EbicsOrder.V2_5("Z54", "DZHNN")
                        SupportedDocument.PAIN_002_LOGS -> EbicsOrder.V2_5("HAC", "DZHNN")
                    }
                } else {
                    when (doc) {
                        SupportedDocument.PAIN_002 -> EbicsOrder.V3("BTD", "PSR", "CH", "pain.002", "10", "ZIP")
                        SupportedDocument.CAMT_052 -> EbicsOrder.V3("BTD", "STM", "CH", "camt.052", "08", "ZIP")
                        SupportedDocument.CAMT_053 -> EbicsOrder.V3("BTD", "EOP", "CH", "camt.053", "08", "ZIP")
                        SupportedDocument.CAMT_054 -> EbicsOrder.V3("BTD", "REP", "CH", "camt.054", "08", "ZIP")
                        SupportedDocument.PAIN_002_LOGS -> EbicsOrder.V3("HAC")
                    }
                }
            }
            // TODO for GLS we might have to fetch the same kind of files from multiple orders
            gls -> when (doc) {
                SupportedDocument.PAIN_002 -> EbicsOrder.V3("BTD", "REP", "DE", "pain.002", null, "ZIP", "SCT")
                SupportedDocument.CAMT_052 -> EbicsOrder.V3("BTD", "STM", "DE", "camt.052", null, "ZIP")
                SupportedDocument.CAMT_053 -> EbicsOrder.V3("BTD", "EOP", "DE", "camt.053", null, "ZIP")
                SupportedDocument.CAMT_054 -> EbicsOrder.V3("BTD", "STM", "DE", "camt.054", null, "ZIP", "SCI")
                SupportedDocument.PAIN_002_LOGS -> EbicsOrder.V3("HAC")
            }
        }
    }

    fun directDebit(): EbicsOrder {
        return when (this) {
            postfinance -> EbicsOrder.V3("BTU", "MCT", "CH", "pain.001", "09")
            gls -> EbicsOrder.V3("BTU", "SCT", null, "pain.001")
        }
    }
}