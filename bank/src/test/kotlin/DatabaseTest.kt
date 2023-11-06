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
import tech.libeufin.util.*
import java.sql.DriverManager
import java.time.Instant
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Random
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.experimental.inv
import kotlin.test.*

class DatabaseTest {
    // Testing the helper that update conversion config
    @Test
    fun conversionConfig() = setup { db, ctx ->
        // Check idempotent
        db.conversionUpdateConfig(ctx.conversionInfo!!)
        db.conversionUpdateConfig(ctx.conversionInfo!!)
    }

    // Testing the helper that creates the admin account.
    @Test
    fun createAdmin() = setup { db, ctx ->
        // Create admin account
        assert(maybeCreateAdminAccount(db, ctx))
        // Checking idempotency
        assert(maybeCreateAdminAccount(db, ctx))
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
}



