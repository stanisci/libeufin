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
private val BANK_CONFIG_SOURCE = ConfigSource("libeufin-bank", "libeufin-bank")

/**
 * Application the parsed configuration.
 */
data class BankConfig(
    /**
     * Main, regional currency of the bank.
     */
    val regional_currency: CurrencySpecification,
    /**
     * Restrict account registration to the administrator.
     */
    val restrictRegistration: Boolean,
    /**
     * Restrict account deletion to the administrator.
     */
    val restrictAccountDeletion: Boolean,
    /**
     * Default limit for the debt that a customer can have.
     * Can be adjusted per account after account creation.
     */
    val defaultCustomerDebtLimit: TalerAmount,
    /**
     * Debt limit of the admin account.
     */
    val defaultAdminDebtLimit: TalerAmount,
    /**
     * If true, transfer a registration bonus from the admin
     * account to the newly created account.
     */
    val registrationBonusEnabled: Boolean,
    /**
     * Only set if registration bonus is enabled.
     */
    val registrationBonus: TalerAmount?,
    /**
     * Exchange that the bank suggests to wallets for withdrawal.
     */
    val suggestedWithdrawalExchange: String?,
    /**
     * URL where the user should be redirected to complete the captcha.
     * It can contain the substring "{woid}" that is going to be replaced
     * with the withdrawal operation id and should point where the bank
     * SPA is located.
     */
    val spaCaptchaURL: String?,
    val allowConversion: Boolean,
    val fiatCurrency: CurrencySpecification?,
    val conversionInfo: ConversionInfo?,
    val tanSms: String?,
    val tanEmail: String?,
)

@Serializable
data class ConversionInfo (
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

data class ServerConfig(
    val method: String,
    val port: Int
)

fun talerConfig(configPath: String?): TalerConfig = catchError {
    val config = TalerConfig(BANK_CONFIG_SOURCE)
    config.load(configPath)
    config
}

fun TalerConfig.loadDbConfig(): DatabaseConfig = catchError  {
    DatabaseConfig(
        dbConnStr = requireString("libeufin-bankdb-postgres", "config"),
        sqlDir = requirePath("libeufin-bankdb-postgres", "sql_dir")
    )
}

fun TalerConfig.loadServerConfig(): ServerConfig = catchError  {
    ServerConfig(
        method = requireString("libeufin-bank", "serve"),
        port = requireNumber("libeufin-bank", "port")
    )
}

fun TalerConfig.loadBankConfig(): BankConfig = catchError  {
    val currency = requireString("libeufin-bank", "currency")
    var fiatCurrencySpecification: CurrencySpecification? = null
    var conversionInfo: ConversionInfo? = null;
    val allowConversion = lookupBoolean("libeufin-bank", "allow_conversion") ?: false;
    if (allowConversion) {
        val fiatCurrency = requireString("libeufin-bank", "fiat_currency");
        fiatCurrencySpecification = currencySpecificationFor(fiatCurrency) 
        conversionInfo = loadConversionInfo(currency, fiatCurrency)
    }
    BankConfig(
        regional_currency =  currencySpecificationFor(currency),
        restrictRegistration = lookupBoolean("libeufin-bank", "restrict_registration") ?: false,
        defaultCustomerDebtLimit = requireAmount("libeufin-bank", "default_customer_debt_limit", currency),
        registrationBonusEnabled = lookupBoolean("libeufin-bank", "registration_bonus_enabled") ?: false,
        registrationBonus = requireAmount("libeufin-bank", "registration_bonus", currency),
        suggestedWithdrawalExchange = lookupString("libeufin-bank", "suggested_withdrawal_exchange"),
        defaultAdminDebtLimit = requireAmount("libeufin-bank", "default_admin_debt_limit", currency),
        spaCaptchaURL = lookupString("libeufin-bank", "spa_captcha_url"),
        restrictAccountDeletion = lookupBoolean("libeufin-bank", "restrict_account_deletion") ?: true,
        allowConversion = allowConversion,
        fiatCurrency = fiatCurrencySpecification,
        conversionInfo = conversionInfo,
        tanSms = lookupPath("libeufin-bank", "tan_sms"),
        tanEmail = lookupPath("libeufin-bank", "tan_email"),
    )
}

fun TalerConfig.currencySpecificationFor(currency: String): CurrencySpecification = catchError {
    sections.find {
        it.startsWith("CURRENCY-") && requireBoolean(it, "enabled") && requireString(it, "code") == currency
    }?.let { loadCurrencySpecification(it) } ?: throw TalerConfigError("missing currency specification for $currency")
}

private fun TalerConfig.loadConversionInfo(currency: String, fiatCurrency: String): ConversionInfo = catchError {
    ConversionInfo(
        cashin_ratio = requireDecimalNumber("libeufin-bank-conversion", "cashin_ratio"),
        cashin_fee = requireAmount("libeufin-bank-conversion", "cashin_fee", currency),
        cashin_tiny_amount = amount("libeufin-bank-conversion", "cashin_tiny_amount", currency) ?: TalerAmount(0, 1, currency),
        cashin_rounding_mode = RoundingMode("libeufin-bank-conversion", "cashin_rounding_mode") ?: RoundingMode.zero,
        cashin_min_amount = amount("libeufin-bank-conversion", "cashin_min_amount", fiatCurrency) ?: TalerAmount(0, 0, fiatCurrency),
        cashout_ratio = requireDecimalNumber("libeufin-bank-conversion", "cashout_ratio"),
        cashout_fee = requireAmount("libeufin-bank-conversion", "cashout_fee", fiatCurrency),
        cashout_tiny_amount = amount("libeufin-bank-conversion", "cashout_tiny_amount", fiatCurrency) ?: TalerAmount(0, 1, fiatCurrency),
        cashout_rounding_mode = RoundingMode("libeufin-bank-conversion", "cashout_rounding_mode") ?: RoundingMode.zero,
        cashout_min_amount = amount("libeufin-bank-conversion", "cashout_min_amount", currency) ?: TalerAmount(0, 0, currency),
    )
}

private fun TalerConfig.loadCurrencySpecification(section: String): CurrencySpecification = catchError {
    CurrencySpecification(
        name = requireString(section, "name"),
        currency = requireString(section, "code"),
        num_fractional_input_digits = requireNumber(section, "fractional_input_digits"),
        num_fractional_normal_digits = requireNumber(section, "fractional_normal_digits"),
        num_fractional_trailing_zero_digits = requireNumber(section, "fractional_trailing_zero_digits"),
        alt_unit_names = Json.decodeFromString(requireString(section, "alt_unit_names"))
    )
}

private fun TalerConfig.amount(section: String, option: String, currency: String): TalerAmount? = catchError {
    val amountStr = lookupString(section, option) ?: return@catchError null
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
    amount
}

private fun TalerConfig.requireAmount(section: String, option: String, currency: String): TalerAmount = catchError {
    amount(section, option, currency) ?:
        throw TalerConfigError("expected amount for section $section, option $option, but config value is empty")
}

private fun TalerConfig.decimalNumber(section: String, option: String): DecimalNumber? = catchError {
    val numberStr = lookupString(section, option) ?: return@catchError null
    try {
        DecimalNumber(numberStr)
    } catch (e: Exception) {
        throw TalerConfigError("expected decimal number for section $section, option $option, but number is malformed")
    }
}

private fun TalerConfig.requireDecimalNumber(section: String, option: String): DecimalNumber = catchError {
    decimalNumber(section, option) ?:
        throw TalerConfigError("expected decimal number for section $section, option $option, but config value is empty")
}

private fun TalerConfig.RoundingMode(section: String, option: String): RoundingMode? = catchError {
    val str = lookupString(section, option) ?: return@catchError null;
    try {
        RoundingMode.valueOf(str)
    } catch (e: Exception) {
        throw TalerConfigError("expected rouding mode for section $section, option $option, but $str is unknown")
    }
}

private fun <R> catchError(lambda: () -> R): R {
    try {
        return lambda()
    } catch (e: TalerConfigError) {
        logger.error(e.message)
        kotlin.system.exitProcess(1)
    }
}
