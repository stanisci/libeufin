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
import tech.libeufin.nexus.XmlBuilder
import tech.libeufin.nexus.XMLUtil
import kotlin.test.assertEquals

class XmlCombinatorsTest {
    fun testBuilder(expected: String, root: String, builder: XmlBuilder.() -> Unit) {
        val toBytes = XmlBuilder.toBytes(root, builder)
        val toDom = XmlBuilder.toDom(root, null, builder)
        //assertEquals(expected, toString) TODO fix empty tag being closed only with toString
        assertEquals(expected, XMLUtil.convertDomToBytes(toDom).toString(Charsets.UTF_8))
    }

    @Test
    fun testWithModularity() {
        fun module(base: XmlBuilder) {
            base.el("module")
        }
        testBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><root><module/></root>",
            "root"
        ) {
            module(this)
        }
    }

    @Test
    fun testWithIterable() {
        testBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><iterable><endOfDocument><e1><e11>111</e11></e1><e2><e22>222</e22></e2><e3><e33>333</e33></e3><e4><e44>444</e44></e4><e5><e55>555</e55></e5><e6><e66>666</e66></e6><e7><e77>777</e77></e7><e8><e88>888</e88></e8><e9><e99>999</e99></e9><e10><e1010>101010</e1010></e10></endOfDocument></iterable>", 
            "iterable"
        ) {
            el("endOfDocument") {
                for (i in 1..10)
                    el("e$i/e$i$i", "$i$i$i")
            }
        }
    }

    @Test
    fun testBasicXmlBuilding() {
        testBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><ebicsRequest version=\"H004\"><a><b><c attribute-of=\"c\"><d><e><f nested=\"true\"><g><h/></g></f></e></d></c></b></a><one_more/></ebicsRequest>",
            "ebicsRequest"
        ) {
            attr("version", "H004")
            el("a/b/c") {
                attr("attribute-of", "c")
                el("d/e/f") {
                    attr("nested", "true")
                    el("g/h")
                }
            }
            el("one_more")
        }
    }
}
