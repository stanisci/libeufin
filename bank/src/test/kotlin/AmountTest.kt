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


import org.junit.Test
import org.postgresql.jdbc.PgConnection
import tech.libeufin.bank.*
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
            val txRes = db.bankTransaction(
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
            val wRes = db.talerWithdrawalCreate(
                walletAccountUsername = "merchant",
                opUUID = UUID.randomUUID(),
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
    fun parseValid() {
        assertEquals(TalerAmount("EUR:4"), TalerAmount(4L, 0, "EUR"))
        assertEquals(TalerAmount("EUR:0.02"), TalerAmount(0L, 2000000, "EUR"))
        assertEquals(TalerAmount("EUR:4.12"), TalerAmount(4L, 12000000, "EUR"))
        assertEquals(TalerAmount("LOCAL:4444.1000"), TalerAmount(4444L, 10000000, "LOCAL"))
    }

    @Test
    fun parseInvalid() {
        assertException("Invalid amount format") {TalerAmount("")}
        assertException("Invalid amount format") {TalerAmount("EUR")}
        assertException("Invalid amount format") {TalerAmount("eur:12")}
        assertException("Invalid amount format") {TalerAmount(" EUR:12")}
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
}