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

// Foo pays Bar with custom subject.
fun genTx(
    subject: String = "test",
    creditorId: Long = 2,
    debtorId: Long = 1
): BankInternalTransaction =
    BankInternalTransaction(
        creditorAccountId = creditorId,
        debtorAccountId = debtorId,
        subject = subject,
        amount = TalerAmount( 10, 0, "KUDOS"),
        accountServicerReference = "acct-svcr-ref",
        endToEndId = "end-to-end-id",
        paymentInformationId = "pmtinfid",
        transactionDate = Instant.now()
    )

class DatabaseTest {

    // Testing the helper that creates the admin account.
    @Test
    fun createAdminTest() = setup { db, ctx ->
        // No admin accounts is expected.
        val noAdminCustomer = db.customerGetFromLogin("admin")
        assert(noAdminCustomer == null)
        // Now creating one.
        assert(maybeCreateAdminAccount(db, ctx))
        // Now expecting one.
        val yesAdminCustomer = db.customerGetFromLogin("admin")
        assert(yesAdminCustomer != null)
        // Expecting also its _bank_ account.
        assert(db.bankAccountGetFromOwnerId(yesAdminCustomer!!.customerId) != null)
        // Checking idempotency.
        assert(maybeCreateAdminAccount(db, ctx))
        // Checking that the random password blocks a login.
        assert(!CryptoUtil.checkpw(
            "likely-wrong",
            yesAdminCustomer.passwordHash
        ))
    }
}



