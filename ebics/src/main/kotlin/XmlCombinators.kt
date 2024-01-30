/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020, 2024 Taler Systems S.A.
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

package tech.libeufin.ebics

import com.sun.xml.txw2.output.IndentingXMLStreamWriter
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter
import java.time.format.*
import java.time.*

class XmlBuilder(private val w: XMLStreamWriter) {
    fun el(path: String, lambda: XmlBuilder.() -> Unit = {}) {
        path.splitToSequence('/').forEach { 
            w.writeStartElement(it)
        }
        lambda()
        path.splitToSequence('/').forEach { 
            w.writeEndElement()
        }
    }

    fun el(path: String, content: String) {
        el(path) {
            text(content)
        }
    }

    fun attr(name: String, value: String) {
        w.writeAttribute(name, value)
    }

    fun text(content: String) {
        w.writeCharacters(content)
    }
}

fun constructXml(root: String, f: XmlBuilder.() -> Unit): String {
    val factory = XMLOutputFactory.newFactory()
    val stream = StringWriter()
    var writer = factory.createXMLStreamWriter(stream)
    /**
     * NOTE: commenting out because it wasn't obvious how to output the
     * "standalone = 'yes' directive".  Manual forge was therefore preferred.
     */
    stream.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
    XmlBuilder(writer).el(root) {
        this.f()
    }
    writer.writeEndDocument()
    return stream.buffer.toString()
}

class DestructionError(m: String) : Exception(m)

private fun Element.childrenByTag(tag: String): Sequence<Element> = sequence {
    for (i in 0..childNodes.length) {
        val el = childNodes.item(i)
        if (el !is Element) {
            continue
        }
        if (el.localName != tag) {
            continue
        }
        yield(el)
    }
}

class XmlDestructor internal constructor(private val el: Element) {
    fun each(path: String, f: XmlDestructor.() -> Unit) {
        el.childrenByTag(path).forEach {
            f(XmlDestructor(it))
        }
    }

    fun <T> map(path: String, f: XmlDestructor.() -> T): List<T> {
        return el.childrenByTag(path).map {
            f(XmlDestructor(it))
        }.toList()
    }

    fun one(path: String): XmlDestructor {
        val children = el.childrenByTag(path).iterator()
        if (!children.hasNext()) {
            throw DestructionError("expected a single $path child, got none instead at $el")
        }
        val el = children.next()
        if (children.hasNext()) {
            throw DestructionError("expected a single $path child, got ${children.asSequence() + 1} instead at $el")
        }
        return XmlDestructor(el)
    }
    fun opt(path: String): XmlDestructor? {
        val children = el.childrenByTag(path).iterator()
        if (!children.hasNext()) {
            return null
        }
        val el = children.next()
        if (children.hasNext()) {
            throw DestructionError("expected an optional $path child, got ${children.asSequence().count() + 1} instead at $el")
        }
        return XmlDestructor(el)
    }

    fun <T> one(path: String, f: XmlDestructor.() -> T): T = f(one(path))
    fun <T> opt(path: String, f: XmlDestructor.() -> T): T? = opt(path)?.run(f)

    fun text(): String = el.textContent
    fun bool(): Boolean = el.textContent.toBoolean()
    fun date(): LocalDate = LocalDate.parse(text(), DateTimeFormatter.ISO_DATE)
    fun dateTime(): LocalDateTime = LocalDateTime.parse(text(), DateTimeFormatter.ISO_DATE_TIME)
    inline fun <reified T : kotlin.Enum<T>> enum(): T = java.lang.Enum.valueOf(T::class.java, text())

    fun attr(index: String): String = el.getAttribute(index)
}

fun <T> destructXml(xml: ByteArray, root: String, f: XmlDestructor.() -> T): T {
    val doc = XMLUtil.parseBytesIntoDom(xml)
    if (doc.documentElement.tagName != root) {
        throw DestructionError("expected root '$root' got '${doc.documentElement.tagName}'")
    }
    val destr = XmlDestructor(doc.documentElement)
    return f(destr)
}
