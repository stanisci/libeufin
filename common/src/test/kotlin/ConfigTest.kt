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

import org.junit.Test
import uk.org.webcompere.systemstubs.SystemStubs.*
import java.time.Duration
import tech.libeufin.common.*
import tech.libeufin.common.db.*
import kotlin.test.*

class ConfigTest {
    @Test
    fun timeParsing() {
        fun parseTime(raw: String): Duration {
            val cfg = TalerConfig(ConfigSource("test", "test", "test"))
            cfg.loadFromMem("""
                [test]
                time = "$raw"
            """, null)
            return cfg.requireDuration("test", "time")
        }
        assertEquals(Duration.ofSeconds(1), parseTime("1s"))
        assertEquals(parseTime("1 s"), parseTime("1s"))
        assertEquals(Duration.ofMinutes(10), parseTime("10m"))
        assertEquals(parseTime("10 m"), parseTime("10m"))
        assertEquals(Duration.ofHours(1), parseTime("01h"))
        assertEquals(
            Duration.ofHours(1).plus(Duration.ofMinutes(10)).plus(Duration.ofSeconds(12)),
            parseTime("1h10m12s")
        )
        assertEquals(parseTime("1h10m12s"), parseTime("1h10'12\""))
    }

    @Test
    fun jdbcParsing() {
        val user = currentUser()
        assertFails { jdbcFromPg("test") }
        assertEquals("jdbc:test", jdbcFromPg("jdbc:test"))
        assertEquals("jdbc:postgresql://localhost/?user=$user&socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory\$FactoryArg&socketFactoryArg=/var/run/postgresql/.s.PGSQL.5432", jdbcFromPg("postgresql:///"))
        assertEquals("jdbc:postgresql://?host=args%2Dhost&user=arg%23%24User&password=%21%22%23%24%25%26%27%28%29", jdbcFromPg("postgresql://?host=args%2Dhost&user=arg%23%24User&password=%21%22%23%24%25%26%27%28%29"))
        withEnvironmentVariable("PGPORT", "1234").execute {
            assertEquals("jdbc:postgresql://localhost/?user=$user&socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory\$FactoryArg&socketFactoryArg=/var/run/postgresql/.s.PGSQL.1234", jdbcFromPg("postgresql:///"))
        }
        withEnvironmentVariable("PGPORT", "1234").and("PGHOST", "/tmp").execute {
            assertEquals("jdbc:postgresql://localhost/?user=antoine&socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory\$FactoryArg&socketFactoryArg=/tmp/.s.PGSQL.1234", jdbcFromPg("postgresql:///"))
        }
    }
}