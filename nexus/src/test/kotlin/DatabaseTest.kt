import kotlinx.coroutines.runBlocking
import org.junit.Test
import tech.libeufin.nexus.*
import java.time.Instant
import kotlin.random.Random
import kotlin.test.*
import kotlin.test.assertEquals


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
            assertFalse(db.isOutgoingPaymentSeen("entropic"))
            assertEquals(
                OutgoingPaymentOutcome.SUCCESS,
                db.outgoingPaymentCreate(genOutPay("paid by nexus"))
            )
            assertTrue(db.isOutgoingPaymentSeen("entropic"))
            // inserting trying to reconcile with a non-existing initiated payment.
            assertEquals(
                OutgoingPaymentOutcome.INITIATED_COUNTERPART_NOT_FOUND,
                db.outgoingPaymentCreate(genOutPay(), 5)
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

// @Ignore // enable after having modified the bouncing logic in Kotlin
class IncomingPaymentsTest {
    // Tests creating and bouncing incoming payments in one DB transaction.
    @Test
    fun incomingAndBounce() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        runBlocking {
            // creating and bouncing one incoming transaction.
            assertTrue(db.incomingPaymentCreateBounced(
                genIncPay("incoming and bounced"),
                "UID"
            ))
            db.runConn {
                // Checking one incoming got created
                val checkIncoming = it.prepareStatement("""
                    SELECT 1 FROM incoming_transactions WHERE incoming_transaction_id = 1;
                """).executeQuery()
                assertTrue(checkIncoming.next())
                // Checking the bounced table got its row.
                val checkBounced = it.prepareStatement("""
                    SELECT 1 FROM bounced_transactions WHERE incoming_transaction_id = 1;
                """).executeQuery()
                assertTrue(checkBounced.next())
                // check the related initiated payment exists.
                val checkInitiated = it.prepareStatement("""
                    SELECT 
                      COUNT(initiated_outgoing_transaction_id) AS how_many
                      FROM initiated_outgoing_transactions
                """).executeQuery()
                assertTrue(checkInitiated.next())
                assertEquals(1, checkInitiated.getInt("how_many"))
            }
        }
    }

    // Tests the creation of a talerable incoming payment.
    @Test
    fun incomingTalerableCreation() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        val reservePub = ByteArray(32)
        Random.nextBytes(reservePub)

        runBlocking {
            val inc = genIncPay("reserve-pub")
            // Checking the reserve is not found.
            assertFalse(db.isReservePubFound(reservePub))
            assertFalse(db.isIncomingPaymentSeen(inc.bankTransferId))
            assertTrue(db.incomingTalerablePaymentCreate(inc, reservePub))
            // Checking the reserve is not found.
            assertTrue(db.isReservePubFound(reservePub))
            assertTrue(db.isIncomingPaymentSeen(inc.bankTransferId))
        }
    }
}
class PaymentInitiationsTest {

    // Testing the insertion of the failure message.
    @Test
    fun setFailureMessage() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        runBlocking {
            assertEquals(
                db.initiatedPaymentCreate(genInitPay("not submitted, has row ID == 1")),
                PaymentInitiationOutcome.SUCCESS
            )
            assertFalse(db.initiatedPaymentSetFailureMessage(3, "3 not existing"))
            assertTrue(db.initiatedPaymentSetFailureMessage(1, "expired"))
            // Checking the value from the database.
            db.runConn { conn ->
                val idOne = conn.execSQLQuery("""
                    SELECT failure_message
                      FROM initiated_outgoing_transactions
                      WHERE initiated_outgoing_transaction_id = 1;
                """.trimIndent())
                assertTrue(idOne.next())
                val maybeMessage = idOne.getString("failure_message")
                assertEquals("expired", maybeMessage)
            }
        }
    }
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
                PaymentInitiationOutcome.SUCCESS,
                db.initiatedPaymentCreate(genInitPay("not submitted, has row ID == 1")),
            )
            // Asserting on the false default submitted state.
            db.runConn { conn ->
                val isSubmitted = conn.execSQLQuery(getRowOne)
                assertTrue(isSubmitted.next())
                assertEquals("unsubmitted", isSubmitted.getString("submitted"))
            }
            // Switching the submitted state to success.
            assertTrue(db.initiatedPaymentSetSubmittedState(1, DatabaseSubmissionState.success))
            // Asserting on the submitted state being TRUE now.
            db.runConn { conn ->
                val isSubmitted = conn.execSQLQuery(getRowOne)
                assertTrue(isSubmitted.next())
                assertEquals("success", isSubmitted.getString("submitted"))
            }
        }
    }

    // Tests creation, unique constraint violation handling, and
    // retrieving only one non-submitted payment.
    @Test
    fun paymentInitiation() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        runBlocking {
            val beEmpty = db.initiatedPaymentsSubmittableGet("KUDOS") // expect no records.
            assertEquals(beEmpty.size, 0)
        }
        val initPay = InitiatedPayment(
            amount = TalerAmount(44, 0, "KUDOS"),
            creditPaytoUri = "payto://iban/TEST-IBAN?receiver-name=Test",
            wireTransferSubject = "test",
            requestUid = "unique",
            initiationTime = Instant.now()
        )
        runBlocking {
            assertNull(db.initiatedPaymentGetFromUid("unique"))
            assertEquals(db.initiatedPaymentCreate(initPay), PaymentInitiationOutcome.SUCCESS)
            assertEquals(db.initiatedPaymentCreate(initPay), PaymentInitiationOutcome.UNIQUE_CONSTRAINT_VIOLATION)
            val haveOne = db.initiatedPaymentsSubmittableGet("KUDOS")
            assertTrue {
                haveOne.size == 1
                        && haveOne.containsKey(1)
                        && haveOne[1]?.requestUid == "unique"
            }
            assertTrue(db.initiatedPaymentSetSubmittedState(1, DatabaseSubmissionState.success))
            assertNotNull(db.initiatedPaymentGetFromUid("unique"))
        }
    }

    /**
     * The SQL that gets submittable payments checks multiple
     * statuses from them.  Checking it here.
     */
    @Test
    fun submittablePayments() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        runBlocking {
            val beEmpty = db.initiatedPaymentsSubmittableGet("KUDOS")
            assertEquals(0, beEmpty.size)
            assertEquals(
                db.initiatedPaymentCreate(genInitPay(requestUid = "first")),
                PaymentInitiationOutcome.SUCCESS
            )
            assertEquals(
                db.initiatedPaymentCreate(genInitPay(requestUid = "second")),
                PaymentInitiationOutcome.SUCCESS
            )
            assertEquals(
                db.initiatedPaymentCreate(genInitPay(requestUid = "third")),
                PaymentInitiationOutcome.SUCCESS
            )

            // Setting the first as "transient_failure", must be found.
            assertTrue(db.initiatedPaymentSetSubmittedState(
                1, DatabaseSubmissionState.transient_failure
            ))
            // Setting the second as "success", must not be found.
            assertTrue(db.initiatedPaymentSetSubmittedState(
                2, DatabaseSubmissionState.success
            ))
            val expectTwo = db.initiatedPaymentsSubmittableGet("KUDOS")
            // the third initiation keeps the default "unsubmitted"
            // state, must be found.  Total 2.
            assertEquals(2, expectTwo.size)
        }
    }

    // Tests how the fetch method gets the list of
    // multiple unsubmitted payment initiations.
    @Test
    fun paymentInitiationsMultiple() {
        val db = prepDb(TalerConfig(NEXUS_CONFIG_SOURCE))
        runBlocking {
            assertEquals(db.initiatedPaymentCreate(genInitPay("#1", "unique1")), PaymentInitiationOutcome.SUCCESS)
            assertEquals(db.initiatedPaymentCreate(genInitPay("#2", "unique2")), PaymentInitiationOutcome.SUCCESS)
            assertEquals(db.initiatedPaymentCreate(genInitPay("#3", "unique3")), PaymentInitiationOutcome.SUCCESS)
            assertEquals(db.initiatedPaymentCreate(genInitPay("#4", "unique4")), PaymentInitiationOutcome.SUCCESS)

            // Marking one as submitted, hence not expecting it in the results.
            db.runConn { conn ->
                conn.execSQLUpdate("""
                    UPDATE initiated_outgoing_transactions
                      SET submitted='success'
                      WHERE initiated_outgoing_transaction_id=3;
                """.trimIndent())
            }

            // Expecting all the payments BUT the #3 in the result.
            db.initiatedPaymentsSubmittableGet("KUDOS").apply {
                assertEquals(3, this.size)
                assertEquals("#1", this[1]?.wireTransferSubject)
                assertEquals("#2", this[2]?.wireTransferSubject)
                assertEquals("#4", this[4]?.wireTransferSubject)
            }
        }
    }
}