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

import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.serialization.Serializable
import net.taler.wallet.crypto.Base32Crockford
import net.taler.wallet.crypto.EncodingException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import net.taler.common.errorcodes.TalerErrorCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.TalerCommon")
const val MAX_SAFE_INTEGER = 9007199254740991L; // 2^53 - 1

/**
 * 32-byte Crockford's Base32 encoded data.
 */
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

    override fun equals(other: Any?) = (other is Base32Crockford32B) && Arrays.equals(raw, other.raw)

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

/**
 * 64-byte Crockford's Base32 encoded data.
 */
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

    override fun equals(other: Any?) = (other is Base32Crockford64B) && Arrays.equals(raw, other.raw)

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

/** 32-byte hash code. */
typealias ShortHashCode = Base32Crockford32B;
/** 64-byte hash code. */
typealias HashCode = Base32Crockford64B;
/**
 * EdDSA and ECDHE public keys always point on Curve25519
 * and represented  using the standard 256 bits Ed25519 compact format,
 * converted to Crockford Base32.
 */
typealias EddsaPublicKey = Base32Crockford32B;

/**
 * Timestamp containing the number of seconds since epoch.
 */
@Serializable
data class TalerProtocolTimestamp(
    @Serializable(with = TalerProtocolTimestamp.Serializer::class)
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
                ts > Instant.MAX.getEpochSecond() -> throw badRequest("Timestamp $ts too big to be represented in Kotlin")
                else -> return Instant.ofEpochSecond(ts)
            }
        }
    
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    }
}

/**
 * Represents a Taler amount.  This type can be used both
 * to hold database records and amounts coming from the parser.
 * If maybeCurrency is null, then the constructor defaults it
 * to be the "internal currency".  Internal currency is the one
 * with which Libeufin-Bank moves funds within itself, therefore
 * not to be mistaken with the cashout currency, which is the one
 * that gets credited to Libeufin-Bank users to their cashout_payto_uri.
 *
 * maybeCurrency is typically null when the TalerAmount object gets
 * defined by the Database class.
 */
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
        fun badAmount(hint: String): Exception = 
            badRequest(hint, TalerErrorCode.TALER_EC_BANK_BAD_FORMAT_AMOUNT)

        if (encoded.isEmpty()) throw badAmount("Empty amount")
        val currencySplit: List<String> = encoded.split(':', limit = 2)
        if (currencySplit.size != 2) throw badAmount("Missing value")
        currency = currencySplit[0].trimStart()
        if (currency.length > 12) throw badAmount("Currency too big")
        val dotSplit: List<String> = currencySplit[1].split('.', limit = 2)

        value = dotSplit[0].toLongOrNull() ?: throw badAmount("Invalid value")
        if (value > MAX_SAFE_INTEGER) throw badAmount("Value specified in amount is too large")

        if (dotSplit.size == 2) {
            if (dotSplit[1].length > 8) throw badAmount("Fractional value too precise")
            var tmp: Int =  dotSplit[1].toIntOrNull() ?: throw badAmount("Invalid fractional value")
            repeat(8 - dotSplit[1].length) {
                tmp *= 10
            }
            frac = tmp
        } else {
            frac = 0
        }
    }

    fun normalize(): TalerAmount {
        if (frac > FRACTION_BASE) {
            val overflow = frac / FRACTION_BASE
            val normalFrac = frac % FRACTION_BASE
            val normalValue = value + overflow
            if (normalValue < overflow || normalValue > MAX_SAFE_INTEGER)
                throw badRequest("Amount value overflowed")
            return TalerAmount(
                value = normalValue, frac = normalFrac, currency = currency
            )
        }
        return this
    }

    operator fun plus(other: TalerAmount): TalerAmount {
        if (currency != other.currency) throw badRequest(
            "Currency mismatch, balance '$currency', price '${other.currency}'",
            TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
        )
        val valueAdd = value + other.value
        if (valueAdd < value || valueAdd > MAX_SAFE_INTEGER) throw badRequest("Amount value overflowed")
        val fracAdd = frac + other.frac
        if (fracAdd < frac) throw badRequest("Amount fraction overflowed")
        return TalerAmount(
            value = valueAdd, frac = fracAdd, currency = currency
        ).normalize()
    }

    override fun equals(other: Any?): Boolean {
        return other is TalerAmount &&
                other.value == this.value &&
                other.frac == this.frac &&
                other.currency == this.currency
    }

    override fun toString(): String {
        val fracNoTrailingZero = this.frac.toString().dropLastWhile { it == '0' }
        if (fracNoTrailingZero.isEmpty()) return "$currency:$value"
        return "$currency:$value.$fracNoTrailingZero"
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
    }
}


/**
 * Internal representation of relative times.  The
 * "forever" case is represented with Long.MAX_VALUE.
 */
@Serializable()
data class RelativeTime(
    @Serializable(with = RelativeTime.Serializer::class)
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
}