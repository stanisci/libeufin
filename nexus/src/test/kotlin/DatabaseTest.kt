/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

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
import tech.libeufin.common.*
import tech.libeufin.nexus.*
import tech.libeufin.nexus.db.*
import tech.libeufin.nexus.db.InitiatedDAO.*
import tech.libeufin.nexus.db.PaymentDAO.*
import java.time.Instant
import kotlin.random.Random
import kotlin.test.*

class OutgoingPaymentsTest {
    @Test
    fun register() = setup { db, _ -> 
        // With reconciling
        genOutPay("paid by nexus", "first").run {
            assertEquals(
                PaymentInitiationResult.SUCCESS,
                db.initiated.create(genInitPay("waiting for reconciliation", "first"))
            )
            db.payment.registerOutgoing(this).run {
                assertTrue(new,)
                assertTrue(initiated)
            }
            db.payment.registerOutgoing(this).run {
                assertFalse(new)
                assertTrue(initiated)
            }
        }
        // Without reconciling
        genOutPay("not paid by nexus", "second").run {
            db.payment.registerOutgoing(this).run {
                assertTrue(new)
                assertFalse(initiated)
            }
            db.payment.registerOutgoing(this).run {
                assertFalse(new)
                assertFalse(initiated)
            }
        }
    }
}

class IncomingPaymentsTest {
    // Tests creating and bouncing incoming payments in one DB transaction.
    @Test
    fun bounce() = setup { db, _ -> 
        // creating and bouncing one incoming transaction.
        val payment = genInPay("incoming and bounced")
        db.payment.registerMalformedIncoming(
            payment,
            TalerAmount("KUDOS:2.53"),
            Instant.now()
        ).run {
            assertTrue(new)
        }
        db.payment.registerMalformedIncoming(
            payment,
            TalerAmount("KUDOS:2.53"),
            Instant.now()
        ).run {
            assertFalse(new)
        }
        db.conn {
            // Checking one incoming got created
            val checkIncoming = it.prepareStatement("""
                SELECT (amount).val as amount_value, (amount).frac as amount_frac 
                FROM incoming_transactions WHERE incoming_transaction_id = 1
            """).executeQuery()
            assertTrue(checkIncoming.next())
            assertEquals(payment.amount.value, checkIncoming.getLong("amount_value"))
            assertEquals(payment.amount.frac, checkIncoming.getInt("amount_frac"))
            // Checking the bounced table got its row.
            val checkBounced = it.prepareStatement("""
                SELECT 1 FROM bounced_transactions 
                WHERE incoming_transaction_id = 1 AND initiated_outgoing_transaction_id = 1
            """).executeQuery()
            assertTrue(checkBounced.next())
            // check the related initiated payment exists.
            val checkInitiated = it.prepareStatement("""
                SELECT
                    (amount).val as amount_value
                    ,(amount).frac as amount_frac
                FROM initiated_outgoing_transactions
                WHERE initiated_outgoing_transaction_id = 1
            """).executeQuery()
            assertTrue(checkInitiated.next())
            assertEquals(
                53000000,
                checkInitiated.getInt("amount_frac")
            )
            assertEquals(
                2,
                checkInitiated.getInt("amount_value")
            )
        }
    }
}
class PaymentInitiationsTest {

    @Test
    fun status() = setup { db, _ ->
        assertEquals(
            PaymentInitiationResult.SUCCESS,
            db.initiated.create(genInitPay(requestUid = "PAY1"))
        )
        db.initiated.submissionFailure(1, Instant.now(), "First failure")
        db.initiated.submissionFailure(1, Instant.now(), "Second failure")
        db.initiated.submissionSuccess(1, Instant.now(), "ORDER1")
        assertEquals(Pair("PAY1", null), db.initiated.logFailure("ORDER1"))

        assertEquals(
            PaymentInitiationResult.SUCCESS,
            db.initiated.create(genInitPay(requestUid = "PAY2"))
        )
        db.initiated.submissionFailure(2, Instant.now(), "First failure")
        db.initiated.submissionSuccess(2, Instant.now(), "ORDER2")
        db.initiated.logMessage("ORDER2", "status msg")
        assertEquals(Pair("PAY2", "status msg"), db.initiated.logFailure("ORDER2"))

        assertEquals(
            PaymentInitiationResult.SUCCESS,
            db.initiated.create(genInitPay(requestUid = "PAY3"))
        )
        db.initiated.submissionSuccess(3, Instant.now(), "ORDER3")
        assertEquals("PAY3", db.initiated.logSuccess("ORDER3"))

        // Unknown order
        assertNull(db.initiated.logSuccess("ORDER_X"))
        assertNull(db.initiated.logFailure("ORDER_X"))

        assertEquals(
            PaymentInitiationResult.SUCCESS,
            db.initiated.create(genInitPay(requestUid = "PAY4"))
        )
        db.initiated.bankMessage("PAY4", "status progress")
        db.initiated.bankFailure("PAY4", "status failure")

        assertEquals(
            PaymentInitiationResult.SUCCESS,
            db.initiated.create(genInitPay(requestUid = "PAY5"))
        )
        db.initiated.bankMessage("PAY5", "status progress")
        db.initiated.reversal("PAY5", "status reversal")
    }

    @Test
    fun submittable() = setup { db, _ -> 
        for (i in 0..5) {
            assertEquals(
                PaymentInitiationResult.SUCCESS,
                db.initiated.create(genInitPay(requestUid = "PAY$i"))
            )
        }
        assertEquals(
            listOf("PAY0", "PAY1", "PAY2", "PAY3", "PAY4", "PAY5"),
            db.initiated.submittable("KUDOS").map { it.requestUid }
        )

        // Check submitted not submitable
        db.initiated.submissionSuccess(1, Instant.now(), "ORDER1")
        assertEquals(
            listOf("PAY1", "PAY2", "PAY3", "PAY4", "PAY5"),
            db.initiated.submittable("KUDOS").map { it.requestUid }
        )

        // Check transient failure submitable last
        db.initiated.submissionFailure(2, Instant.now(), "Failure")
        assertEquals(
            listOf("PAY2", "PAY3", "PAY4", "PAY5", "PAY1"),
            db.initiated.submittable("KUDOS").map { it.requestUid }
        )

        // Check persistent failure not submitable
        db.initiated.bankFailure("PAY3", "status failure")
        assertEquals(
            listOf("PAY2", "PAY4", "PAY5", "PAY1"),
            db.initiated.submittable("KUDOS").map { it.requestUid }
        )
        db.initiated.reversal("PAY4", "status reversal")
        assertEquals(
            listOf("PAY2", "PAY5", "PAY1"),
            db.initiated.submittable("KUDOS").map { it.requestUid }
        )

        // Check rotation
        db.initiated.submissionFailure(3, Instant.now(), "Failure")
        assertEquals(
            listOf("PAY5", "PAY1", "PAY2"),
            db.initiated.submittable("KUDOS").map { it.requestUid }
        )
        db.initiated.submissionFailure(6, Instant.now(), "Failure")
        assertEquals(
            listOf("PAY1", "PAY2", "PAY5"),
            db.initiated.submittable("KUDOS").map { it.requestUid }
        )
        db.initiated.submissionFailure(2, Instant.now(), "Failure")
        assertEquals(
            listOf("PAY2", "PAY5", "PAY1"),
            db.initiated.submittable("KUDOS").map { it.requestUid }
        )
    }
}