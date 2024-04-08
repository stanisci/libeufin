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

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import tech.libeufin.common.db.DbPool
import tech.libeufin.common.PageParams
import tech.libeufin.common.HistoryParams
import org.postgresql.jdbc.PgConnection
import org.postgresql.util.PSQLState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.math.abs

/** Apply paging logic to a sql query */
suspend fun <T> DbPool.page(
    params: PageParams,
    idName: String,
    query: String,
    bind: PreparedStatement.() -> Int = { 0 },
    map: (ResultSet) -> T
): List<T> = conn { conn ->
    val backward = params.delta < 0
    val pageQuery = """
        $query
        $idName ${if (backward) '<' else '>'} ?
        ORDER BY $idName ${if (backward) "DESC" else "ASC"}
        LIMIT ?
    """
    conn.prepareStatement(pageQuery).run {
        val pad = bind()
        setLong(pad + 1, params.start)
        setInt(pad + 2, abs(params.delta))
        all { map(it) }
    }
}

/**
* The following function returns the list of transactions, according
* to the history parameters and perform long polling when necessary
*/
suspend fun <T> DbPool.poolHistory(
    params: HistoryParams, 
    bankAccountId: Long,
    listen: suspend (Long, suspend (Flow<Long>) -> List<T>) -> List<T>,
    query: String,
    accountColumn: String = "bank_account_id",
    map: (ResultSet) -> T
): List<T> {

    suspend fun load(): List<T> = page(
        params.page, 
        "bank_transaction_id", 
        "$query $accountColumn=? AND", 
        {
            setLong(1, bankAccountId)
            1
        },
        map
    )
        

    // TODO do we want to handle polling when going backward and there is no transactions yet ?
    // When going backward there is always at least one transaction or none
    return if (params.page.delta >= 0 && params.polling.poll_ms > 0) {
        listen(bankAccountId) { flow ->
            coroutineScope {
                // Start buffering notification before loading transactions to not miss any
                val polling = launch {
                    withTimeoutOrNull(params.polling.poll_ms) {
                        flow.first { it > params.page.start } // Always forward so >
                    }
                }    
                // Initial loading
                val init = load()
                // Long polling if we found no transactions
                if (init.isEmpty()) {
                    if (polling.join() != null) {
                        load()
                    } else {
                        init
                    }
                } else {
                    polling.cancel()
                    init
                }
            }
        }
    } else {
        load()
    }
}