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
import java.time.Instant
import java.util.*
import kotlin.test.*
import kotlin.reflect.full.declaredMemberProperties
import kotlin.suspend
import kotlinx.serialization.json.Json
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.util.*

class StatsTest {
    @Test
    fun register() = bankSetup { db ->
        setMaxDebt("merchant", TalerAmount("KUDOS:1000"))
        setMaxDebt("exchange", TalerAmount("KUDOS:1000"))
        setMaxDebt("customer", TalerAmount("KUDOS:1000"))
        client.patch("/accounts/customer") {
            basicAuth("customer", "customer-password")
            jsonBody(json {
                "cashout_payto_uri" to IbanPayTo(genIbanPaytoUri())
                "challenge_contact_data" to json {
                    "phone" to "+99"
                }
            })
        }.assertNoContent()

        suspend fun cashin(amount: String) {
            db.conn { conn ->
                val stmt = conn.prepareStatement("SELECT 0 FROM cashin(?, ?, (?, ?)::taler_amount, ?)")
                stmt.setLong(1, Instant.now().toDbMicros()!!)
                stmt.setString(2, IbanPayTo("payto://iban/CUSTOMER-IBAN-XYZ").canonical)
                val amount = TalerAmount(amount)
                stmt.setLong(3, amount.value)
                stmt.setInt(4, amount.frac)
                stmt.setString(5, "")
                stmt.executeQueryCheck();
            }
        }

        suspend fun monitor(
            dbCount: (MonitorWithCashout) -> Long, 
            count: Long, 
            internalVolume: (MonitorWithCashout) -> TalerAmount, 
            internalAmount: String,
            externalVolume: ((MonitorWithCashout) -> TalerAmount)? = null, 
            externalAmount: String? = null
        ) {
            Timeframe.entries.forEach { timestamp -> 
                client.get("/monitor?timestamp=${timestamp.name}") { basicAuth("admin", "admin-password") }.assertOk().run {
                    println(bodyAsText())
                    val resp = json<MonitorResponse>() as MonitorWithCashout
                    assertEquals(count, dbCount(resp))
                    assertEquals(TalerAmount(internalAmount), internalVolume(resp))
                    externalVolume?.run { assertEquals(TalerAmount(externalAmount!!), this(resp)) }
                }
            }
        }

        suspend fun monitorTalerIn(count: Long, amount: String) =
            monitor({it.talerInCount}, count, {it.talerInVolume}, amount)
        suspend fun monitorTalerOut(count: Long, amount: String) = 
            monitor({it.talerOutCount}, count, {it.talerOutVolume}, amount)
        suspend fun monitorCashin(count: Long, internalAmount: String, externalAmount: String) =
            monitor({it.cashinCount}, count, {it.cashinInternalVolume}, internalAmount, {it.cashinExternalVolume}, externalAmount)
        suspend fun monitorCashout(count: Long, internalAmount: String, externalAmount: String) =
            monitor({it.cashoutCount}, count, {it.cashoutInternalVolume}, internalAmount, {it.cashoutExternalVolume}, externalAmount)

        monitorTalerIn(0, "KUDOS:0")
        monitorTalerOut(0, "KUDOS:0")
        monitorCashin(0, "KUDOS:0", "FIAT:0")
        monitorCashout(0, "KUDOS:0", "FIAT:0")

        addIncoming("KUDOS:3")
        monitorTalerIn(1, "KUDOS:3")
        addIncoming("KUDOS:7.6")
        monitorTalerIn(2, "KUDOS:10.6")
        addIncoming("KUDOS:12.3")
        monitorTalerIn(3, "KUDOS:22.9")
        
        transfer("KUDOS:10.0")
        monitorTalerOut(1, "KUDOS:10.0")
        transfer("KUDOS:30.5")
        monitorTalerOut(2, "KUDOS:40.5")
        transfer("KUDOS:42")
        monitorTalerOut(3, "KUDOS:82.5")

        cashin("FIAT:10")
        monitorCashin(1, "KUDOS:7.98", "FIAT:10")
        cashin("FIAT:20")
        monitorCashin(2, "KUDOS:23.96", "FIAT:30")
        cashin("FIAT:40")
        monitorCashin(3, "KUDOS:55.94", "FIAT:70")

        cashout("KUDOS:3")
        monitorCashout(1, "KUDOS:3", "FIAT:3.747")
        cashout("KUDOS:7.6")
        monitorCashout(2, "KUDOS:10.6", "FIAT:13.244")
        cashout("KUDOS:12.3")
        monitorCashout(3, "KUDOS:22.9", "FIAT:28.616")

        monitorTalerIn(3, "KUDOS:22.9")
        monitorTalerOut(3, "KUDOS:82.5")
        monitorCashin(3, "KUDOS:55.94", "FIAT:70")
        monitorCashout(3, "KUDOS:22.9", "FIAT:28.616")
    }

    @Test
    fun timeframe() = bankSetup { db ->
        db.conn { conn ->
            suspend fun register(now: OffsetDateTime, amount: TalerAmount) {
                val stmt = conn.prepareStatement(
                    "CALL stats_register_payment('taler_out', ?::timestamp, (?, ?)::taler_amount, null)"
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
                val stmt = conn.prepareStatement("""
                    SELECT
                        taler_out_count
                        ,(taler_out_volume).val as taler_out_volume_val
                        ,(taler_out_volume).frac as taler_out_volume_frac
                    FROM stats_get_frame(?::timestamp, ?::stat_timeframe_enum, ?)
                """)
                stmt.setObject(1, now)
                stmt.setString(2, timeframe.name)
                if (which != null) {
                    stmt.setInt(3, which)
                } else {
                    stmt.setNull(3, java.sql.Types.INTEGER)
                }
                stmt.oneOrNull {
                    val talerOutCount = it.getLong("taler_out_count")
                    val talerOutInternalVolume = TalerAmount(
                        value = it.getLong("taler_out_volume_val"),
                        frac = it.getInt("taler_out_volume_frac"),
                        currency = "KUDOS"
                    )
                    println("$timeframe $talerOutCount $talerOutInternalVolume")
                    assertEquals(count, talerOutCount)
                    assertEquals(amount, talerOutInternalVolume)
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
