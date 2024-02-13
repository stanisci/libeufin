/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.

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
import tech.libeufin.common.Base32Crockford
import tech.libeufin.common.EncodingException
import tech.libeufin.common.TalerAmount
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** 32-byte Crockford's Base32 encoded data */
@Serializable(with = Base32Crockford32B.Serializer::class)
class Base32Crockford32B {
    private var encoded: String? = null
    val raw: ByteArray

    constructor(encoded: String) {
        val decoded = try {
            Base32Crockford.decode(encoded) 
        } catch (e: EncodingException) {
            null
        }
        
        require(decoded != null) {
            "Data should be encoded using Crockford's Base32"
        }
        require(decoded.size == 32) {
            "Encoded data should be 32 bytes long"
        }
        this.raw = decoded
        this.encoded = encoded
    }
    constructor(raw: ByteArray) {
        require(raw.size == 32) {
            "Encoded data should be 32 bytes long"
        }
        this.raw = raw
    }

    fun encoded(): String {
        encoded = encoded ?: Base32Crockford.encode(raw)
        return encoded!!
    }

    override fun toString(): String {
        return encoded()
    }

    override fun equals(other: Any?) = (other is Base32Crockford32B) && raw.contentEquals(other.raw)

    internal object Serializer : KSerializer<Base32Crockford32B> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Base32Crockford32B", PrimitiveKind.STRING)
    
        override fun serialize(encoder: Encoder, value: Base32Crockford32B) {
            encoder.encodeString(value.encoded())
        }
    
        override fun deserialize(decoder: Decoder): Base32Crockford32B {
            return Base32Crockford32B(decoder.decodeString())
        }
    }
}

/** 64-byte Crockford's Base32 encoded data */
@Serializable(with = Base32Crockford64B.Serializer::class)
class Base32Crockford64B {
    private var encoded: String? = null
    val raw: ByteArray

    constructor(encoded: String) {
        val decoded = try {
            Base32Crockford.decode(encoded) 
        } catch (e: EncodingException) {
            null
        }
        
        require(decoded != null) {
            "Data should be encoded using Crockford's Base32"
        }
        require(decoded.size == 64) {
            "Encoded data should be 32 bytes long"
        }
        this.raw = decoded
        this.encoded = encoded
    }
    constructor(raw: ByteArray) {
        require(raw.size == 64) {
            "Encoded data should be 32 bytes long"
        }
        this.raw = raw
    }

    fun encoded(): String {
        encoded = encoded ?: Base32Crockford.encode(raw)
        return encoded!!
    }

    override fun toString(): String {
        return encoded()
    }

    override fun equals(other: Any?) = (other is Base32Crockford64B) && raw.contentEquals(other.raw)

    internal object Serializer : KSerializer<Base32Crockford64B> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Base32Crockford64B", PrimitiveKind.STRING)
    
        override fun serialize(encoder: Encoder, value: Base32Crockford64B) {
            encoder.encodeString(value.encoded())
        }
    
        override fun deserialize(decoder: Decoder): Base32Crockford64B {
            return Base32Crockford64B(decoder.decodeString())
        }
    }
}

/** 32-byte hash code */
typealias ShortHashCode = Base32Crockford32B
/** 64-byte hash code */
typealias HashCode = Base32Crockford64B
/**
 * EdDSA and ECDHE public keys always point on Curve25519
 * and represented  using the standard 256 bits Ed25519 compact format,
 * converted to Crockford Base32.
 */
typealias EddsaPublicKey = Base32Crockford32B

/** Timestamp containing the number of seconds since epoch */
@Serializable
data class TalerProtocolTimestamp(
    @Serializable(with = Serializer::class)
    val t_s: Instant,
) {
    companion object {
        fun fromMicroseconds(uSec: Long): TalerProtocolTimestamp {
            return TalerProtocolTimestamp(
                Instant.EPOCH.plus(uSec, ChronoUnit.MICROS)
            )
        }
    }

    internal object Serializer : KSerializer<Instant> {
        override fun serialize(encoder: Encoder, value: Instant) {
            if (value == Instant.MAX) {
                encoder.encodeString("never")
            } else {
                encoder.encodeLong(value.epochSecond)
            }
            
        }
    
        override fun deserialize(decoder: Decoder): Instant {
            val jsonInput = decoder as? JsonDecoder ?: error("Can be deserialized only by JSON")
            val maybeTs = jsonInput.decodeJsonElement().jsonPrimitive
            if (maybeTs.isString) {
                if (maybeTs.content != "never") throw badRequest("Only 'never' allowed for t_s as string, but '${maybeTs.content}' was found")
                return Instant.MAX
            }
            val ts: Long = maybeTs.longOrNull
                ?: throw badRequest("Could not convert t_s '${maybeTs.content}' to a number")
            when {
                ts < 0 -> throw badRequest("Negative timestamp not allowed")
                ts > Instant.MAX.epochSecond -> throw badRequest("Timestamp $ts too big to be represented in Kotlin")
                else -> return Instant.ofEpochSecond(ts)
            }
        }
    
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    }
}

@Serializable(with = DecimalNumber.Serializer::class)
class DecimalNumber {
    val value: Long
    val frac: Int

    constructor(value: Long, frac: Int) {
        this.value = value
        this.frac = frac
    }
    constructor(encoded: String) {
        val match = PATTERN.matchEntire(encoded) ?: throw badRequest("Invalid decimal number format")
        val (value, frac) = match.destructured
        this.value = value.toLongOrNull() ?: throw badRequest("Invalid value")
        if (this.value > TalerAmount.MAX_VALUE) throw badRequest("Value specified in decimal number is too large")
        this.frac = if (frac.isEmpty()) {
            0
        } else {
            var tmp = frac.toIntOrNull() ?: throw badRequest("Invalid fractional value")
            repeat(8 - frac.length) {
                tmp *= 10
            }
            tmp
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is DecimalNumber &&
                other.value == this.value &&
                other.frac == this.frac
    }

    override fun toString(): String {
        if (frac == 0) {
            return "$value"
        } else {
            return "$value.${frac.toString().padStart(8, '0')}"
                .dropLastWhile { it == '0' } // Trim useless fractional trailing 0
        }
    }

    internal object Serializer : KSerializer<DecimalNumber> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("DecimalNumber", PrimitiveKind.STRING)
    
        override fun serialize(encoder: Encoder, value: DecimalNumber) {
            encoder.encodeString(value.toString())
        }
    
        override fun deserialize(decoder: Decoder): DecimalNumber {
            return DecimalNumber(decoder.decodeString())
        }
    }

    companion object {
        private val PATTERN = Regex("([0-9]+)(?:\\.([0-9]{1,8}))?")
    }
}


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


@Serializable(with = ExchangeUrl.Serializer::class)
class ExchangeUrl {
    val url: String

    constructor(raw: String) {
        url = URL(raw).toString()
    }

    override fun toString(): String = url

    internal object Serializer : KSerializer<ExchangeUrl> {
        override val descriptor: SerialDescriptor =
                PrimitiveSerialDescriptor("ExchangeUrl", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: ExchangeUrl) {
            encoder.encodeString(value.toString())
        }

        override fun deserialize(decoder: Decoder): ExchangeUrl {
            return ExchangeUrl(decoder.decodeString())
        }
    }
}