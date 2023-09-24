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
import tech.libeufin.util.execCommand

/**
 * Init the database and sets the currency to KUDOS.
 */
fun initDb(): Database {
    val config = TalerConfig.load()
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
        maxAuthTokenDurationUs = 200 * 1000000,
        spaCaptchaURL = null,
    )
}