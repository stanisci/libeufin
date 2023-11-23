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

import tech.libeufin.util.*

/** Data access logic for conversion */
class ConversionDAO(private val db: Database) {
    /** Update in-db conversion config */
    suspend fun updateConfig(cfg: ConversionInfo) = db.serializable {
        it.transaction { conn -> 
            var stmt = conn.prepareStatement("CALL config_set_amount(?, (?, ?)::taler_amount)")
            for ((name, amount) in listOf(
                Pair("cashin_ratio", cfg.cashin_ratio),
                Pair("cashout_ratio", cfg.cashout_ratio),
            )) {
                stmt.setString(1, name)
                stmt.setLong(2, amount.value)
                stmt.setInt(3, amount.frac)
                stmt.executeUpdate()
            }
            for ((name, amount) in listOf(
                Pair("cashin_fee", cfg.cashin_fee),
                Pair("cashin_tiny_amount", cfg.cashin_tiny_amount),
                Pair("cashin_min_amount", cfg.cashin_min_amount),
                Pair("cashout_fee", cfg.cashout_fee),
                Pair("cashout_tiny_amount", cfg.cashout_tiny_amount),
                Pair("cashout_min_amount", cfg.cashout_min_amount),
            )) {
                stmt.setString(1, name)
                stmt.setLong(2, amount.value)
                stmt.setInt(3, amount.frac)
                stmt.executeUpdate()
            }
            stmt = conn.prepareStatement("CALL config_set_rounding_mode(?, ?::rounding_mode)")
            for ((name, value) in listOf(
                Pair("cashin_rounding_mode", cfg.cashin_rounding_mode),
                Pair("cashout_rounding_mode", cfg.cashout_rounding_mode)
            )) {
                stmt.setString(1, name)
                stmt.setString(2, value.name)
                stmt.executeUpdate()
            }
        }
    }

    /** Clear in-db conversion config */
    suspend fun clearConfig() = db.serializable { conn ->
        conn.prepareStatement("DELETE FROM config WHERE key LIKE 'cashin%' OR key like 'cashout%'").executeUpdate()
    }

    /** Result of conversions operations */
    sealed class ConversionResult {
        data class Success(val converted: TalerAmount): ConversionResult()
        object ToSmall: ConversionResult()
        object MissingConfig: ConversionResult()
    }

    /** Perform [direction] conversion of [amount] using in-db [function] */
    private suspend fun conversion(amount: TalerAmount, direction: String, function: String): ConversionResult = db.conn { conn ->
        val stmt = conn.prepareStatement("SELECT too_small, no_config, (converted).val AS amount_val, (converted).frac AS amount_frac FROM $function((?, ?)::taler_amount, ?)")
        stmt.setLong(1, amount.value)
        stmt.setInt(2, amount.frac)
        stmt.setString(3, direction)
        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure $function")
                it.getBoolean("no_config") -> ConversionResult.MissingConfig
                it.getBoolean("too_small") -> ConversionResult.ToSmall
                else -> ConversionResult.Success(
                    it.getAmount("amount", if (amount.currency == db.bankCurrency) db.fiatCurrency!! else db.bankCurrency)
                )
            }
        }
    }
 
    /** Convert [regional] amount to fiat using cashout rate */
    suspend fun toCashout(regional: TalerAmount): ConversionResult = conversion(regional, "cashout", "conversion_to")
    /** Convert [fiat] amount to regional using cashin rate */
    suspend fun toCashin(fiat: TalerAmount): ConversionResult = conversion(fiat, "cashin", "conversion_to")
    /** Convert [fiat] amount to regional using inverse cashout rate */
    suspend fun fromCashout(fiat: TalerAmount): ConversionResult = conversion(fiat, "cashout", "conversion_from")
    /** Convert [regional] amount to fiat using inverse cashin rate */
    suspend fun fromCashin(regional: TalerAmount): ConversionResult = conversion(regional, "cashin", "conversion_from")
}