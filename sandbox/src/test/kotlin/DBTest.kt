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
import tech.libeufin.util.millis
import java.io.File
import java.time.LocalDateTime

/**
 * Run a block after connecting to the test database.
 * Cleans up the DB file afterwards.
 */
fun withTestDatabase(f: () -> Unit) {
    val dbfile = "jdbc:sqlite:/tmp/nexus-test.sqlite3"
    File(dbfile).also {
        if (it.exists()) {
            it.delete()
        }
    }
    Database.connect("$dbfile")
    dbDropTables(dbfile)
    try {
        f()
    }
    finally {
        File(dbfile).also {
            if (it.exists()) {
                it.delete()
            }
        }
    }
}

class DBTest {
    @Test
    fun exist() {
        println("x")
    }

    @Test
    fun betweenDates() {
        withTestDatabase {
            transaction {
                SchemaUtils.create(
                    BankAccountTransactionsTable,
                    BankAccountFreshTransactionsTable
                )
                val demobank = DemobankConfigEntity.new {
                    currency = "EUR"
                    bankDebtLimit = 1000000
                    usersDebtLimit = 10000
                    allowRegistrations = true
                    name = "default"
                    withSignupBonus = false
                    uiTitle = "test"
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