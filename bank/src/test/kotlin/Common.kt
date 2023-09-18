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

import tech.libeufin.bank.Database
import tech.libeufin.util.execCommand

// Init the database and sets the currency to KUDOS.
fun initDb(): Database {
    System.setProperty(
        "BANK_DB_CONNECTION_STRING",
        "jdbc:postgresql:///libeufincheck"
    )
    execCommand(
        listOf(
            "libeufin-bank-dbinit",
            "-d",
            "libeufincheck",
            "-r"
        ),
        throwIfFails = true
    )
    val db = Database("jdbc:postgresql:///libeufincheck")
    db.configSet("internal_currency", "KUDOS")
    return db
}