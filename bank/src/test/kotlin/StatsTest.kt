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
import io.ktor.server.testing.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.Json
import tech.libeufin.bank.*
import tech.libeufin.util.*
import kotlin.test.*
import java.time.Instant
import java.util.*

class StatsTest {
    @Test
    fun internalTalerPayment() = bankSetup { db ->  
        db.conn { conn ->
            val stmt = conn.prepareStatement("CALL stats_register_internal_taler_payment(now()::timestamp, (?, ?)::taler_amount)")
        
            suspend fun register(amount: TalerAmount) {
                stmt.setLong(1, amount.value)
                stmt.setInt(2, amount.frac)
                stmt.executeUpdate()
            }

            client.get("/monitor") {
                basicAuth("admin", "admin-password")
            }.assertOk().run {
                val resp = Json.decodeFromString<MonitorResponse>(bodyAsText())
                assertEquals(0, resp.talerPayoutCount)
                assertEquals(TalerAmount("KUDOS:0"), resp.talerPayoutInternalVolume)
            }

            register(TalerAmount("KUDOS:10.0"))
            client.get("/monitor") {
                basicAuth("admin", "admin-password")
            }.assertOk().run {
                val resp = Json.decodeFromString<MonitorResponse>(bodyAsText())
                assertEquals(1, resp.talerPayoutCount)
                assertEquals(TalerAmount("KUDOS:10"), resp.talerPayoutInternalVolume)
            }

            register(TalerAmount("KUDOS:30.5"))
            client.get("/monitor") {
                basicAuth("admin", "admin-password")
            }.assertOk().run {
                val resp = Json.decodeFromString<MonitorResponse>(bodyAsText())
                assertEquals(2, resp.talerPayoutCount)
                assertEquals(TalerAmount("KUDOS:40.5"), resp.talerPayoutInternalVolume)
            }

            // TODO Test timeframe logic with different timestamps
        }
    }
}