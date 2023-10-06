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

import tech.libeufin.bank.*
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

/**
 * Init the database and sets the currency to KUDOS.
 */
fun initDb(): Database {
    // We assume that libeufin-bank is installed. We could also try to locate the source tree here.
    val config = TalerConfig(ConfigSource("libeufin-bank", "libeufin-bank"))
    config.load()
    val sqlPath = config.requireValuePath("libeufin-bankdb-postgres", "SQL_DIR")
    val dbConnStr = "postgresql:///libeufincheck"
    resetDatabaseTables(dbConnStr, sqlPath)
    initializeDatabaseTables(dbConnStr, sqlPath)
    return Database(dbConnStr, "KUDOS")
}

fun getTestContext(
    restrictRegistration: Boolean = false,
    suggestedExchange: String = "https://exchange.example.com"
): BankApplicationContext {
    return BankApplicationContext(
        currency = "KUDOS",
        restrictRegistration = restrictRegistration,
        cashoutCurrency = "EUR",
        defaultCustomerDebtLimit = TalerAmount(100, 0, "KUDOS"),
        defaultAdminDebtLimit = TalerAmount(10000, 0, "KUDOS"),
        registrationBonusEnabled = false,
        registrationBonus = null,
        suggestedWithdrawalExchange = suggestedExchange,
        spaCaptchaURL = null,
        restrictAccountDeletion = true
    )
}

fun deflater(reqBody: String): ByteArray {
    val bos = ByteArrayOutputStream()
    val ios = DeflaterOutputStream(bos)
    ios.write(reqBody.toByteArray())
    ios.finish()
    return bos.toByteArray()
}