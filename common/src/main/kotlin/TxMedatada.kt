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
package tech.libeufin.common

private val BASE32_32B_PATTERN = Regex("[a-z0-9A-Z]{52}")

/** Extract the reserve public key from an incoming Taler transaction subject */
fun parseIncomingTxMetadata(subject: String): EddsaPublicKey {
    val match = BASE32_32B_PATTERN.find(subject)?.value ?: throw Exception("Missing reserve public key")
    return EddsaPublicKey(match)
}

/** Extract the reserve public key from an incoming Taler transaction subject */
fun parseOutgoingTxMetadata(subject: String): Pair<ShortHashCode, ExchangeUrl>  {
    val (wtid, baseUrl) = subject.splitOnce(" ") ?: throw Exception("Malformed outgoing subject")
    return Pair(EddsaPublicKey(wtid), ExchangeUrl(baseUrl))
}