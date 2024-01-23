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

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.util.*

@Serializable
data class MyJsonType(
    val content: String,
    val n: Int
)

// Running (de)serialization, only checking that no exceptions are raised.
class JsonTest {
    @Test
    fun serializationTest() {
        Json.encodeToString(MyJsonType("Lorem Ipsum", 3))
    }
    @Test
    fun deserializationTest() {
        val serialized = """
            {"content": "Lorem Ipsum", "n": 3}
        """.trimIndent()
        Json.decodeFromString<MyJsonType>(serialized)
    }

    /**
     * Testing the custom absolute and relative time serializers.
     */
    @Test
    fun timeSerializers() {
        // from JSON to time types
        assert(Json.decodeFromString<RelativeTime>("{\"d_us\": 3}").d_us.toNanos() == 3000L)
        assert(Json.decodeFromString<RelativeTime>("{\"d_us\": \"forever\"}").d_us == ChronoUnit.FOREVER.duration)
        assert(Json.decodeFromString<TalerProtocolTimestamp>("{\"t_s\": 3}").t_s == Instant.ofEpochSecond(3))
        assert(Json.decodeFromString<TalerProtocolTimestamp>("{\"t_s\": \"never\"}").t_s == Instant.MAX)

        // from time types to JSON
        val oneDay = RelativeTime(d_us = Duration.of(1, ChronoUnit.DAYS))
        val oneDaySerial = Json.encodeToString(oneDay)
        assert(Json.decodeFromString<RelativeTime>(oneDaySerial).d_us == oneDay.d_us)
        val forever = RelativeTime(d_us = ChronoUnit.FOREVER.duration)
        val foreverSerial = Json.encodeToString(forever)
        assert(Json.decodeFromString<RelativeTime>(foreverSerial).d_us == forever.d_us)
    }

    @Test
    fun enumSerializer() {
        assert("\"credit\"" == Json.encodeToString(CreditDebitInfo.credit))
        assert("\"debit\"" == Json.encodeToString(CreditDebitInfo.debit))
    }

    // Testing JSON <--> TalerAmount
    @Test
    fun amountSerializer() {
        val amt = Json.decodeFromString<TalerAmount>("\"KUDOS:4.4\"")
        assert(Json.encodeToString(amt) == "\"KUDOS:4.4\"")
    }
}