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

import io.ktor.client.request.*
import org.junit.Test
import tech.libeufin.bank.MonitorParams
import tech.libeufin.bank.MonitorResponse
import tech.libeufin.bank.MonitorWithConversion
import tech.libeufin.bank.Timeframe
import tech.libeufin.common.db.executeQueryCheck
import tech.libeufin.common.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals

class StatsTest {
    @Test
    fun register() = bankSetup { db ->
        setMaxDebt("merchant", "KUDOS:1000")
        setMaxDebt("exchange", "KUDOS:1000")
        setMaxDebt("customer", "KUDOS:1000")

        suspend fun cashin(amount: String) {
            db.conn { conn ->
                val stmt = conn.prepareStatement("SELECT 0 FROM cashin(?, ?, (?, ?)::taler_amount, ?)")
                stmt.setLong(1, Instant.now().micros())
                stmt.setBytes(2, ShortHashCode.rand().raw)
                val amount = TalerAmount(amount)
                stmt.setLong(3, amount.value)
                stmt.setInt(4, amount.frac)
                stmt.setString(5, "")
                stmt.executeQueryCheck()
            }
        }

        suspend fun monitor(
            dbCount: (MonitorWithConversion) -> Long, 
            count: Long, 
            regionalVolume: (MonitorWithConversion) -> TalerAmount, 
            regionalAmount: String,
            fiatVolume: ((MonitorWithConversion) -> TalerAmount)? = null, 
            fiatAmount: String? = null
        ) {
            Timeframe.entries.forEach { timeframe -> 
                client.get("/monitor?timestamp=${timeframe.name}") { pwAuth("admin") }.assertOkJson<MonitorResponse> {
                    val resp = it as MonitorWithConversion
                    println("$resp")
                    assertEquals(count, dbCount(resp))
                    assertEquals(TalerAmount(regionalAmount), regionalVolume(resp))
                    fiatVolume?.run { assertEquals(TalerAmount(fiatAmount!!), this(resp)) }
                }
            }
        }

        suspend fun monitorTalerIn(count: Long, amount: String) =
            monitor({it.talerInCount}, count, {it.talerInVolume}, amount)
        suspend fun monitorTalerOut(count: Long, amount: String) = 
            monitor({it.talerOutCount}, count, {it.talerOutVolume}, amount)
        suspend fun monitorCashin(count: Long, regionalAmount: String, fiatAmount: String) =
            monitor({it.cashinCount}, count, {it.cashinRegionalVolume}, regionalAmount, {it.cashinFiatVolume}, fiatAmount)
        suspend fun monitorCashout(count: Long, regionalAmount: String, fiatAmount: String) =
            monitor({it.cashoutCount}, count, {it.cashoutRegionalVolume}, regionalAmount, {it.cashoutFiatVolume}, fiatAmount)

        monitorTalerIn(0, "KUDOS:0")
        monitorTalerOut(0, "KUDOS:0")
        monitorCashin(0, "KUDOS:0", "EUR:0")
        monitorCashout(0, "KUDOS:0", "EUR:0")

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

        cashin("EUR:10")
        monitorCashin(1, "KUDOS:7.98", "EUR:10")
        monitorTalerIn(4, "KUDOS:30.88")
        cashin("EUR:20")
        monitorCashin(2, "KUDOS:23.96", "EUR:30")
        monitorTalerIn(5, "KUDOS:46.86")
        cashin("EUR:40")
        monitorCashin(3, "KUDOS:55.94", "EUR:70")
        monitorTalerIn(6, "KUDOS:78.84")

        cashout("KUDOS:3")
        monitorCashout(1, "KUDOS:3", "EUR:3.747")
        cashout("KUDOS:7.6")
        monitorCashout(2, "KUDOS:10.6", "EUR:13.244")
        cashout("KUDOS:12.3")
        monitorCashout(3, "KUDOS:22.9", "EUR:28.616")

        monitorTalerIn(6, "KUDOS:78.84")
        monitorTalerOut(3, "KUDOS:82.5")
        monitorCashin(3, "KUDOS:55.94", "EUR:70")
        monitorCashout(3, "KUDOS:22.9", "EUR:28.616")
    }

    @Test
    fun timeframe() = bankSetup { db ->
        db.conn { conn ->
            suspend fun register(now: LocalDateTime, amount: TalerAmount) {
                val stmt = conn.prepareStatement(
                    "CALL stats_register_payment('taler_out', ?::timestamp, (?, ?)::taler_amount, null)"
                )
                stmt.setObject(1, now)
                stmt.setLong(2, amount.value)
                stmt.setInt(3, amount.frac)
                stmt.executeUpdate()
            }

            suspend fun check(
                params: MonitorParams,
                count: Long,
                amount: TalerAmount
            ) {
                val res = db.monitor(params)
                assertEquals(count, res.talerOutCount, "taler count")
                assertEquals(amount, res.talerOutVolume, "taler volume")
            }

            suspend fun checkSimple(
                now: LocalDateTime,
                timeframe: Timeframe,
                count: Long,
                amount: TalerAmount
            ) = check(MonitorParams(timeframe, now), count, amount)
            suspend fun checkWhich(
                now: LocalDateTime,
                timeframe: Timeframe,
                which: Int,
                count: Long,
                amount: TalerAmount
            ) = check(MonitorParams(timeframe, now, which), count, amount)
            suspend fun checkDate(
                secs: Long,
                timeframe: Timeframe,
                count: Long,
                amount: TalerAmount
            ) = check(MonitorParams(timeframe, secs), count, amount)

            val now = LocalDateTime.now(ZoneOffset.UTC)
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
            checkSimple(now, Timeframe.hour, 1, TalerAmount("KUDOS:10.0"))
            checkSimple(otherHour, Timeframe.hour, 1, TalerAmount("KUDOS:20.0"))
            checkSimple(otherDay, Timeframe.day, 1, TalerAmount("KUDOS:35.0"))
            checkSimple(otherMonth, Timeframe.month, 1, TalerAmount("KUDOS:40.0"))
            checkSimple(otherYear, Timeframe.year, 1, TalerAmount("KUDOS:50.0"))

            // Check with timestamp and intervals
            checkWhich(now, Timeframe.hour, now.hour, 1, TalerAmount("KUDOS:10.0"))
            checkWhich(now, Timeframe.hour, otherHour.hour, 1, TalerAmount("KUDOS:20.0"))
            checkWhich(now, Timeframe.day, otherDay.dayOfMonth, 1, TalerAmount("KUDOS:35.0"))
            checkWhich(now, Timeframe.month, otherMonth.monthValue, 1, TalerAmount("KUDOS:40.0"))
            checkWhich(now, Timeframe.year, otherYear.year, 1, TalerAmount("KUDOS:50.0"))
            
            // Check with date seconds
            checkDate(now.toEpochSecond(ZoneOffset.UTC), Timeframe.hour, 1, TalerAmount("KUDOS:10.0"))
            checkDate(otherHour.toEpochSecond(ZoneOffset.UTC), Timeframe.hour, 1, TalerAmount("KUDOS:20.0"))
            checkDate(otherDay.toEpochSecond(ZoneOffset.UTC), Timeframe.day, 1, TalerAmount("KUDOS:35.0"))
            checkDate(otherMonth.toEpochSecond(ZoneOffset.UTC), Timeframe.month, 1, TalerAmount("KUDOS:40.0"))
            checkDate(otherYear.toEpochSecond(ZoneOffset.UTC), Timeframe.year, 1, TalerAmount("KUDOS:50.0"))

            // Check timestamp aggregation
            checkSimple(now, Timeframe.day, 2, TalerAmount("KUDOS:30.0"))
            checkSimple(now, Timeframe.month, 3, TalerAmount("KUDOS:65.0"))
            checkSimple(now, Timeframe.year, 4, TalerAmount("KUDOS:105.0")) 
            checkWhich(now, Timeframe.day, now.dayOfMonth, 2, TalerAmount("KUDOS:30.0"))
            checkWhich(now, Timeframe.month, now.monthValue, 3, TalerAmount("KUDOS:65.0"))
            checkWhich(now, Timeframe.year, now.year, 4, TalerAmount("KUDOS:105.0"))
            checkDate(now.toEpochSecond(ZoneOffset.UTC), Timeframe.day, 2, TalerAmount("KUDOS:30.0"))
            checkDate(now.toEpochSecond(ZoneOffset.UTC), Timeframe.month, 3, TalerAmount("KUDOS:65.0"))
            checkDate(now.toEpochSecond(ZoneOffset.UTC), Timeframe.year, 4, TalerAmount("KUDOS:105.0")) 
        }
    }
}
