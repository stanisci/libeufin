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


// TODO import missing using a script
@Suppress("SpellCheckingInspection")
enum class EbicsReturnCode(val code: String) {
    EBICS_OK("000000"),
    EBICS_DOWNLOAD_POSTPROCESS_DONE("011000"),
    EBICS_DOWNLOAD_POSTPROCESS_SKIPPED("011001"),
    EBICS_TX_SEGMENT_NUMBER_UNDERRUN("011101"),
    EBICS_AUTHENTICATION_FAILED("061001"),
    EBICS_INVALID_REQUEST("061002"),
    EBICS_INTERNAL_ERROR("061099"),
    EBICS_TX_RECOVERY_SYNC("061101"),
    EBICS_AUTHORISATION_ORDER_IDENTIFIER_FAILED("090003"),
    EBICS_INVALID_ORDER_DATA_FORMAT("090004"),
    EBICS_NO_DOWNLOAD_DATA_AVAILABLE("090005"),

    // Transaction administration
    EBICS_INVALID_USER_OR_USER_STATE("091002"),
    EBICS_USER_UNKNOWN("091003"),
    EBICS_INVALID_USER_STATE("091004"),
    EBICS_INVALID_ORDER_IDENTIFIER("091005"),
    EBICS_UNSUPPORTED_ORDER_TYPE("091006"),
    EBICS_INVALID_XML("091010"),
    EBICS_TX_MESSAGE_REPLAY("091103"),
    EBICS_TX_SEGMENT_NUMBER_EXCEEDED("091104"), 
    EBICS_INVALID_REQUEST_CONTENT("091113"),
    EBICS_PROCESSING_ERROR("091116"),

    // Key-Management errors
    EBICS_KEYMGMT_UNSUPPORTED_VERSION_SIGNATURE("091201"),
    EBICS_KEYMGMT_UNSUPPORTED_VERSION_AUTHENTICATION("091202"),
    EBICS_KEYMGMT_UNSUPPORTED_VERSION_ENCRYPTION("091203"),
    EBICS_KEYMGMT_KEYLENGTH_ERROR_SIGNATURE("091204"),
    EBICS_KEYMGMT_KEYLENGTH_ERROR_AUTHENTICATION("091205"),
    EBICS_KEYMGMT_KEYLENGTH_ERROR_ENCRYPTION("091206"),
    EBICS_X509_CERTIFICATE_EXPIRED("091208"),
    EBICS_X509_CERTIFICATE_NOT_VALID_YET("091209"),
    EBICS_X509_WRONG_KEY_USAGE("091210"),
    EBICS_X509_WRONG_ALGORITHM("091211"),
    EBICS_X509_INVALID_THUMBPRINT("091212"),
    EBICS_X509_CTL_INVALID("091213"),
    EBICS_X509_UNKNOWN_CERTIFICATE_AUTHORITY("091214"),
    EBICS_X509_INVALID_POLICY("091215"),
    EBICS_X509_INVALID_BASIC_CONSTRAINTS("091216"),
    EBICS_ONLY_X509_SUPPORT("091217"),
    EBICS_KEYMGMT_DUPLICATE_KEY("091218"),
    EBICS_CERTIFICATE_VALIDATION_ERROR("091219"),
    
    // Pre-erification errors
    EBICS_SIGNATURE_VERIFICATION_FAILED("091301"),
    EBICS_ACCOUNT_AUTHORISATION_FAILED("091302"),
    EBICS_AMOUNT_CHECK_FAILED("091303"),
    EBICS_SIGNER_UNKNOWN("091304"),
    EBICS_INVALID_SIGNER_STATE("091305"),
    EBICS_DUPLICATE_SIGNATURE("091306");

    enum class Kind {
        Information,
        Note,
        Warning,
        Error
    }

    fun kind(): Kind {
        return when (val errorClass = code.substring(0..1)) {
            "00" -> Kind.Information
            "01" -> Kind.Note
            "03" -> Kind.Warning
            "06", "09" -> Kind.Error
            else -> throw Exception("Unknown EBICS status code error class: $errorClass")
        }
    }

    companion object {
        fun lookup(code: String): EbicsReturnCode {
            for (x in entries) {
                if (x.code == code) {
                    return x
                }
            }
            throw Exception(
                "Unknown EBICS status code: $code"
            )
        }
    }
}