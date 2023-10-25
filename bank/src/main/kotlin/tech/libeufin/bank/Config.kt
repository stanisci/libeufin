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

import ConfigSource
import TalerConfig
import TalerConfigError
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import tech.libeufin.util.DatabaseConfig

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.Config")
private val BANK_CONFIG_SOURCE = ConfigSource("libeufin-bank", "libeufin-bank")


/**
 * Application the parsed configuration.
 */
data class BankConfig(
    /**
     * Main, internal currency of the bank.
     */
    val currency: String,
    val currencySpecification: CurrencySpecification,
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
    val haveCashout: Boolean,
    val fiatCurrency: String?,
    val conversionInfo: ConversionInfo?
)

@Serializable
data class ConversionInfo (
    val buy_at_ratio: DecimalNumber,
    val sell_at_ratio: DecimalNumber,
    val buy_in_fee: DecimalNumber,
    val sell_out_fee: DecimalNumber,
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
    val currencySpecification = sections.find {
        it.startsWith("CURRENCY-") && requireBoolean(it, "enabled") && requireString(it, "code") == currency
    }?.let { loadCurrencySpecification(it) } ?: throw TalerConfigError("missing currency specification for $currency")
    val haveCashout = lookupBoolean("libeufin-bank", "have_cashout") ?: false;
    BankConfig(
        currency = currency,
        restrictRegistration = lookupBoolean("libeufin-bank", "restrict_registration") ?: false,
        defaultCustomerDebtLimit = requireAmount("libeufin-bank", "default_customer_debt_limit", currency),
        registrationBonusEnabled = lookupBoolean("libeufin-bank", "registration_bonus_enabled") ?: false,
        registrationBonus = requireAmount("libeufin-bank", "registration_bonus", currency),
        suggestedWithdrawalExchange = lookupString("libeufin-bank", "suggested_withdrawal_exchange"),
        defaultAdminDebtLimit = requireAmount("libeufin-bank", "default_admin_debt_limit", currency),
        spaCaptchaURL = lookupString("libeufin-bank", "spa_captcha_url"),
        restrictAccountDeletion = lookupBoolean("libeufin-bank", "restrict_account_deletion") ?: true,
        currencySpecification = currencySpecification,
        haveCashout = haveCashout,
        fiatCurrency = if (haveCashout) { requireString("libeufin-bank", "fiat_currency") } else { null },
        conversionInfo = if (haveCashout) { loadConversionInfo() } else { null }
    )
}

private fun TalerConfig.loadConversionInfo(): ConversionInfo = catchError {
    ConversionInfo(
        buy_at_ratio = requireDecimalNumber("libeufin-bank-conversion", "buy_at_ratio"),
        sell_at_ratio = requireDecimalNumber("libeufin-bank-conversion", "sell_at_ratio"),
        buy_in_fee = requireDecimalNumber("libeufin-bank-conversion", "buy_in_fee"),
        sell_out_fee = requireDecimalNumber("libeufin-bank-conversion", "sell_out_fee")
    )
}

private fun TalerConfig.loadCurrencySpecification(section: String): CurrencySpecification = catchError {
    CurrencySpecification(
        name = requireString(section, "name"),
        decimal_separator = requireString(section, "decimal_separator"),
        num_fractional_input_digits = requireNumber(section, "fractional_input_digits"),
        num_fractional_normal_digits = requireNumber(section, "fractional_normal_digits"),
        num_fractional_trailing_zero_digits = requireNumber(section, "fractional_trailing_zero_digits"),
        is_currency_name_leading = requireBoolean(section, "is_currency_name_leading"),
        alt_unit_names = Json.decodeFromString(requireString(section, "alt_unit_names"))
    )
}

private fun TalerConfig.requireAmount(section: String, option: String, currency: String): TalerAmount = catchError {
    val amountStr = lookupString(section, option) ?:
        throw TalerConfigError("expected amount for section $section, option $option, but config value is empty")
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

private fun TalerConfig.requireDecimalNumber(section: String, option: String): DecimalNumber = catchError {
    val numberStr = lookupString(section, option) ?:
        throw TalerConfigError("expected decimal number for section $section, option $option, but config value is empty")
    try {
        DecimalNumber(numberStr)
    } catch (e: Exception) {
        throw TalerConfigError("expected decimal number for section $section, option $option, but number is malformed")
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
