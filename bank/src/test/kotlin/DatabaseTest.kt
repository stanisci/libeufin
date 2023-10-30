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
import tech.libeufin.util.CryptoUtil
import java.sql.DriverManager
import java.time.Instant
import java.util.Random
import java.util.UUID
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
}



