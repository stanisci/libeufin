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

package tech.libeufin.common

import io.ktor.http.*
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
import java.net.URI
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

sealed class CommonError(msg: String): Exception(msg) {
    class AmountFormat(msg: String): CommonError(msg)
    class AmountNumberTooBig(msg: String): CommonError(msg)
    class Payto(msg: String): CommonError(msg)
}


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
            throw CommonError.AmountFormat("Invalid amount format")
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
                throw CommonError.AmountFormat("Fractional value specified in amount is too large")
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
        const val MAX_VALUE = 4503599627370496L // 2^52
        private val PATTERN = Regex("([A-Z]{1,11}):([0-9]+)(?:\\.([0-9]{1,8}))?")
    }
}

@JvmInline
value class IBAN private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val SEPARATOR = Regex("[\\ \\-]")

        fun parse(raw: String): IBAN {
            val iban: String = raw.uppercase().replace(SEPARATOR, "")
            val builder = StringBuilder(iban.length + iban.asSequence().map { if (it.isDigit()) 1 else 2 }.sum())
            (iban.subSequence(4, iban.length).asSequence() + iban.subSequence(0, 4).asSequence()).forEach {
                if (it.isDigit()) {
                    builder.append(it)
                } else {
                    builder.append((it.code - 'A'.code) + 10)
                }
            }
            val str = builder.toString()
            val mod = str.toBigInteger().mod(97.toBigInteger()).toInt()
            if (mod != 1) throw CommonError.Payto("Iban malformed, modulo is $mod expected 1")
            return IBAN(iban)
        }

        fun rand(): IBAN {
            val ccNoCheck = "131400" // DE00
            val bban = (0..10).map {
                (0..9).random()
            }.joinToString("") // 10 digits account number
            var checkDigits: String = "98".toBigInteger().minus("$bban$ccNoCheck".toBigInteger().mod("97".toBigInteger())).toString()
            if (checkDigits.length == 1) {
                checkDigits = "0${checkDigits}"
            }
            return IBAN("DE$checkDigits$bban")
        }
    }
}

@Serializable(with = Payto.Serializer::class)
sealed class Payto {
    abstract val parsed: URI
    abstract val canonical: String
    abstract val amount: TalerAmount?
    abstract val message: String?
    abstract val receiverName: String?

    /** Transform a payto URI to its bank form, using [name] as the receiver-name and the bank [ctx] */
    fun bank(name: String, ctx: BankPaytoCtx): String = when (this) {
        is IbanPayto -> IbanPayto.build(iban.toString(), ctx.bic, name)
        is XTalerBankPayto -> "payto://x-taler-bank/${ctx.hostname ?: "localhost"}/$username?receiver-name=${name.encodeURLParameter()}"
    }

    fun expectIban(): IbanPayto {
        return when (this) {
            is IbanPayto -> this
            else -> throw CommonError.Payto("expected an IBAN payto URI got '${parsed.host}'")
        }
    }

    fun expectXTalerBank(): XTalerBankPayto {
        return when (this) {
            is XTalerBankPayto -> this
            else -> throw CommonError.Payto("expected a x-taler-bank payto URI got '${parsed.host}'")
        }
    }

    internal object Serializer : KSerializer<Payto> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Payto", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Payto) {
            encoder.encodeString(value.toString())
        }

        override fun deserialize(decoder: Decoder): Payto {
            return parse(decoder.decodeString())
        }
    }

    companion object {
        fun parse(raw: String): Payto {
            val parsed = try {
                URI(raw)
            } catch (e: Exception) {
                throw CommonError.Payto("expected a valid URI")
            }
            if (parsed.scheme != "payto") throw CommonError.Payto("expect a payto URI got '${parsed.scheme}'")

            val params = parseQueryString(parsed.query ?: "")
            val amount = params["amount"]?.run { TalerAmount(this) }
            val message = params["message"]
            val receiverName = params["receiver-name"]

            return when (parsed.host) {
                "iban" -> {
                    val splitPath = parsed.path.split("/", limit=3).filter { it.isNotEmpty() }
                    val (bic, rawIban) = when (splitPath.size) {
                        1 -> Pair(null, splitPath[0])
                        2 -> Pair(splitPath[0], splitPath[1])
                        else -> throw CommonError.Payto("too many path segments for an IBAN payto URI")
                    }
                    val iban = IBAN.parse(rawIban)
                    IbanPayto(
                        parsed, 
                        "payto://iban/$iban",
                        amount, 
                        message,
                        receiverName,
                        bic,
                        iban
                    )
                }
                "x-taler-bank" -> {
                    val splitPath = parsed.path.split("/", limit=3).filter { it.isNotEmpty() }
                    if (splitPath.size != 2)
                        throw CommonError.Payto("bad number of path segments for a x-taler-bank payto URI")
                    val username = splitPath[1]
                    XTalerBankPayto(
                        parsed, 
                        "payto://x-taler-bank/localhost/$username",
                        amount, 
                        message,
                        receiverName,
                        username
                    )
                }
                else -> throw CommonError.Payto("unsupported payto URI kind '${parsed.host}'")
            }
        }
    }
}

@Serializable(with = IbanPayto.Serializer::class)
class IbanPayto internal constructor(
    override val parsed: URI,
    override val canonical: String,
    override val amount: TalerAmount?,
    override val message: String?,
    override val receiverName: String?,
    val bic: String?,
    val iban: IBAN
): Payto() {

    override fun toString(): String = parsed.toString()

    /** Transform an IBAN payto URI to its full form, using [defaultName] if receiver-name is missing */
    fun full(defaultName: String): String = build(iban.toString(), bic, receiverName ?: defaultName)

    internal object Serializer : KSerializer<IbanPayto> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("IbanPayto", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: IbanPayto) {
            encoder.encodeString(value.toString())
        }

        override fun deserialize(decoder: Decoder): IbanPayto {
            return parse(decoder.decodeString()).expectIban()
        }
    }

    companion object {
        fun build(iban: String, bic: String?, name: String?): String {
            val bic = if (bic != null) "$bic/" else ""
            val name = if (name != null) "?receiver-name=${name.encodeURLParameter()}" else ""
            return "payto://iban/$bic$iban$name"
        }

        fun rand(): IbanPayto {
            return parse("payto://iban/SANDBOXX/${IBAN.rand()}").expectIban()
        }
    }
}

class XTalerBankPayto internal constructor(
    override val parsed: URI,
    override val canonical: String,
    override val amount: TalerAmount?,
    override val message: String?,
    override val receiverName: String?,
    val username: String
): Payto() {
    override fun toString(): String = parsed.toString()

    companion object {
        fun forUsername(username: String): XTalerBankPayto {
            return parse("payto://x-taler-bank/hostname/$username").expectXTalerBank()
        }
    }
}

/** Context specific data necessary to create a bank payto URI from a canonical payto URI */
data class BankPaytoCtx(
    val bic: String? = null,
    val hostname: String? = null
)


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
        require(decoded != null && decoded.size == 32) {
            "expected 32 bytes encoded in Crockford's base32"
        }
        this.raw = decoded
        this.encoded = encoded
    }
    constructor(raw: ByteArray) {
        require(raw.size == 32) {
            "encoded data should be 32 bytes long"
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

    companion object {
        fun rand(): Base32Crockford32B = Base32Crockford32B(ByteArray(32).rand())
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
        
        require(decoded != null && decoded.size == 64) {
            "expected 64 bytes encoded in Crockford's base32"
        }
        this.raw = decoded
        this.encoded = encoded
    }
    constructor(raw: ByteArray) {
        require(raw.size == 64) {
            "encoded data should be 64 bytes long"
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

    companion object {
        fun rand(): Base32Crockford64B = Base32Crockford64B(ByteArray(64).rand())
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