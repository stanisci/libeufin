/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.
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

import org.junit.Test
import kotlin.test.assertEquals

class TalerConfigTest {

    @Test
    fun parsing() {
        // We assume that libeufin-bank is installed. We could also try to locate the source tree here.
        val conf = TalerConfig(ConfigSource("libeufin", "libeufin-bank", "libeufin-bank"))
        conf.loadDefaults()
        conf.loadFromString(
            """

            [foo]

            bar = baz

            """.trimIndent()
        )

        println(conf.stringify())

        assertEquals("baz", conf.lookupString("foo", "bar"))

        println(conf.getInstallPath())
    }

    @Test
    fun substitution() {
        // We assume that libeufin-bank is installed. We could also try to locate the source tree here.
        val conf = TalerConfig(ConfigSource("libeufin", "libeufin-bank", "libeufin-bank"))
        conf.putValueString("PATHS", "DATADIR", "mydir")
        conf.putValueString("foo", "bar", "baz")
        conf.putValueString("foo", "bar2", "baz")

        assertEquals("baz", conf.lookupString("foo", "bar"))
        assertEquals("baz", conf.lookupPath("foo", "bar"))

        conf.putValueString("foo", "dir1", "foo/\$DATADIR/bar")

        assertEquals("foo/mydir/bar", conf.lookupPath("foo", "dir1"))
    }
}
