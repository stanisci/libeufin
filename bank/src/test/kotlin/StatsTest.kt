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

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.testing.*
import java.time.*
import java.util.*
import kotlin.test.*
import kotlinx.serialization.json.Json
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.util.*

class StatsTest {
    @Test
    fun transfer() = bankSetup { _ ->
        setMaxDebt("exchange", TalerAmount("KUDOS:1000"))

        suspend fun transfer(amount: TalerAmount) {
            client.post("/accounts/exchange/taler-wire-gateway/transfer") {
                basicAuth("exchange", "exchange-password")
                jsonBody {
                    "request_uid" to randHashCode()
                    "amount" to amount
                    "exchange_base_url" to "http://exchange.example.com/"
                    "wtid" to randShortHashCode()
                    "credit_account" to "payto://iban/MERCHANT-IBAN-XYZ"
                }
            }.assertOk()
        }

        suspend fun monitor(count: Long, amount: TalerAmount) {
            Timeframe.entries.forEach { timestamp -> 
                client.get("/monitor?timestamp=${timestamp.name}") { basicAuth("admin", "admin-password") }.assertOk().run {
                    val resp = json<MonitorResponse>()
                    assertEquals(count, resp.talerPayoutCount)
                    assertEquals(amount, resp.talerPayoutInternalVolume)
                }
            }
        }

        monitor(0, TalerAmount("KUDOS:0"))
        transfer(TalerAmount("KUDOS:10.0"))
        monitor(1, TalerAmount("KUDOS:10.0"))
        transfer(TalerAmount("KUDOS:30.5"))
        monitor(2, TalerAmount("KUDOS:40.5"))
        transfer(TalerAmount("KUDOS:42"))
        monitor(3, TalerAmount("KUDOS:82.5"))
    }

    @Test
    fun timeframe() = bankSetup { db ->
        db.conn { conn ->
            suspend fun register(now: OffsetDateTime, amount: TalerAmount) {
                val stmt =
                        conn.prepareStatement(
                                "CALL stats_register_internal_taler_payment(?::timestamp, (?, ?)::taler_amount)"
                        )
                stmt.setObject(1, now)
                stmt.setLong(2, amount.value)
                stmt.setInt(3, amount.frac)
                stmt.executeUpdate()
            }

            suspend fun check(
                now: OffsetDateTime,
                timeframe: Timeframe,
                which: Int?,
                count: Long,
                amount: TalerAmount
            ) {
                val stmt = conn.prepareStatement(
                    """
                    SELECT
                        internal_taler_payments_count
                        ,(internal_taler_payments_volume).val as internal_taler_payments_volume_val
                        ,(internal_taler_payments_volume).frac as internal_taler_payments_volume_frac
                    FROM stats_get_frame(?::timestamp, ?::stat_timeframe_enum, ?)
                    """
                )
                stmt.setObject(1, now)
                
                stmt.setString(2, timeframe.name)
                if (which != null) {
                    stmt.setInt(3, which)
                } else {
                    stmt.setNull(3, java.sql.Types.INTEGER)
                }
                stmt.oneOrNull {
                    val talerPayoutCount = it.getLong("internal_taler_payments_count")
                    val talerPayoutInternalVolume =
                            TalerAmount(
                                    value = it.getLong("internal_taler_payments_volume_val"),
                                    frac = it.getInt("internal_taler_payments_volume_frac"),
                                    currency = "KUDOS"
                            )
                    println("$timeframe $talerPayoutCount $talerPayoutInternalVolume")
                    assertEquals(count, talerPayoutCount)
                    assertEquals(amount, talerPayoutInternalVolume)
                }!!
            }

            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val otherHour = now.withHour((now.hour + 1) % 24)
            val otherDay = now.withDayOfMonth((now.dayOfMonth) % 28 + 1)
            val otherMonth = now.withMonth((now.monthValue) % 12 + 1)
            val otherYear = now.minusYears(1)

            register(now, TalerAmount("KUDOS:10.0"))
            register(otherHour, TalerAmount("KUDOS:20.0"))
            register(otherDay, TalerAmount("KUDOS:35.0"))
            register(otherMonth, TalerAmount("KUDOS:40.0"))
            register(otherYear, TalerAmount("KUDOS:50.0"))

            // Check with timestamp and truncating
            check(now, Timeframe.hour, null, 1, TalerAmount("KUDOS:10.0"))
            check(otherHour, Timeframe.hour, null, 1, TalerAmount("KUDOS:20.0"))
            check(otherDay, Timeframe.day, null, 1, TalerAmount("KUDOS:35.0"))
            check(otherMonth, Timeframe.month, null, 1, TalerAmount("KUDOS:40.0"))
            check(otherYear, Timeframe.year, null, 1, TalerAmount("KUDOS:50.0"))

            // Check with timestamp and intervals
            check(now, Timeframe.hour, now.hour, 1, TalerAmount("KUDOS:10.0"))
            check(now, Timeframe.hour, otherHour.hour, 1, TalerAmount("KUDOS:20.0"))
            check(now, Timeframe.day, otherDay.dayOfMonth, 1, TalerAmount("KUDOS:35.0"))
            check(now, Timeframe.month, otherMonth.monthValue, 1, TalerAmount("KUDOS:40.0"))
            check(now, Timeframe.year, otherYear.year, 1, TalerAmount("KUDOS:50.0"))

            // Check timestamp aggregation
            check(now, Timeframe.day, now.dayOfMonth, 2, TalerAmount("KUDOS:30.0"))
            check(now, Timeframe.month, now.monthValue, 3, TalerAmount("KUDOS:65.0"))
            check(now, Timeframe.year, now.year, 4, TalerAmount("KUDOS:105.0"))
            check(now, Timeframe.day, null, 2, TalerAmount("KUDOS:30.0"))
            check(now, Timeframe.month, null, 3, TalerAmount("KUDOS:65.0"))
            check(now, Timeframe.year, null, 4, TalerAmount("KUDOS:105.0")) 
        }
    }
}
