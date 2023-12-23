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

import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.bank.AccountDAO.*
import tech.libeufin.util.*

class DatabaseTest {
    
    // Testing the helper that creates the admin account.
    @Test
    fun createAdmin() = setup { db, ctx ->
        // Create admin account
        assertEquals(AccountCreationResult.Success, maybeCreateAdminAccount(db, ctx))
        // Checking idempotency
        assertEquals(AccountCreationResult.LoginReuse, maybeCreateAdminAccount(db, ctx))
    }

    @Test
    fun serialisation() = bankSetup {
        assertBalance("customer", "+KUDOS:0")
        assertBalance("merchant", "+KUDOS:0")
        coroutineScope {
            repeat(10) { 
                launch {
                    tx("customer", "KUDOS:0.$it", "merchant", "concurrent $it")
                }
            }
        }
        assertBalance("customer", "-KUDOS:4.5")
        assertBalance("merchant", "+KUDOS:4.5")
        coroutineScope {
            repeat(5) { 
                launch {
                    tx("customer", "KUDOS:0.0$it", "merchant", "concurrent 0$it")
                }
                launch {
                    client.getA("/accounts/merchant/transactions").assertOk()
                }
            }
        }
    }

    @Test
    fun challenge() = setup { db, _ -> db.conn { conn ->
        val createStmt = conn.prepareStatement("SELECT challenge_create(?,?,?,?)")
        val sendStmt = conn.prepareStatement("SELECT challenge_mark_sent(?,?,?)")
        val tryStmt = conn.prepareStatement("SELECT ok, no_retry FROM challenge_try(?,?,?)")
        val resendStmt = conn.prepareStatement("SELECT challenge_resend(?,?,?,?,?)")

        val validityPeriod = Duration.ofHours(1)
        val retransmissionPeriod: Duration = Duration.ofMinutes(1)
        val retryCounter = 3

        fun create(code: String, now: Instant): Long {
            createStmt.setString(1, code)
            createStmt.setLong(2, ChronoUnit.MICROS.between(Instant.EPOCH, now))
            createStmt.setLong(3, TimeUnit.MICROSECONDS.convert(validityPeriod))
            createStmt.setInt(4, retryCounter)
            return createStmt.oneOrNull { it.getLong(1) }!!
        }

        fun send(id: Long, now: Instant) {
            sendStmt.setLong(1, id)
            sendStmt.setLong(2, ChronoUnit.MICROS.between(Instant.EPOCH, now))
            sendStmt.setLong(3, TimeUnit.MICROSECONDS.convert(retransmissionPeriod))
            return sendStmt.oneOrNull { }!!
        }

        fun cTry(id: Long, code: String, now: Instant): Pair<Boolean, Boolean> {
            tryStmt.setLong(1, id)
            tryStmt.setString(2, code)
            tryStmt.setLong(3, ChronoUnit.MICROS.between(Instant.EPOCH, now))
            return tryStmt.oneOrNull { 
                Pair(it.getBoolean(1), it.getBoolean(2))
            }!!
        }

        fun resend(id: Long, code: String, now: Instant): String? {
            resendStmt.setLong(1, id)
            resendStmt.setString(2, code)
            resendStmt.setLong(3, ChronoUnit.MICROS.between(Instant.EPOCH, now))
            resendStmt.setLong(4, TimeUnit.MICROSECONDS.convert(validityPeriod))
            resendStmt.setInt(5, retryCounter)
            return resendStmt.oneOrNull { it.getString(1) }
        }
        
        val now = Instant.now()
        val expired = now + validityPeriod
        val retransmit = now + retransmissionPeriod

        // Check basic
        create("good-code", now).run {
            // Bad code
            assertEquals(Pair(false, false), cTry(this, "bad-code", now))
            // Good code
            assertEquals(Pair(true, false), cTry(this, "good-code", now))
            // Never resend a confirmed challenge
            assertNull(resend(this, "new-code", expired))
            // Confirmed challenge always ok
            assertEquals(Pair(true, false), cTry(this, "good-code", now))
        }

        // Check retry
        create("good-code", now).run {
            send(this, now)
            // Bad code
            repeat(retryCounter) {
                assertEquals(Pair(false, false), cTry(this, "bad-code", now))
            }
            // Good code fail
            assertEquals(Pair(false, true), cTry(this, "good-code", now))
            // New code 
            assertEquals("new-code", resend(this, "new-code", now))
            // Good code
            assertEquals(Pair(true, false), cTry(this, "new-code", now))
        }

        // Check retransmission and expiration
        create("good-code", now).run {
            // Failed to send retransmit
            assertEquals("good-code", resend(this, "new-code", now))
            // Code successfully sent and still valid
            send(this, now)
            assertNull(resend(this, "new-code", now))
            // Code is still valid but shoud be resent
            assertEquals("good-code", resend(this, "new-code", retransmit))
            // Good code fail because expired
            assertEquals(Pair(false, false), cTry(this, "good-code", expired))
            // New code because expired
            assertEquals("new-code", resend(this, "new-code", expired))
            // Code successfully sent and still valid
            send(this, expired)
            assertNull(resend(this, "another-code", expired))
            // Old code no longer workds
            assertEquals(Pair(false, false), cTry(this, "good-code", expired))
            // New code works
            assertEquals(Pair(true, false), cTry(this, "new-code", expired))
        }
    }}

    @Test
    fun tanChallenge() = bankSetup { db -> db.conn { conn ->
        val createStmt = conn.prepareStatement("SELECT tan_challenge_create('',?,?,?,?,'customer',NULL,NULL)")
        val markSentStmt = conn.prepareStatement("SELECT tan_challenge_mark_sent(?,?,?)")
        val tryStmt = conn.prepareStatement("SELECT out_ok, out_no_retry, out_expired FROM tan_challenge_try(?,'customer',?,?)")
        val sendStmt = conn.prepareStatement("SELECT out_tan_code FROM tan_challenge_send(?,'customer',?,?,?,?)")

        val validityPeriod = Duration.ofHours(1)
        val retransmissionPeriod: Duration = Duration.ofMinutes(1)
        val retryCounter = 3

        fun create(code: String, now: Instant): Long {
            createStmt.setString(1, code)
            createStmt.setLong(2, ChronoUnit.MICROS.between(Instant.EPOCH, now))
            createStmt.setLong(3, TimeUnit.MICROSECONDS.convert(validityPeriod))
            createStmt.setInt(4, retryCounter)
            return createStmt.oneOrNull { it.getLong(1) }!!
        }

        fun markSent(id: Long, now: Instant) {
            markSentStmt.setLong(1, id)
            markSentStmt.setLong(2, ChronoUnit.MICROS.between(Instant.EPOCH, now))
            markSentStmt.setLong(3, TimeUnit.MICROSECONDS.convert(retransmissionPeriod))
            return markSentStmt.oneOrNull { }!!
        }

        fun cTry(id: Long, code: String, now: Instant): Triple<Boolean, Boolean, Boolean> {
            tryStmt.setLong(1, id)
            tryStmt.setString(2, code)
            tryStmt.setLong(3, ChronoUnit.MICROS.between(Instant.EPOCH, now))
            return tryStmt.oneOrNull { 
                Triple(it.getBoolean(1), it.getBoolean(2), it.getBoolean(3))
            }!!
        }

        fun send(id: Long, code: String, now: Instant): String? {
            sendStmt.setLong(1, id)
            sendStmt.setString(2, code)
            sendStmt.setLong(3, ChronoUnit.MICROS.between(Instant.EPOCH, now))
            sendStmt.setLong(4, TimeUnit.MICROSECONDS.convert(validityPeriod))
            sendStmt.setInt(5, retryCounter)
            return sendStmt.oneOrNull {
                it.getString(1) 
            }
        }
        
        val now = Instant.now()
        val expired = now + validityPeriod
        val retransmit = now + retransmissionPeriod

        // Check basic
        create("good-code", now).run {
            // Bad code
            assertEquals(Triple(false, false, false), cTry(this, "bad-code", now))
            // Good code
            assertEquals(Triple(true, false, false), cTry(this, "good-code", now))
            // Never resend a confirmed challenge
            assertNull(send(this, "new-code", expired))
            // Confirmed challenge always ok
            assertEquals(Triple(true, false, false), cTry(this, "good-code", now))
        }

        // Check retry
        create("good-code", now).run {
            markSent(this, now)
            // Bad code
            repeat(retryCounter-1) {
                assertEquals(Triple(false, false, false), cTry(this, "bad-code", now))
            }
            assertEquals(Triple(false, true, false), cTry(this, "bad-code", now))
            // Good code fail
            assertEquals(Triple(false, true, false), cTry(this, "good-code", now))
            // New code 
            assertEquals("new-code", send(this, "new-code", now))
            // Good code
            assertEquals(Triple(true, false, false), cTry(this, "new-code", now))
        }

        // Check retransmission and expiration
        create("good-code", now).run {
            // Failed to send retransmit
            assertEquals("good-code", send(this, "new-code", now))
            // Code successfully sent and still valid
            markSent(this, now)
            assertNull(send(this, "new-code", now))
            // Code is still valid but shoud be resent
            assertEquals("good-code", send(this, "new-code", retransmit))
            // Good code fail because expired
            assertEquals(Triple(false, false, true), cTry(this, "good-code", expired))
            // New code because expired
            assertEquals("new-code", send(this, "new-code", expired))
            // Code successfully sent and still valid
            markSent(this, expired)
            assertNull(send(this, "another-code", expired))
            // Old code no longer workds
            assertEquals(Triple(false, false, false), cTry(this, "good-code", expired))
            // New code works
            assertEquals(Triple(true, false, false), cTry(this, "new-code", expired))
        }
    }}

    // Testing iban payto uri normalization
    @Test
    fun ibanPayto() = setup { _, _ ->
        val expected = "payto://iban/CH9300762011623852957"
        val inputs = listOf(
            "payto://iban/BIC/CH9300762011623852957?receiver-name=NotGiven",
            "payto://iban/ch%209300-7620-1162-3852-957",
        )
        for (input in inputs) {
            assertEquals(expected, IbanPayTo(input).canonical)
        }
    }
}



