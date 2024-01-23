/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.util

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

sealed class CommonError(msg: String): Exception(msg) {
    class AmountFormat(msg: String): CommonError(msg)
    class AmountNumberTooBig(msg: String): CommonError(msg)
}

@Serializable(with = TalerAmount.Serializer::class)
class TalerAmount {
    val value: Long
    val frac: Int
    val currency: String

    constructor(value: Long, frac: Int, currency: String) {
        this.value = value
        this.frac = frac
        this.currency = currency
    }
    constructor(encoded: String) {
        val match = PATTERN.matchEntire(encoded) ?: 
            throw CommonError.AmountFormat("Invalid amount format");
        val (currency, value, frac) = match.destructured
        this.currency = currency
        this.value = value.toLongOrNull() ?: 
            throw CommonError.AmountFormat("Invalid value")
        if (this.value > MAX_VALUE) 
            throw CommonError.AmountNumberTooBig("Value specified in amount is too large")
        this.frac = if (frac.isEmpty()) {
            0
        } else {
            var tmp = frac.toIntOrNull() ?: 
                throw CommonError.AmountFormat("Invalid fractional value")
            if (tmp > FRACTION_BASE) 
                throw CommonError.AmountFormat("Fractional calue specified in amount is too large")
            repeat(8 - frac.length) {
                tmp *= 10
            }
            tmp
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is TalerAmount &&
                other.value == this.value &&
                other.frac == this.frac &&
                other.currency == this.currency
    }

    override fun toString(): String {
        if (frac == 0) {
            return "$currency:$value"
        } else {
            return "$currency:$value.${frac.toString().padStart(8, '0')}"
                .dropLastWhile { it == '0' } // Trim useless fractional trailing 0
        }
    }

    internal object Serializer : KSerializer<TalerAmount> {
        override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TalerAmount", PrimitiveKind.STRING)
    
        override fun serialize(encoder: Encoder, value: TalerAmount) {
            encoder.encodeString(value.toString())
        }
    
        override fun deserialize(decoder: Decoder): TalerAmount {
            return TalerAmount(decoder.decodeString())
        }
    }

    companion object {
        const val FRACTION_BASE = 100000000
        const val MAX_VALUE = 4503599627370496L; // 2^52
        private val PATTERN = Regex("([A-Z]{1,11}):([0-9]+)(?:\\.([0-9]{1,8}))?");
    }
}