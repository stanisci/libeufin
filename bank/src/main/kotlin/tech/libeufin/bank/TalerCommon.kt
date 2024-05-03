/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023-2024 Taler Systems S.A.

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

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import tech.libeufin.common.*
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Internal representation of relative times.  The
 * "forever" case is represented with Long.MAX_VALUE.
 */
@Serializable()
data class RelativeTime(
    @Serializable(with = Serializer::class)
    val d_us: Duration
) {
    internal object Serializer : KSerializer<Duration> {
        override fun serialize(encoder: Encoder, value: Duration) {
            if (value == ChronoUnit.FOREVER.duration) {
                encoder.encodeString("forever")
            } else {
                encoder.encodeLong(TimeUnit.MICROSECONDS.convert(value))
            }
        }

        override fun deserialize(decoder: Decoder): Duration {
            val jsonInput = decoder as? JsonDecoder ?: error("Can be deserialized only by JSON")
            val maybeDUs = jsonInput.decodeJsonElement().jsonPrimitive
            if (maybeDUs.isString) {
                if (maybeDUs.content != "forever") throw badRequest("Only 'forever' allowed for d_us as string, but '${maybeDUs.content}' was found")
                return ChronoUnit.FOREVER.duration
            }
            val dUs: Long = maybeDUs.longOrNull
                ?: throw badRequest("Could not convert d_us: '${maybeDUs.content}' to a number")
            when {
                dUs < 0 -> throw badRequest("Negative duration specified.")
                dUs > MAX_SAFE_INTEGER -> throw badRequest("d_us value $dUs exceed cap (2^53-1)")
                else -> return Duration.of(dUs, ChronoUnit.MICROS)
            } 
        }

        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    }

    companion object {
        private const val MAX_SAFE_INTEGER = 9007199254740991L // 2^53 - 1
    }
}