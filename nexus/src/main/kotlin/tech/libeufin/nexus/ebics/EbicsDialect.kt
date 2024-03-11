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

data class Ebics3Service(
    val name: String,
    val scope: String,
    val messageName: String,
    val messageVersion: String,
    val container: String?
)

fun downloadDocService(doc: SupportedDocument): Pair<String, Ebics3Service?> {
    return when (doc) {
        SupportedDocument.PAIN_002 -> Pair("BTD", Ebics3Service("PSR", "CH", "pain.002", "10", "ZIP"))
        SupportedDocument.CAMT_052 -> Pair("BTD", Ebics3Service("STM", "CH", "camt.052", "08", "ZIP"))
        SupportedDocument.CAMT_053 -> Pair("BTD", Ebics3Service("EOP", "CH", "camt.053", "08", "ZIP"))
        SupportedDocument.CAMT_054 -> Pair("BTD", Ebics3Service("REP", "CH", "camt.054", "08", "ZIP"))
        SupportedDocument.PAIN_002_LOGS -> Pair("HAC", null)
    }
}

fun uploadPaymentService(): Ebics3Service {
    return Ebics3Service(
        name = "MCT",
        scope = "CH",
        messageName = "pain.001",
        messageVersion = "09",
        container = null
    )
}
