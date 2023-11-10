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

import java.util.UUID
import java.time.Instant
import java.time.Duration
import java.util.concurrent.TimeUnit
import tech.libeufin.util.*

class ConversionDAO(private val db: Database) {
    suspend fun updateConfig(cfg: ConversionInfo) = db.conn {
        it.transaction { conn -> 
            var stmt = conn.prepareStatement("CALL config_set_amount(?, (?, ?)::taler_amount)")
            for ((name, amount) in listOf(
                Pair("buy_ratio", cfg.buy_ratio),
                Pair("buy_fee", cfg.buy_fee),
                Pair("sell_ratio", cfg.sell_ratio),
                Pair("sell_fee", cfg.sell_fee),
            )) {
                stmt.setString(1, name)
                stmt.setLong(2, amount.value)
                stmt.setInt(3, amount.frac)
                stmt.executeUpdate()
            }
            for ((name, amount) in listOf(
                Pair("buy_tiny_amount", cfg.buy_tiny_amount),
                Pair("buy_min_amount", cfg.buy_min_amount),
                Pair("sell_tiny_amount", cfg.sell_tiny_amount),
                Pair("sell_min_amount", cfg.sell_min_amount),
            )) {
                stmt.setString(1, name)
                stmt.setLong(2, amount.value)
                stmt.setInt(3, amount.frac)
                stmt.executeUpdate()
            }
            stmt = conn.prepareStatement("CALL config_set_rounding_mode(?, ?::rounding_mode)")
            for ((name, value) in listOf(
                Pair("buy_rounding_mode", cfg.buy_rounding_mode),
                Pair("sell_rounding_mode", cfg.sell_rounding_mode)
            )) {
                stmt.setString(1, name)
                stmt.setString(2, value.name)
                stmt.executeUpdate()
            }
        }
    }

    private suspend fun conversion(amount: TalerAmount, name: String, currency: String): TalerAmount? = db.conn { conn ->
        val stmt = conn.prepareStatement("SELECT too_small, (to_amount).val AS amount_val, (to_amount).frac AS amount_frac FROM conversion_to((?, ?)::taler_amount, ?)")
        stmt.setLong(1, amount.value)
        stmt.setInt(2, amount.frac)
        stmt.setString(3, name)
        stmt.executeQuery().use {
            it.next()
            if (!it.getBoolean("too_small")) {
                TalerAmount(
                    value = it.getLong("amount_val"),
                    frac = it.getInt("amount_frac"),
                    currency = currency
                )
            } else {
                null
            }
        }
    }

    suspend fun regionalToFiat(amount: TalerAmount): TalerAmount? = conversion(amount, "sell", db.fiatCurrency!!)
    suspend fun fiatToRegional(amount: TalerAmount): TalerAmount? = conversion(amount, "buy", db.bankCurrency)
}