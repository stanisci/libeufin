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

import org.junit.Test
import tech.libeufin.bank.DecimalNumber
import tech.libeufin.bank.db.TransactionDAO.BankTransactionResult
import tech.libeufin.bank.db.WithdrawalDAO.WithdrawalCreationResult
import tech.libeufin.common.*
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals

class AmountTest {
    // Test amount computation in database
    @Test
    fun computationTest() = bankSetup { db -> db.conn { conn ->
        conn.execSQLUpdate("UPDATE libeufin_bank.bank_accounts SET balance.val = 100000 WHERE internal_payto_uri = '${customerPayto.canonical}'")
        val stmt = conn.prepareStatement("""
            UPDATE libeufin_bank.bank_accounts 
                SET balance = (?, ?)::taler_amount
                    ,has_debt = ?
                    ,max_debt = (?, ?)::taler_amount
            WHERE internal_payto_uri = '${merchantPayto.canonical}'
        """)
        suspend fun routine(balance: TalerAmount, due: TalerAmount, hasBalanceDebt: Boolean, maxDebt: TalerAmount): Boolean {
            stmt.setLong(1, balance.value)
            stmt.setInt(2, balance.frac)
            stmt.setBoolean(3, hasBalanceDebt)
            stmt.setLong(4, maxDebt.value)
            stmt.setInt(5, maxDebt.frac)

            // Check bank transaction
            stmt.executeUpdate()
            val txRes = db.transaction.create(
                creditAccountPayto = customerPayto,
                debitAccountUsername = "merchant",
                subject = "test",
                amount = due,
                timestamp = Instant.now(),
                is2fa = false,
                requestUid = null
            )
            val txBool = when (txRes) {
                BankTransactionResult.BalanceInsufficient -> false
                is BankTransactionResult.Success -> true
                else -> throw Exception("Unexpected error $txRes")
            }

            // Check whithdraw 
            stmt.executeUpdate()
            val wRes = db.withdrawal.create(
                login = "merchant",
                uuid = UUID.randomUUID(),
                amount = due,
                now = Instant.now()
            )
            val wBool = when (wRes) {
                WithdrawalCreationResult.BalanceInsufficient -> false
                WithdrawalCreationResult.Success -> true
                else -> throw Exception("Unexpected error $txRes")
            }

            // Logic must be the same
            assertEquals(wBool, txBool)
            return txBool
        }

        // Balance enough, assert for true
        assert(routine(
            balance = TalerAmount(10, 0, "KUDOS"),
            due = TalerAmount(8, 0, "KUDOS"),
            hasBalanceDebt = false,
            maxDebt = TalerAmount(100, 0, "KUDOS")
        ))
        // Balance still sufficient, thanks for big enough debt permission.  Assert true.
        assert(routine(
            balance = TalerAmount(10, 0, "KUDOS"),
            due = TalerAmount(80, 0, "KUDOS"),
            hasBalanceDebt = false,
            maxDebt = TalerAmount(100, 0, "KUDOS")
        ))
        // Balance not enough, max debt cannot cover, asserting for false.
        assert(!routine(
            balance = TalerAmount(10, 0, "KUDOS"),
            due = TalerAmount(80, 0, "KUDOS"),
            hasBalanceDebt = true,
            maxDebt = TalerAmount(50, 0, "KUDOS")
        ))
        // Balance becomes enough, due to a larger max debt, asserting for true.
        assert(routine(
            balance = TalerAmount(10, 0, "KUDOS"),
            due = TalerAmount(80, 0, "KUDOS"),
            hasBalanceDebt = false,
            maxDebt = TalerAmount(70, 0, "KUDOS")
        ))
        // Max debt not enough for the smallest fraction, asserting for false
        assert(!routine(
            balance = TalerAmount(0, 0, "KUDOS"),
            due = TalerAmount(0, 2, "KUDOS"),
            hasBalanceDebt = false,
            maxDebt = TalerAmount(0, 1, "KUDOS")
        ))
        // Same as above, but already in debt.
        assert(!routine(
            balance = TalerAmount(0, 1, "KUDOS"),
            due = TalerAmount(0, 1, "KUDOS"),
            hasBalanceDebt = true,
            maxDebt = TalerAmount(0, 1, "KUDOS")
        ))
    }}

    @Test
    fun parseRoundTrip() {
        for (amount in listOf("EUR:4", "EUR:0.02", "EUR:4.12")) {
            assertEquals(amount, TalerAmount(amount).toString())
        }
    }
    
    @Test
    fun normalize() = dbSetup { db ->
        db.conn { conn ->
            val stmt = conn.prepareStatement("SELECT normalized.val, normalized.frac FROM amount_normalize((?, ?)::taler_amount) as normalized")
            fun TalerAmount.normalize(): TalerAmount? {
                stmt.setLong(1, value)
                stmt.setInt(2, frac)
                return stmt.oneOrNull {
                    TalerAmount(
                        it.getLong(1),
                        it.getInt(2),
                        "EUR"
                    )
                }!!
            }
    
            assertEquals(TalerAmount("EUR:6"), TalerAmount(4L, 2 * TalerAmount.FRACTION_BASE, "EUR").normalize())
            assertEquals(TalerAmount("EUR:6.00000001"), TalerAmount(4L, 2 * TalerAmount.FRACTION_BASE + 1, "EUR").normalize())
            assertEquals(TalerAmount("EUR:${TalerAmount.MAX_VALUE}.99999999"), TalerAmount("EUR:${TalerAmount.MAX_VALUE}.99999999").normalize())
            assertException("ERROR: bigint out of range") { TalerAmount(Long.MAX_VALUE, TalerAmount.FRACTION_BASE, "EUR").normalize() }
            assertException("ERROR: amount value overflowed") { TalerAmount(TalerAmount.MAX_VALUE, TalerAmount.FRACTION_BASE , "EUR").normalize() }
        }
    }

    @Test
    fun add() = dbSetup { db ->
        db.conn { conn ->
            val stmt = conn.prepareStatement("SELECT sum.val, sum.frac FROM amount_add((?, ?)::taler_amount, (?, ?)::taler_amount) as sum")
            operator fun TalerAmount.plus(increment: TalerAmount): TalerAmount? {
                stmt.setLong(1, value)
                stmt.setInt(2, frac)
                stmt.setLong(3, increment.value)
                stmt.setInt(4, increment.frac)
                return stmt.oneOrNull {
                    TalerAmount(
                        it.getLong(1),
                        it.getInt(2),
                        "EUR"
                    )
                }!!
            }
    
            assertEquals(TalerAmount("EUR:6.41") + TalerAmount("EUR:4.69"), TalerAmount("EUR:11.1"))
            assertEquals(TalerAmount("EUR:${TalerAmount.MAX_VALUE}") + TalerAmount("EUR:0.99999999"), TalerAmount("EUR:${TalerAmount.MAX_VALUE}.99999999"))
            assertException("ERROR: amount value overflowed") { TalerAmount(TalerAmount.MAX_VALUE - 5, 0, "EUR") + TalerAmount(6, 0, "EUR") }
            assertException("ERROR: bigint out of range") { TalerAmount(Long.MAX_VALUE, 0, "EUR") + TalerAmount(1, 0, "EUR") }
            assertException("ERROR: amount value overflowed") { TalerAmount(TalerAmount.MAX_VALUE - 5, TalerAmount.FRACTION_BASE - 1, "EUR") + TalerAmount(5, 2, "EUR") }
            assertException("ERROR: integer out of range") { TalerAmount(0, Int.MAX_VALUE, "EUR") + TalerAmount(0, 1, "EUR") }
        }
    }

    @Test
    fun conversionApply() = dbSetup { db ->
        db.conn { conn ->
            fun apply(nb: TalerAmount, times: DecimalNumber, tiny: DecimalNumber = DecimalNumber("0.00000001"), roundingMode: String = "zero"): TalerAmount? {
                val stmt = conn.prepareStatement("SELECT amount.val, amount.frac FROM conversion_apply_ratio((?, ?)::taler_amount, (?, ?)::taler_amount, (?, ?)::taler_amount, ?::rounding_mode) as amount")
                stmt.setLong(1, nb.value)
                stmt.setInt(2, nb.frac)
                stmt.setLong(3, times.value)
                stmt.setInt(4, times.frac)
                stmt.setLong(5, tiny.value)
                stmt.setInt(6, tiny.frac)
                stmt.setString(7, roundingMode)
                return stmt.oneOrNull {
                    TalerAmount(
                        it.getLong(1),
                        it.getInt(2),
                        nb.currency
                    )
                }!!
            }
    
            assertEquals(TalerAmount("EUR:30.0629"), apply(TalerAmount("EUR:6.41"), DecimalNumber("4.69")))
            assertEquals(TalerAmount("EUR:6.41000641"), apply(TalerAmount("EUR:6.41"), DecimalNumber("1.000001")))
            assertEquals(TalerAmount("EUR:2.49999997"), apply(TalerAmount("EUR:0.99999999"), DecimalNumber("2.5")))
            assertEquals(TalerAmount("EUR:${TalerAmount.MAX_VALUE}.99999999"), apply(TalerAmount("EUR:${TalerAmount.MAX_VALUE}.99999999"), DecimalNumber("1")))
            assertEquals(TalerAmount("EUR:${TalerAmount.MAX_VALUE}"), apply(TalerAmount("EUR:${TalerAmount.MAX_VALUE/4}"), DecimalNumber("4")))
            assertException("ERROR: amount value overflowed") { apply(TalerAmount(TalerAmount.MAX_VALUE/3, 0, "EUR"), DecimalNumber("3.00000001")) }
            assertException("ERROR: amount value overflowed") { apply(TalerAmount((TalerAmount.MAX_VALUE+2)/2, 0, "EUR"), DecimalNumber("2")) }
            assertException("ERROR: numeric field overflow") { apply(TalerAmount(Long.MAX_VALUE, 0, "EUR"), DecimalNumber("1")) }

            // Check rounding mode
            for ((mode, rounding) in listOf(
                Pair("zero", listOf(Pair(1, listOf(10, 11, 12, 12, 14, 15, 16, 17, 18, 19)))),
                Pair("up", listOf(Pair(1, listOf(10)), Pair(2, listOf(11, 12, 12, 14, 15, 16, 17, 18, 19)))),
                Pair("nearest", listOf(Pair(1, listOf(10, 11, 12, 12, 14)), Pair(2, listOf(15, 16, 17, 18, 19))))
            )) {
                for ((rounded, amounts) in rounding) {
                    for (amount in amounts) {
                        // Check euro
                        assertEquals(TalerAmount("EUR:0.0$rounded"), apply(TalerAmount("EUR:$amount"), DecimalNumber("0.001"), DecimalNumber("0.01"), mode))
                        // Check kudos
                        assertEquals(TalerAmount("KUDOS:0.0000000$rounded"), apply(TalerAmount("KUDOS:0.$amount"), DecimalNumber("0.0000001"), roundingMode = mode))
                    }
                }
            }
            
            // Check hungarian rounding
            for ((mode, rounding) in listOf(
                Pair("zero", listOf(Pair(10, listOf(10, 11, 12, 13, 14)), Pair(15, listOf(15, 16, 17, 18, 19)))),
                Pair("up", listOf(Pair(10, listOf(10)), Pair(15, listOf(11, 12, 13, 14, 15)), Pair(20, listOf(16, 17, 18, 19)))),
                Pair("nearest", listOf(Pair(10, listOf(10, 11, 12)), Pair(15, listOf(13, 14, 15, 16, 17)), Pair(20, listOf(18, 19))))
            )) {
                for ((rounded, amounts) in rounding) {
                    for (amount in amounts) {
                        assertEquals(TalerAmount("HUF:$rounded"), apply(TalerAmount("HUF:$amount"), DecimalNumber("1"), DecimalNumber("5"), mode))
                    }
                }
            }
            for (mode in listOf("zero", "up", "nearest")) {
                assertEquals(TalerAmount("HUF:5"), apply(TalerAmount("HUF:5"), DecimalNumber("1"), DecimalNumber("1"), mode))
            }
        }
    }

    @Test
    fun conversionRevert() = dbSetup { db ->
        db.conn { conn ->
            fun TalerAmount.apply(ratio: DecimalNumber, tiny: DecimalNumber = DecimalNumber("0.00000001"), roundingMode: String = "zero"): TalerAmount? {
                val stmt = conn.prepareStatement("SELECT amount.val, amount.frac FROM conversion_apply_ratio((?, ?)::taler_amount, (?, ?)::taler_amount, (?, ?)::taler_amount, ?::rounding_mode) as amount")
                stmt.setLong(1, this.value)
                stmt.setInt(2, this.frac)
                stmt.setLong(3, ratio.value)
                stmt.setInt(4, ratio.frac)
                stmt.setLong(5, tiny.value)
                stmt.setInt(6, tiny.frac)
                stmt.setString(7, roundingMode)
                return stmt.oneOrNull {
                    TalerAmount(
                        it.getLong(1),
                        it.getInt(2),
                        currency
                    )
                }!!
            }

            fun TalerAmount.revert(ratio: DecimalNumber, tiny: DecimalNumber = DecimalNumber("0.00000001"), roundingMode: String = "zero"): TalerAmount? {
                val stmt = conn.prepareStatement("SELECT amount.val, amount.frac FROM conversion_revert_ratio((?, ?)::taler_amount, (?, ?)::taler_amount, (?, ?)::taler_amount, ?::rounding_mode) as amount")
                stmt.setLong(1, this.value)
                stmt.setInt(2, this.frac)
                stmt.setLong(3, ratio.value)
                stmt.setInt(4, ratio.frac)
                stmt.setLong(5, tiny.value)
                stmt.setInt(6, tiny.frac)
                stmt.setString(7, roundingMode)
                return stmt.oneOrNull {
                    TalerAmount(
                        it.getLong(1),
                        it.getInt(2),
                        currency
                    )
                }!!
            }
    
            assertEquals(TalerAmount("EUR:6.41"), TalerAmount("EUR:30.0629").revert(DecimalNumber("4.69")))
            assertEquals(TalerAmount("EUR:6.41"), TalerAmount("EUR:6.41000641").revert(DecimalNumber("1.000001")))
            assertEquals(TalerAmount("EUR:0.99999999"), TalerAmount("EUR:2.49999997").revert(DecimalNumber("2.5")))
            assertEquals(TalerAmount("EUR:${TalerAmount.MAX_VALUE}.99999999"), TalerAmount("EUR:${TalerAmount.MAX_VALUE}.99999999").revert(DecimalNumber("1")))
            assertEquals(TalerAmount("EUR:${TalerAmount.MAX_VALUE}"), TalerAmount("EUR:${TalerAmount.MAX_VALUE/4}").revert(DecimalNumber("0.25")))
            assertException("ERROR: amount value overflowed") { TalerAmount(TalerAmount.MAX_VALUE/4, 0, "EUR").revert(DecimalNumber("0.24999999")) }
            assertException("ERROR: amount value overflowed") { TalerAmount((TalerAmount.MAX_VALUE+2)/2, 0, "EUR").revert(DecimalNumber("0.5")) }
            assertException("ERROR: numeric field overflow") { TalerAmount(Long.MAX_VALUE, 0, "EUR").revert(DecimalNumber("1")) }

            for (mode in listOf("zero", "up", "nearest")) {
                for (amount in listOf(10, 11, 12, 12, 14, 15, 16, 17, 18, 19)) {
                    for (tiny in listOf("0.01", "0.00000001", "5")) {
                        for (ratio in listOf("1", "0.341", "0.00000001")) {
                            val tiny = DecimalNumber(tiny)
                            val ratio = DecimalNumber(ratio)
                            val base = TalerAmount("EUR:$amount")
                            // Apply ratio
                            val rounded = base.apply(ratio, tiny, mode)!!
                            // Revert ratio  
                            val revert = rounded.revert(ratio, tiny, mode)!!
                            // Check applying ratio again give the same result
                            val check = revert.apply(ratio, tiny, mode)!!
                            assertEquals(rounded, check)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun apiError() = bankSetup { _ -> 
        val base = obj {
            "payto_uri" to "$exchangePayto?message=payout"
        }

        // Check OK
        client.postA("/accounts/merchant/transactions") {
            json(base) { "amount" to "KUDOS:0.3ABC" }
        }.assertBadRequest(TalerErrorCode.BANK_BAD_FORMAT_AMOUNT)
        client.postA("/accounts/merchant/transactions") {
            json(base) { "amount" to "KUDOS:999999999999999999" }
        }.assertBadRequest(TalerErrorCode.BANK_NUMBER_TOO_BIG)
    }
}