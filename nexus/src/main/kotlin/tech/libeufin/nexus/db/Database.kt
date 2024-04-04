/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

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
package tech.libeufin.nexus.db

import org.postgresql.util.PSQLState
import tech.libeufin.common.*
import tech.libeufin.common.db.*
import tech.libeufin.nexus.*
import java.sql.PreparedStatement
import java.sql.SQLException
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*

/**
 * Minimal set of information to initiate a new payment in
 * the database.
 */
data class InitiatedPayment(
    val id: Long,
    val amount: TalerAmount,
    val wireTransferSubject: String,
    val creditPaytoUri: String,
    val initiationTime: Instant,
    val requestUid: String
)

/**
 * Collects database connection steps and any operation on the Nexus tables.
 */
class Database(dbConfig: DatabaseConfig): DbPool(dbConfig, "libeufin_nexus") {
    val payment = PaymentDAO(this)
    val initiated = InitiatedDAO(this)
}