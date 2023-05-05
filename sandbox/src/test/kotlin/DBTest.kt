/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.sandbox.*
import tech.libeufin.util.getCurrentUser
import tech.libeufin.util.millis
import java.io.File
import java.time.LocalDateTime
import kotlin.reflect.KProperty

/**
 * Run a block after connecting to the test database.
 * Cleans up the DB file afterwards.
 */
fun withTestDatabase(f: () -> Unit) {
    val dbFile = "/tmp/sandbox-test.sqlite3"
    val dbConn = "jdbc:sqlite:${dbFile}"
    File(dbFile).also {
        if (it.exists()) {
            it.delete()
        }
    }
    Database.connect(dbConn, user = getCurrentUser())
    dbDropTables(dbConn)
    dbCreateTables(dbConn)
    try { f() }
    finally {
        File(dbFile).also {
            if (it.exists())
                it.delete()
        }
    }
}

class DBTest {
    private var config = DemobankConfig(
        currency = "EUR",
        bankDebtLimit = 1000000,
        usersDebtLimit = 10000,
        allowRegistrations = true,
        demobankName = "default",
        withSignupBonus = false,
    )

    /**
     * Storing configuration values into the database,
     * then extract them and check that they equal the
     * configuration model object.
     */
    @Test
    fun insertPairsTest() {
        withTestDatabase {
            // Config model.
            val config = DemobankConfig(
                currency = "EUR",
                bankDebtLimit = 1,
                usersDebtLimit = 2,
                allowRegistrations = true,
                demobankName = "default",
                withSignupBonus = true
            )
            transaction {
                DemobankConfigEntity.new { name = "default" }
                insertConfigPairs(config)
                val db = getDefaultDemobank()
                /**
                 * db.config extracts config values from the database
                 * and puts them in a fresh config model object.
                 */
                assert(config.hashCode() == db.config.hashCode())
            }
        }
    }

    @Test
    fun betweenDates() {
        withTestDatabase {
            transaction {
                insertConfigPairs(config)
                val demobank = DemobankConfigEntity.new {
                    name = "default"
                }
                val bankAccount = BankAccountEntity.new {
                    iban = "iban"
                    bic = "bic"
                    label = "label"
                    owner = "test"
                    demoBank = demobank
                }
                BankAccountTransactionEntity.new {
                    account = bankAccount
                    creditorIban = "earns"
                    creditorBic = "BIC"
                    creditorName = "Creditor Name"
                    debtorIban = "spends"
                    debtorBic = "BIC"
                    debtorName = "Debitor Name"
                    subject = "deal"
                    amount = "EUR:1"
                    date = LocalDateTime.now().millis()
                    currency = "EUR"
                    pmtInfId = "0"
                    direction = "DBIT"
                    accountServicerReference = "test-account-servicer-reference"
                    this.demobank = demobank
                }
            }
            // The block below tests the date range in the database query
            transaction {
                addLogger(StdOutSqlLogger)
                BankAccountTransactionEntity.find {
                    BankAccountTransactionsTable.date.between(
                        0, // 1970-01-01
                        LocalDateTime.now().millis() //
                    )
                }.apply {
                    assert(this.count() == 1L)
                }
            }
        }
    }
}