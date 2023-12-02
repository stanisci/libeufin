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

import ConfigSource
import TalerConfig
import TalerConfigError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.DatabaseConfig

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.Config")

/**
 * Application the parsed configuration.
 */
data class BankConfig(
    val regionalCurrency: String,
    val regionalCurrencySpec: CurrencySpecification,
    val allowRegistration: Boolean,
    val allowAccountDeletion: Boolean,
    val defaultDebtLimit: TalerAmount,
    val registrationBonus: TalerAmount,
    val suggestedWithdrawalExchange: String?,
    /**
     * URL where the user should be redirected to complete the captcha.
     * It can contain the substring "{woid}" that is going to be replaced
     * with the withdrawal operation id and should point where the bank
     * SPA is located.
     */
    val spaCaptchaURL: String?,
    val allowConversion: Boolean,
    val fiatCurrency: String?,
    val fiatCurrencySpec: CurrencySpecification?,
    val tanSms: String?,
    val tanEmail: String?,
    val spaPath: String?
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

sealed class ServerConfig {
    data class Unix(val path: String, val mode: Int): ServerConfig()
    data class Tcp(val port: Int): ServerConfig()
}

fun talerConfig(configPath: String?): TalerConfig {
    val config = TalerConfig(BANK_CONFIG_SOURCE)
    config.load(configPath)
    return config
}

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
        else -> throw Exception("Unknown server method '$method' expected 'tcp' or 'unix'")
    }
}

fun TalerConfig.loadBankConfig(): BankConfig  {
    val regionalCurrency = requireString("libeufin-bank", "currency")
    var fiatCurrency: String? = null;
    var fiatCurrencySpec: CurrencySpecification? = null
    val allowConversion = lookupBoolean("libeufin-bank", "allow_conversion") ?: false;
    if (allowConversion) {
        fiatCurrency = requireString("libeufin-bank", "fiat_currency");
        fiatCurrencySpec = currencySpecificationFor(fiatCurrency) 
    }
    return BankConfig(
        regionalCurrency = regionalCurrency,
        regionalCurrencySpec =  currencySpecificationFor(regionalCurrency),
        allowRegistration = lookupBoolean("libeufin-bank", "allow_registration") ?: false,
        allowAccountDeletion = lookupBoolean("libeufin-bank", "allow_account_deletion") ?: false,
        defaultDebtLimit = amount("libeufin-bank", "default_debt_limit", regionalCurrency) ?: TalerAmount(0, 0, regionalCurrency),
        registrationBonus = amount("libeufin-bank", "registration_bonus", regionalCurrency) ?: TalerAmount(0, 0, regionalCurrency),
        suggestedWithdrawalExchange = lookupString("libeufin-bank", "suggested_withdrawal_exchange"),
        spaCaptchaURL = lookupString("libeufin-bank", "spa_captcha_url"),
        spaPath = lookupPath("libeufin-bank", "spa"),
        allowConversion = allowConversion,
        fiatCurrency = fiatCurrency,
        fiatCurrencySpec = fiatCurrencySpec,
        tanSms = lookupPath("libeufin-bank", "tan_sms")?.notEmptyOrNull(),
        tanEmail = lookupPath("libeufin-bank", "tan_email")?.notEmptyOrNull(),
    )
}

fun String.notEmptyOrNull(): String? = if (isEmpty()) null else this

fun TalerConfig.currencySpecificationFor(currency: String): CurrencySpecification
    = sections.find {
        it.startsWith("CURRENCY-") && requireBoolean(it, "enabled") && requireString(it, "code") == currency
    }?.let { loadCurrencySpecification(it) } ?: throw TalerConfigError("missing currency specification for $currency")

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
    val amountStr = lookupString(section, option) ?: return null
    val amount = try {
        TalerAmount(amountStr)
    } catch (e: Exception) {
        throw TalerConfigError("expected amount for section $section, option $option, but amount is malformed")
    }

    if (amount.currency != currency) {
        throw TalerConfigError(
            "expected amount for section $section, option $option, but currency is wrong (got ${amount.currency} expected $currency"
        )
    }
    return amount
}

private fun TalerConfig.requireAmount(section: String, option: String, currency: String): TalerAmount =
    amount(section, option, currency) ?:
        throw TalerConfigError("expected amount for section $section, option $option, but config value is empty")

private fun TalerConfig.decimalNumber(section: String, option: String): DecimalNumber? {
    val numberStr = lookupString(section, option) ?: return null
    try {
        return DecimalNumber(numberStr)
    } catch (e: Exception) {
        throw TalerConfigError("expected decimal number for section $section, option $option, but number is malformed")
    }
}

private fun TalerConfig.requireDecimalNumber(section: String, option: String): DecimalNumber
    = decimalNumber(section, option) ?:
        throw TalerConfigError("expected decimal number for section $section, option $option, but config value is empty")

private fun TalerConfig.RoundingMode(section: String, option: String): RoundingMode? {
    val str = lookupString(section, option) ?: return null;
    try {
        return RoundingMode.valueOf(str)
    } catch (e: Exception) {
        throw TalerConfigError("expected rouding mode for section $section, option $option, but $str is unknown")
    }
}