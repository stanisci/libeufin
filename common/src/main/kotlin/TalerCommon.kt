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

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*
import io.ktor.http.*
import java.net.URI

sealed class CommonError(msg: String): Exception(msg) {
    class AmountFormat(msg: String): CommonError(msg)
    class AmountNumberTooBig(msg: String): CommonError(msg)
    class IbanPayto(msg: String): CommonError(msg)
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

@JvmInline
value class IBAN private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val SEPARATOR = Regex("[\\ \\-]");

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
            val mod = str.toBigInteger().mod(97.toBigInteger()).toInt();
            if (mod != 1) throw CommonError.IbanPayto("Iban malformed, modulo is $mod expected 1")
            return IBAN(iban)
        }
    }
}


sealed class PaytoUri {
    abstract val amount: TalerAmount?
    abstract val message: String?
    abstract val receiverName: String?
}

// TODO x-taler-bank Payto

@Serializable(with = IbanPayto.Serializer::class)
class IbanPayto: PaytoUri {
    val parsed: URI
    val canonical: String
    val iban: IBAN
    override val amount: TalerAmount?
    override val message: String?
    override val receiverName: String?

    // TODO maybe add a fster builder that performs less expensive checks when the payto is from the database ?

    constructor(raw: String) {
        try {
            parsed = URI(raw)
        } catch (e: Exception) {
            throw CommonError.IbanPayto("expecteda valid URI")
        }
        
        if (parsed.scheme != "payto") throw CommonError.IbanPayto("expect a payto URI")
        if (parsed.host != "iban") throw CommonError.IbanPayto("expect a IBAN payto URI")

        val splitPath = parsed.path.split("/").filter { it.isNotEmpty() }
        val rawIban = when (splitPath.size) {
            1 -> splitPath[0]
            2 -> splitPath[1]
            else -> throw CommonError.IbanPayto("too many path segments")
        }
        iban = IBAN.parse(rawIban)
        canonical = "payto://iban/$iban"
    
        val params = (parsed.query ?: "").parseUrlEncodedParameters();
        amount = params["amount"]?.run { TalerAmount(this) }
        message = params["message"]
        receiverName = params["receiver-name"]
    }

    /** Full IBAN payto with receiver-name parameter set to [name] */
    fun withName(name: String): FullIbanPayto = FullIbanPayto(this, name)

    /** Full IBAN payto with receiver-name parameter if present */
    fun maybeFull(): FullIbanPayto? {
        return withName(receiverName ?: return null)
    }

    /** Full IBAN payto with receiver-name parameter set to [defaultName] if absent */
    fun full(defaultName: String): FullIbanPayto = withName(receiverName ?: defaultName)

    /** Full IBAN payto with receiver-name parameter if present, fail if absent */
    fun requireFull(): FullIbanPayto {
        return maybeFull() ?: throw Exception("Missing receiver-name")
    }    

    override fun toString(): String = canonical

    internal object Serializer : KSerializer<IbanPayto> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("IbanPayto", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: IbanPayto) {
            encoder.encodeString(value.parsed.toString())
        }

        override fun deserialize(decoder: Decoder): IbanPayto {
            return IbanPayto(decoder.decodeString())
        }
    }
}

@Serializable(with = FullIbanPayto.Serializer::class)
class FullIbanPayto(val payto: IbanPayto, val receiverName: String) {
    val full = payto.canonical + "?receiver-name=" + receiverName.encodeURLParameter()

    override fun toString(): String = full

    internal object Serializer : KSerializer<FullIbanPayto> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("IbanPayto", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: FullIbanPayto) {
            encoder.encodeString(value.full)
        }

        override fun deserialize(decoder: Decoder): FullIbanPayto {
            return IbanPayto(decoder.decodeString()).requireFull()
        }
    }
}