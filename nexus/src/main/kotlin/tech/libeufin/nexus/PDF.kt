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

package tech.libeufin.nexus

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Paragraph
import java.security.interfaces.RSAPrivateCrtKey
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.io.ByteArrayOutputStream
import tech.libeufin.common.crypto.*

/**
 * Generate the PDF document with all the client public keys
 * to be sent on paper to the bank.
 */
fun generateKeysPdf(
    clientKeys: ClientPrivateKeysFile,
    cfg: EbicsSetupConfig
): ByteArray {
    val po = ByteArrayOutputStream()
    val pdfWriter = PdfWriter(po)
    val pdfDoc = PdfDocument(pdfWriter)
    val date = LocalDateTime.now()
    val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

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

    fun writeCommon(doc: Document) {
        doc.add(
            Paragraph(
                """
            Datum: $dateStr
            Host-ID: ${cfg.ebicsHostId}
            User-ID: ${cfg.ebicsUserId}
            Partner-ID: ${cfg.ebicsPartnerId}
            ES version: A006
        """.trimIndent()
            )
        )
    }

    fun writeKey(doc: Document, priv: RSAPrivateCrtKey) {
        val pub = CryptoUtil.RSAPublicFromPrivate(priv)
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
        writeKey(it, clientKeys.signature_private_key)
        it.add(Paragraph("\n"))
        writeSigLine(it)
        it.add(AreaBreak())

        it.add(Paragraph("Authentifikationsschlüssel").setFontSize(24f))
        writeCommon(it)
        it.add(Paragraph("Öffentlicher Schlüssel (Public key for the identification and authentication signature)"))
        writeKey(it, clientKeys.authentication_private_key)
        it.add(Paragraph("\n"))
        writeSigLine(it)
        it.add(AreaBreak())

        it.add(Paragraph("Verschlüsselungsschlüssel").setFontSize(24f))
        writeCommon(it)
        it.add(Paragraph("Öffentlicher Schlüssel (Public encryption key)"))
        writeKey(it, clientKeys.encryption_private_key)
        it.add(Paragraph("\n"))
        writeSigLine(it)
    }
    pdfWriter.flush()
    return po.toByteArray()
}