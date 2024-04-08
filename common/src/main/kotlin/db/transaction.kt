/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.
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

package tech.libeufin.common.db

import org.postgresql.jdbc.PgConnection
import org.postgresql.util.PSQLState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

internal val logger: Logger = LoggerFactory.getLogger("libeufin-db")

fun <R> PgConnection.transaction(lambda: (PgConnection) -> R): R {
    try {
        autoCommit = false
        val result = lambda(this)
        commit()
        autoCommit = true
        return result
    } catch (e: Exception) {
        rollback()
        autoCommit = true
        throw e
    }
}

fun <T> PreparedStatement.oneOrNull(lambda: (ResultSet) -> T): T? {
    executeQuery().use {
        return if (it.next()) lambda(it) else null
    }
}

fun <T> PreparedStatement.one(lambda: (ResultSet) -> T): T =
    requireNotNull(oneOrNull(lambda)) { "Missing result to database query" }

fun <T> PreparedStatement.all(lambda: (ResultSet) -> T): List<T> {
    executeQuery().use {
        val ret = mutableListOf<T>()
        while (it.next()) {
            ret.add(lambda(it))
        }
        return ret
    }
}

fun PreparedStatement.executeQueryCheck(): Boolean {
    executeQuery().use {
        return it.next()
    }
}

fun PreparedStatement.executeUpdateCheck(): Boolean {
    executeUpdate()
    return updateCount > 0
}

/**
 * Helper that returns false if the row to be inserted
 * hits a unique key constraint violation, true when it
 * succeeds.  Any other error (re)throws exception.
 */
fun PreparedStatement.executeUpdateViolation(): Boolean {
    return try {
        executeUpdateCheck()
    } catch (e: SQLException) {
        logger.debug(e.message)
        if (e.sqlState == PSQLState.UNIQUE_VIOLATION.state) return false
        throw e // rethrowing, not to hide other types of errors.
    }
}

fun PreparedStatement.executeProcedureViolation(): Boolean {
    val savepoint = connection.setSavepoint()
    return try {
        executeUpdate()
        connection.releaseSavepoint(savepoint)
        true
    } catch (e: SQLException) {
        connection.rollback(savepoint)
        if (e.sqlState == PSQLState.UNIQUE_VIOLATION.state) return false
        throw e // rethrowing, not to hide other types of errors.
    }
}

// TODO comment
fun PgConnection.dynamicUpdate(
    table: String,
    fields: Sequence<String>,
    filter: String,
    bind: Sequence<Any?>,
) {
    val sql = fields.joinToString()
    if (sql.isEmpty()) return
    prepareStatement("UPDATE $table SET $sql $filter").run {
        for ((idx, value) in bind.withIndex()) {
            setObject(idx + 1, value)
        }
        executeUpdate()
    }
}