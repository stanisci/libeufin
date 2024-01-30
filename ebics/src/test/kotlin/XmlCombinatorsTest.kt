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
import tech.libeufin.ebics.XmlBuilder
import tech.libeufin.ebics.constructXml
import kotlin.test.*

class XmlCombinatorsTest {

    @Test
    fun testWithModularity() {
        fun module(base: XmlBuilder) {
            base.el("module")
        }
        val s = constructXml("root") {
            module(this)
        }
        println(s)
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><root><module/></root>", s)
    }

    @Test
    fun testWithIterable() {
        val s = constructXml("iterable") {
            el("endOfDocument") {
                for (i in 1..10)
                    el("$i/$i$i", "$i$i$i")
            }
        }
        println(s)
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><iterable><endOfDocument><1><11>111</11></1><2><22>222</22></2><3><33>333</33></3><4><44>444</44></4><5><55>555</55></5><6><66>666</66></6><7><77>777</77></7><8><88>888</88></8><9><99>999</99></9><10><1010>101010</1010></10></endOfDocument></iterable>", s)
    }

    @Test
    fun testBasicXmlBuilding() {
        val s = constructXml("ebics:ebicsRequest") {
            attr("version", "H004")
            el("a/b/c") {
                attr("attribute-of", "c")
                el("//d/e/f//") {
                    attr("nested", "true")
                    el("g/h/")
                }
            }
            el("one more")
        }
        println(s)
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><ebics:ebicsRequest version=\"H004\"><a><b><c attribute-of=\"c\"><><><d><e><f><>< nested=\"true\"><g><h></></h></g></></></f></e></d></></></c></b></a><one more/></ebics:ebicsRequest>", s)
    }
}
