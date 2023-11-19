/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.

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

package tech.libeufin.bank

import tech.libeufin.util.*
import java.time.Instant

/** Data access logic for auth tokens */
class TokenDAO(private val db: Database) {
    /** Create new token for [login] */
    suspend fun create(
        login: String,
        content: ByteArray,
        creationTime: Instant,
        expirationTime: Instant,
        scope: TokenScope,
        isRefreshable: Boolean
    ): Boolean = db.serializable { conn ->
        // TODO single query
        val bankCustomer = conn.prepareStatement("""
            SELECT customer_id FROM customers WHERE login=?
        """).run {
            setString(1, login)
            oneOrNull { it.getLong(1) }!!
        }
        val stmt = conn.prepareStatement("""
            INSERT INTO bearer_tokens (
                content,
                creation_time,
                expiration_time,
                scope,
                bank_customer,
                is_refreshable
            ) VALUES (?, ?, ?, ?::token_scope_enum, ?, ?)
        """)
        stmt.setBytes(1, content)
        stmt.setLong(2, creationTime.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setLong(3, expirationTime.toDbMicros() ?: throw faultyDurationByClient())
        stmt.setString(4, scope.name)
        stmt.setLong(5, bankCustomer)
        stmt.setBoolean(6, isRefreshable)
        stmt.executeUpdateViolation()
    }
    
    /** Get info for [token] */
    suspend fun get(token: ByteArray): BearerToken? = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
              expiration_time,
              creation_time,
              bank_customer,
              scope,
              is_refreshable
            FROM bearer_tokens
            WHERE content=?;            
        """)
        stmt.setBytes(1, token)
        stmt.oneOrNull { 
            BearerToken(
                content = token,
                creationTime = it.getLong("creation_time").microsToJavaInstant() ?: throw faultyTimestampByBank(),
                expirationTime = it.getLong("expiration_time").microsToJavaInstant() ?: throw faultyDurationByClient(),
                bankCustomer = it.getLong("bank_customer"),
                scope = TokenScope.valueOf(it.getString("scope")),
                isRefreshable = it.getBoolean("is_refreshable")
            )
        }
    }
    
    /** Delete token [token] */
    suspend fun delete(token: ByteArray) = db.serializable { conn ->
        val stmt = conn.prepareStatement("""
            DELETE FROM bearer_tokens WHERE content = ?
        """)
        stmt.setBytes(1, token)
        stmt.execute()
    }
}