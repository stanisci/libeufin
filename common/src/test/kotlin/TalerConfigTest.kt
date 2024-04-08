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
import tech.libeufin.common.ConfigSource
import tech.libeufin.common.fromFile
import tech.libeufin.common.fromMem
import kotlin.io.path.Path
import kotlin.test.assertEquals

class TalerConfigTest {

    @Test
    fun parsing() {
        // We assume that libeufin-bank is installed. We could also try to locate the source tree here.
        val conf = ConfigSource("libeufin", "libeufin-bank", "libeufin-bank").fromMem(
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
        val conf = ConfigSource("libeufin", "libeufin-bank", "libeufin-bank").fromFile(null)
        conf.putValueString("PATHS", "DATADIR", "mydir")
        conf.putValueString("foo", "bar", "baz")
        conf.putValueString("foo", "bar2", "baz")

        assertEquals("baz", conf.lookupString("foo", "bar"))
        assertEquals(Path("baz"), conf.lookupPath("foo", "bar"))

        conf.putValueString("foo", "dir1", "foo/\$DATADIR/bar")

        assertEquals(Path("foo/mydir/bar"), conf.lookupPath("foo", "dir1"))
    }
}
