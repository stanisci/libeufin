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
import org.postgresql.jdbc.PgConnection
import tech.libeufin.bank.*
import tech.libeufin.util.*
import kotlin.test.*
import java.time.Instant
import java.util.*

class AmountTest {
    
    // Test amount computation in database
    @Test
    fun computationTest() = bankSetup { db ->  
        val conn = db.dbPool.getConnection().unwrap(PgConnection::class.java)
        conn.execSQLUpdate("UPDATE libeufin_bank.bank_accounts SET balance.val = 100000 WHERE internal_payto_uri = '${IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ").canonical}'")
        val stmt = conn.prepareStatement("""
            UPDATE libeufin_bank.bank_accounts 
                SET balance = (?, ?)::taler_amount
                    ,has_debt = ?
                    ,max_debt = (?, ?)::taler_amount
            WHERE internal_payto_uri = '${IbanPayTo("payto://iban/MERCHANT-IBAN-XYZ").canonical}'
        """)
        suspend fun routine(balance: TalerAmount, due: TalerAmount, hasBalanceDebt: Boolean, maxDebt: TalerAmount): Boolean {
            stmt.setLong(1, balance.value)
            stmt.setInt(2, balance.frac)
            stmt.setBoolean(3, hasBalanceDebt)
            stmt.setLong(4, maxDebt.value)
            stmt.setInt(5, maxDebt.frac)

            // Check bank transaction
            stmt.executeUpdate()
            val (txRes, _) = db.bankTransaction(
                creditAccountPayto = IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ"),
                debitAccountUsername = "merchant",
                subject = "test",
                amount = due,
                timestamp = Instant.now(),
            )
            val txBool = when (txRes) {
                BankTransactionResult.BALANCE_INSUFFICIENT -> false
                BankTransactionResult.SUCCESS -> true
                else -> throw Exception("Unexpected error $txRes")
            }

            // Check whithdraw 
            stmt.executeUpdate()
            val wRes = db.withdrawal.create(
                walletAccountUsername = "merchant",
                uuid = UUID.randomUUID(),
                amount = due,
            )
            val wBool = when (wRes) {
                WithdrawalCreationResult.BALANCE_INSUFFICIENT -> false
                WithdrawalCreationResult.SUCCESS -> true
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


    }

    @Test
    fun parse() {
        assertEquals(TalerAmount("EUR:4"), TalerAmount(4L, 0, "EUR"))
        assertEquals(TalerAmount("EUR:0.02"), TalerAmount(0L, 2000000, "EUR"))
        assertEquals(TalerAmount("EUR:4.12"), TalerAmount(4L, 12000000, "EUR"))
        assertEquals(TalerAmount("LOCAL:4444.1000"), TalerAmount(4444L, 10000000, "LOCAL"))
        assertEquals(TalerAmount("EUR:${TalerAmount.MAX_VALUE}.99999999"), TalerAmount(TalerAmount.MAX_VALUE, 99999999, "EUR"))

        assertException("Invalid amount format") {TalerAmount("")}
        assertException("Invalid amount format") {TalerAmount("EUR")}
        assertException("Invalid amount format") {TalerAmount("eur:12")}
        assertException("Invalid amount format") {TalerAmount(" EUR:12")}
        assertException("Invalid amount format") {TalerAmount("EUR:1.")}
        assertException("Invalid amount format") {TalerAmount("EUR:.1")}
        assertException("Invalid amount format") {TalerAmount("AZERTYUIOPQSD:12")}
        assertException("Value specified in amount is too large") {TalerAmount("EUR:${Long.MAX_VALUE}")}
        assertException("Invalid amount format") {TalerAmount("EUR:4.000000000")}
        assertException("Invalid amount format") {TalerAmount("EUR:4.4a")}
    }

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
    fun mul() = dbSetup { db ->
        db.conn { conn ->
            val stmt = conn.prepareStatement("SELECT product.val, product.frac FROM amount_mul((?, ?)::taler_amount, (?, ?)::taler_amount, (?, ?)::taler_amount, ?::rounding_mode) as product")

            fun mul(nb: TalerAmount, times: DecimalNumber, tiny: DecimalNumber = DecimalNumber("0.00000001"), roundingMode: String = "zero"): TalerAmount? {
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
    
            assertEquals(TalerAmount("EUR:30.0629"), mul(TalerAmount("EUR:6.41"), DecimalNumber("4.69")))
            assertEquals(TalerAmount("EUR:6.41000641"), mul(TalerAmount("EUR:6.41"), DecimalNumber("1.000001")))
            assertEquals(TalerAmount("EUR:2.49999997"), mul(TalerAmount("EUR:0.99999999"), DecimalNumber("2.5")))
            assertEquals(TalerAmount("EUR:${TalerAmount.MAX_VALUE}.99999999"), mul(TalerAmount("EUR:${TalerAmount.MAX_VALUE}.99999999"), DecimalNumber("1")))
            assertEquals(TalerAmount("EUR:${TalerAmount.MAX_VALUE}"), mul(TalerAmount("EUR:${TalerAmount.MAX_VALUE/4}"), DecimalNumber("4")))
            assertException("ERROR: amount value overflowed") { mul(TalerAmount(TalerAmount.MAX_VALUE/3, 0, "EUR"), DecimalNumber("3.00000001")) }
            assertException("ERROR: amount value overflowed") { mul(TalerAmount((TalerAmount.MAX_VALUE+2)/2, 0, "EUR"), DecimalNumber("2")) }
            assertException("ERROR: numeric field overflow") { mul(TalerAmount(Long.MAX_VALUE, 0, "EUR"), DecimalNumber("1")) }

            // Check rounding mode
            for ((mode, rounding) in listOf(
                Pair("zero", listOf(Pair(1, listOf(10, 11, 12, 12, 14, 15, 16, 17, 18, 19)))),
                Pair("up", listOf(Pair(1, listOf(10)), Pair(2, listOf(11, 12, 12, 14, 15, 16, 17, 18, 19)))),
                Pair("nearest", listOf(Pair(1, listOf(10, 11, 12, 12, 14)), Pair(2, listOf(15, 16, 17, 18, 19))))
            )) {
                for ((rounded, amounts) in rounding) {
                    for (amount in amounts) {
                        // Check euro
                        assertEquals(TalerAmount("EUR:0.0$rounded"), mul(TalerAmount("EUR:$amount"), DecimalNumber("0.001001"), DecimalNumber("0.01"), mode))
                        // Check kudos
                        assertEquals(TalerAmount("KUDOS:0.0000000$rounded"), mul(TalerAmount("KUDOS:0.$amount"), DecimalNumber("0.0000001"), roundingMode = mode))
                    }
                }
            }
            
            // Check hungarian rounding
            for ((rounded, amounts) in listOf(
                Pair(10, listOf(10, 11, 12)),
                Pair(15, listOf(13, 14, 15, 16, 17)),
                Pair(20, listOf(18, 19)),
            )) {
                for (amount in amounts) {
                    assertEquals(TalerAmount("HUF:$rounded"), mul(TalerAmount("HUF:$amount"), DecimalNumber("1.01"), DecimalNumber("5"), "nearest"))
                }
            }
        }
    }
}