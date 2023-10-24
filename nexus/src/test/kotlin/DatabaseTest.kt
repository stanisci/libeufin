import kotlinx.coroutines.runBlocking
import org.junit.Test
import tech.libeufin.nexus.*
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


class OutgoingPaymentsTest {

    /**
     * Tests the insertion of outgoing payments, including
     * the case where we reconcile with an initiated payment.
     */
    @Test
    fun outgoingPaymentCreation() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        runBlocking {
            // inserting without reconciling
            assertEquals(
                OutgoingPaymentOutcome.SUCCESS,
                db.outgoingPaymentCreate(genOutPay("paid by nexus"))
            )
            // inserting trying to reconcile with a non-existing initiated payment.
            assertEquals(
                OutgoingPaymentOutcome.INITIATED_COUNTERPART_NOT_FOUND,
                db.outgoingPaymentCreate(genOutPay("paid by nexus"), 5)
            )
            // initiating a payment to reconcile later.  Takes row ID == 1
            assertEquals(
                PaymentInitiationOutcome.SUCCESS,
                db.initiatedPaymentCreate(genInitPay("waiting for reconciliation"))
            )
            // Creating an outgoing payment, reconciling it with the one above.
            assertEquals(
                OutgoingPaymentOutcome.SUCCESS,
                db.outgoingPaymentCreate(genOutPay(), 1)
            )
        }
    }
}

class IncomingPaymentsTest {
    // Tests the function that flags incoming payments as bounced.
    @Test
    fun incomingPaymentBounce() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        runBlocking {
            // creating one incoming payment.
            assertTrue(db.incomingPaymentCreate(genIncPay("to be bounced"))) // row ID == 1.
            db.runConn {
                val bouncedSql = """
                    SELECT bounced
                      FROM incoming_transactions
                      WHERE incoming_transaction_id = 1"""
                // asserting is NOT bounced.
                val expectNotBounced = it.execSQLQuery(bouncedSql)
                assertTrue(expectNotBounced.next())
                assertFalse(expectNotBounced.getBoolean("bounced"))
                // now bouncing it.
                assertTrue(db.incomingPaymentSetAsBounced(1))
                // asserting it got flagged as bounced.
                val expectBounced = it.execSQLQuery(bouncedSql)
                assertTrue(expectBounced.next())
                assertTrue(expectBounced.getBoolean("bounced"))
                // Trying to bounce a non-existing payment.
                assertFalse(db.incomingPaymentSetAsBounced(5))
            }
        }
    }

    // Tests the creation of an incoming payment.
    @Test
    fun incomingPaymentCreation() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        val countRows = "SELECT count(*) AS how_many FROM incoming_transactions"
        runBlocking {
            // Asserting the table is empty.
            db.runConn {
                val res = it.execSQLQuery(countRows)
                assertTrue(res.next())
                assertEquals(0, res.getInt("how_many"))
            }
            assertTrue(db.incomingPaymentCreate(genIncPay("singleton")))
            // Asserting the table has one.
            db.runConn {
                val res = it.execSQLQuery(countRows)
                assertTrue(res.next())
                assertEquals(1, res.getInt("how_many"))
            }
            // Checking insertion of null (allowed) subjects.
            assertTrue(db.incomingPaymentCreate(genIncPay()))
        }
    }
}
class PaymentInitiationsTest {
    // Tests the flagging of payments as submitted.
    @Test
    fun paymentInitiationSetAsSubmitted() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        val getRowOne = """
                    SELECT submitted
                      FROM initiated_outgoing_transactions
                      WHERE initiated_outgoing_transaction_id=1
                """
        runBlocking {
            // Creating the record first.  Defaults to submitted == false.
            assertEquals(
                db.initiatedPaymentCreate(genInitPay("not submitted, has row ID == 1")),
                PaymentInitiationOutcome.SUCCESS
            )
            // Asserting on the false default submitted state.
            db.runConn { conn ->
                val isSubmitted = conn.execSQLQuery(getRowOne)
                assertTrue(isSubmitted.next())
                assertFalse(isSubmitted.getBoolean("submitted"))
            }
            // Switching the submitted state to true.
            assertTrue(db.initiatedPaymentSetSubmitted(1))
            // Asserting on the submitted state being TRUE now.
            db.runConn { conn ->
                val isSubmitted = conn.execSQLQuery(getRowOne)
                assertTrue(isSubmitted.next())
                assertTrue(isSubmitted.getBoolean("submitted"))
            }
        }
    }

    // Tests creation, unique constraint violation handling, and
    // retrieving only one non-submitted payment.
    @Test
    fun paymentInitiation() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        runBlocking {
            val beEmpty = db.initiatedPaymentsUnsubmittedGet("KUDOS")// expect no records.
            assertEquals(beEmpty.size, 0)
        }
        val initPay = InitiatedPayment(
            amount = TalerAmount(44, 0, "KUDOS"),
            creditPaytoUri = "payto://iban/not-used",
            wireTransferSubject = "test",
            clientRequestUuid = "unique",
            initiationTime = Instant.now()
        )
        runBlocking {
            assertEquals(db.initiatedPaymentCreate(initPay), PaymentInitiationOutcome.SUCCESS)
            assertEquals(db.initiatedPaymentCreate(initPay), PaymentInitiationOutcome.UNIQUE_CONSTRAINT_VIOLATION)
            val haveOne = db.initiatedPaymentsUnsubmittedGet("KUDOS")
            assertTrue {
                haveOne.size == 1
                        && haveOne.containsKey(1)
                        && haveOne[1]?.clientRequestUuid == "unique"
            }
        }
    }

    // Tests how the fetch method gets the list of
    // multiple unsubmitted payment initiations.
    @Test
    fun paymentInitiationsMultiple() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        runBlocking {
            assertEquals(db.initiatedPaymentCreate(genInitPay("#1")), PaymentInitiationOutcome.SUCCESS)
            assertEquals(db.initiatedPaymentCreate(genInitPay("#2")), PaymentInitiationOutcome.SUCCESS)
            assertEquals(db.initiatedPaymentCreate(genInitPay("#3")), PaymentInitiationOutcome.SUCCESS)
            assertEquals(db.initiatedPaymentCreate(genInitPay("#4")), PaymentInitiationOutcome.SUCCESS)

            // Marking one as submitted, hence not expecting it in the results.
            db.runConn { conn ->
                conn.execSQLUpdate("""
                    UPDATE initiated_outgoing_transactions
                      SET submitted = true
                      WHERE initiated_outgoing_transaction_id=3;
                """.trimIndent())
            }

            // Expecting all the payments BUT the #3 in the result.
            db.initiatedPaymentsUnsubmittedGet("KUDOS").apply {
                assertEquals(3, this.size)
                assertEquals("#1", this[1]?.wireTransferSubject)
                assertEquals("#2", this[2]?.wireTransferSubject)
                assertEquals("#4", this[4]?.wireTransferSubject)
            }
        }
    }
}