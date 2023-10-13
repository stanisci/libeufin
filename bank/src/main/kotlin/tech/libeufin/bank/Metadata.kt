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
package tech.libeufin.bank

sealed interface TxMetadata {
    // TODO versioning ?
    companion object {
        fun parse(subject: String): TxMetadata? {
            // IncomingTxMetadata
            try {
                return IncomingTxMetadata(EddsaPublicKey(subject))
            } catch (e: Exception) { }

            // OutgoingTxMetadata
            try {
                val (wtid, exchangeBaseUrl) = subject.split(" ", limit=2) ; 
                return OutgoingTxMetadata(ShortHashCode(wtid), exchangeBaseUrl)
            } catch (e: Exception) { }

            // No well formed metadata
            return null
        }

        fun encode(metadata: TxMetadata): String {
            return when (metadata) {
                is IncomingTxMetadata -> "${metadata.reservePub}"
                is OutgoingTxMetadata -> "${metadata.wtid} ${metadata.exchangeBaseUrl}"
            }
        }
    }
}

data class IncomingTxMetadata(val reservePub: EddsaPublicKey): TxMetadata {
    override fun toString(): String = TxMetadata.encode(this)
}
data class OutgoingTxMetadata(val wtid: ShortHashCode, val exchangeBaseUrl: String): TxMetadata {
    override fun toString(): String = TxMetadata.encode(this)
}