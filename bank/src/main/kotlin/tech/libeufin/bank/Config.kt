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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.common.*
import java.nio.file.Path

private val logger: Logger = LoggerFactory.getLogger("libeufin-bank")

/**
 * Application the parsed configuration.
 */
data class BankConfig(
    val regionalCurrency: String,
    val regionalCurrencySpec: CurrencySpecification,
    val allowRegistration: Boolean,
    val allowAccountDeletion: Boolean,
    val allowEditName: Boolean,
    val allowEditCashout: Boolean,
    val defaultDebtLimit: TalerAmount,
    val registrationBonus: TalerAmount,
    val suggestedWithdrawalExchange: String?,
    val allowConversion: Boolean,
    val fiatCurrency: String?,
    val fiatCurrencySpec: CurrencySpecification?,
    val spaPath: Path?,
    val tanChannels: Map<TanChannel, Path>,
    val payto: BankPaytoCtx,
    val wireMethod: WireMethod
)

@Serializable
data class ConversionRate (
    val cashin_ratio: DecimalNumber,
    val cashin_fee: TalerAmount,
    val cashin_tiny_amount: TalerAmount,
    val cashin_rounding_mode: RoundingMode,
    val cashin_min_amount: TalerAmount,
    val cashout_ratio: DecimalNumber,
    val cashout_fee: TalerAmount,
    val cashout_tiny_amount: TalerAmount,
    val cashout_rounding_mode: RoundingMode,
    val cashout_min_amount: TalerAmount,
)

sealed interface ServerConfig {
    data class Unix(val path: String, val mode: Int): ServerConfig
    data class Tcp(val port: Int): ServerConfig
}

fun talerConfig(configPath: Path?): TalerConfig = BANK_CONFIG_SOURCE.fromFile(configPath)

fun TalerConfig.loadDbConfig(): DatabaseConfig  {
    return DatabaseConfig(
        dbConnStr = requireString("libeufin-bankdb-postgres", "config"),
        sqlDir = requirePath("libeufin-bankdb-postgres", "sql_dir")
    )
}

fun TalerConfig.loadServerConfig(): ServerConfig {
    return when (val method = requireString("libeufin-bank", "serve")) {
        "tcp" -> ServerConfig.Tcp(requireNumber("libeufin-bank", "port"))
        "unix" -> ServerConfig.Unix(requireString("libeufin-bank", "unixpath"), requireNumber("libeufin-bank", "unixpath_mode"))
        else -> throw TalerConfigError.invalid("server method", "libeufin-bank", "serve", "expected 'tcp' or 'unix' got '$method'")
    }
}

fun TalerConfig.loadBankConfig(): BankConfig {
    val regionalCurrency = requireString("libeufin-bank", "currency")
    var fiatCurrency: String? = null
    var fiatCurrencySpec: CurrencySpecification? = null
    val allowConversion = lookupBoolean("libeufin-bank", "allow_conversion") ?: false
    if (allowConversion) {
        fiatCurrency = requireString("libeufin-bank", "fiat_currency")
        fiatCurrencySpec = currencySpecificationFor(fiatCurrency) 
    }
    val tanChannels = buildMap {
        for (channel in TanChannel.entries) {
            lookupPath("libeufin-bank", "tan_$channel")?.let {
                put(channel, it)
            }
        }
    }
    val method = when (val type = lookupString("libeufin-bank", "wire_type")) {
        "iban" -> WireMethod.IBAN
        "x-taler-bank" -> WireMethod.X_TALER_BANK
        null -> {
            val err = TalerConfigError.missing("payment target type", "libeufin-bank", "wire_type").message
            logger.warn("$err, defaulting to 'iban' but will fail in a future update")
            WireMethod.IBAN
        }
        else -> throw TalerConfigError.invalid("payment target type", "libeufin-bank", "wire_type", "expected 'iban' or 'x-taler-bank' got '$type'")
    }
    val payto = BankPaytoCtx(
        bic = lookupString("libeufin-bank", "iban_payto_bic"),
        hostname = lookupString("libeufin-bank", "x_taler_bank_payto_hostname")
    )
    when (method) {
        WireMethod.IBAN -> if (payto.bic == null) {
            logger.warn(TalerConfigError.missing("BIC", "libeufin-bank", "iban_payto_bic").message + " will fail in a future update")
        }
        WireMethod.X_TALER_BANK -> if (payto.hostname == null) {
            logger.warn(TalerConfigError.missing("hostname", "libeufin-bank", "x_taler_bank_payto_hostname").message + " will fail in a future update")
        }
    }
    return BankConfig(
        regionalCurrency = regionalCurrency,
        regionalCurrencySpec =  currencySpecificationFor(regionalCurrency),
        allowRegistration = lookupBoolean("libeufin-bank", "allow_registration") ?: false,
        allowAccountDeletion = lookupBoolean("libeufin-bank", "allow_account_deletion") ?: false,
        allowEditName = lookupBoolean("libeufin-bank", "allow_edit_name") ?: false,
        allowEditCashout = lookupBoolean("libeufin-bank", "allow_edit_cashout_payto_uri") ?: false,
        defaultDebtLimit = amount("libeufin-bank", "default_debt_limit", regionalCurrency) ?: TalerAmount(0, 0, regionalCurrency),
        registrationBonus = amount("libeufin-bank", "registration_bonus", regionalCurrency) ?: TalerAmount(0, 0, regionalCurrency),
        suggestedWithdrawalExchange = lookupString("libeufin-bank", "suggested_withdrawal_exchange"),
        spaPath = lookupPath("libeufin-bank", "spa"),
        allowConversion = allowConversion,
        fiatCurrency = fiatCurrency,
        fiatCurrencySpec = fiatCurrencySpec,
        tanChannels = tanChannels,
        payto = payto,
        wireMethod = method
    )
}

fun String.notEmptyOrNull(): String? = if (isEmpty()) null else this

fun TalerConfig.currencySpecificationFor(currency: String): CurrencySpecification
    = sections.find {
        it.startsWith("CURRENCY-") && requireBoolean(it, "enabled") && requireString(it, "code") == currency
    }?.let { loadCurrencySpecification(it) } ?: throw TalerConfigError.generic("missing currency specification for $currency")

private fun TalerConfig.loadCurrencySpecification(section: String): CurrencySpecification {
    return CurrencySpecification(
        name = requireString(section, "name"),
        num_fractional_input_digits = requireNumber(section, "fractional_input_digits"),
        num_fractional_normal_digits = requireNumber(section, "fractional_normal_digits"),
        num_fractional_trailing_zero_digits = requireNumber(section, "fractional_trailing_zero_digits"),
        alt_unit_names = Json.decodeFromString(requireString(section, "alt_unit_names"))
    )
}

private fun TalerConfig.amount(section: String, option: String, currency: String): TalerAmount? {
    val raw = lookupString(section, option) ?: return null
    val amount = try {
        TalerAmount(raw)
    } catch (e: Exception) {
        throw TalerConfigError.invalid("amount", section, option, "amount '$raw' is malformed")
    }

    if (amount.currency != currency) {
        throw TalerConfigError.invalid("amount", section, option, "expected currency $currency got ${amount.currency}")
    }
    return amount
}

private fun TalerConfig.requireAmount(section: String, option: String, currency: String): TalerAmount =
    amount(section, option, currency) ?: throw TalerConfigError.missing("amount", section, option)

private fun TalerConfig.decimalNumber(section: String, option: String): DecimalNumber? {
    val raw = lookupString(section, option) ?: return null
    try {
        return DecimalNumber(raw)
    } catch (e: Exception) {
        throw TalerConfigError.invalid("decimal number", section, option, "number '$raw' is malformed")
    }
}

private fun TalerConfig.requireDecimalNumber(section: String, option: String): DecimalNumber
    = decimalNumber(section, option) ?: throw TalerConfigError.missing("decimal number", section, option)