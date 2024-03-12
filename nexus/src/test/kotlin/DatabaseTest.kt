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

    // Tests creation, unique constraint violation handling, and
    // retrieving only one non-submitted payment.
    @Test
    fun paymentInitiation() = setup { db, _ -> 
        val beEmpty = db.initiated.submittableGet("KUDOS") // expect no records.
        assertEquals(beEmpty.size, 0)
        val initPay = InitiatedPayment(
            id = -1,
            amount = TalerAmount(44, 0, "KUDOS"),
            creditPaytoUri = "payto://iban/CH9300762011623852957?receiver-name=Test",
            wireTransferSubject = "test",
            requestUid = "unique",
            initiationTime = Instant.now()
        )
        assertEquals(db.initiated.create(initPay), PaymentInitiationResult.SUCCESS)
        assertEquals(db.initiated.create(initPay), PaymentInitiationResult.REQUEST_UID_REUSE)
        val haveOne = db.initiated.submittableGet("KUDOS")
        assertTrue("Size ${haveOne.size} instead of 1") {
            haveOne.size == 1
                    && haveOne.first().id == 1L
                    && haveOne.first().requestUid == "unique"
        }
    }

    /**
     * The SQL that gets submittable payments checks multiple
     * statuses from them.  Checking it here.
     */
    @Test
    fun submittablePayments() = setup { db, _ -> 
        val beEmpty = db.initiated.submittableGet("KUDOS")
        assertEquals(0, beEmpty.size)
        assertEquals(
            db.initiated.create(genInitPay(requestUid = "first")),
            PaymentInitiationResult.SUCCESS
        )
        assertEquals(
            db.initiated.create(genInitPay(requestUid = "second")),
            PaymentInitiationResult.SUCCESS
        )
        assertEquals(
            db.initiated.create(genInitPay(requestUid = "third")),
            PaymentInitiationResult.SUCCESS
        )

        // Setting the first as "transient_failure", must be found.
        db.initiated.submissionSuccess(1, Instant.now(), "Failure")
        // Setting the second as "success", must not be found.
        db.initiated.submissionSuccess(2, Instant.now(), "ORDER1234")
        val expectTwo = db.initiated.submittableGet("KUDOS")
        // the third initiation keeps the default "unsubmitted"
        // state, must be found.  Total 2.
        assertEquals(1, expectTwo.size)
    }

    // Tests how the fetch method gets the list of
    // multiple unsubmitted payment initiations.
    @Test
    fun paymentInitiationsMultiple() = setup { db, _ -> 
        assertEquals(db.initiated.create(genInitPay("#1", "unique1")), PaymentInitiationResult.SUCCESS)
        assertEquals(db.initiated.create(genInitPay("#2", "unique2")), PaymentInitiationResult.SUCCESS)
        assertEquals(db.initiated.create(genInitPay("#3", "unique3")), PaymentInitiationResult.SUCCESS)
        assertEquals(db.initiated.create(genInitPay("#4", "unique4")), PaymentInitiationResult.SUCCESS)

        // Marking one as submitted, hence not expecting it in the results.
        db.conn { conn ->
            conn.execSQLUpdate("""
                UPDATE initiated_outgoing_transactions
                    SET submitted='success'
                    WHERE initiated_outgoing_transaction_id=3;
            """.trimIndent())
        }

        // Expecting all the payments BUT the #3 in the result.
        db.initiated.submittableGet("KUDOS").apply {
            assertEquals(3, this.size)
            assertEquals("#1", this[0].wireTransferSubject)
            assertEquals("#2", this[1].wireTransferSubject)
            assertEquals("#4", this[2].wireTransferSubject)
        }
    }
}