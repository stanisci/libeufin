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

package tech.libeufin.nexus

import org.w3c.dom.*
import java.io.InputStream
import java.io.StringWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.*
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

interface XmlBuilder {
    fun el(path: String, lambda: XmlBuilder.() -> Unit = {})
    fun el(path: String, content: String) {
        el(path) {
            text(content)
        }
    }
    fun attr(namespace: String, name: String, value: String)
    fun attr(name: String, value: String)
    fun text(content: String)

    companion object {
        fun toBytes(root: String, f: XmlBuilder.() -> Unit): ByteArray {
            val factory = XMLOutputFactory.newFactory()
            val stream = StringWriter()
            var writer = factory.createXMLStreamWriter(stream)
            /**
             * NOTE: commenting out because it wasn't obvious how to output the
             * "standalone = 'yes' directive".  Manual forge was therefore preferred.
             */
            stream.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            XmlStreamBuilder(writer).el(root) {
                this.f()
            }
            writer.writeEndDocument()
            return stream.buffer.toString().toByteArray()
        }

        fun toDom(root: String, schema: String?, f: XmlBuilder.() -> Unit): Document {
            val factory = DocumentBuilderFactory.newInstance();
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder();
            val doc = builder.newDocument();
            doc.setXmlVersion("1.0")
            doc.setXmlStandalone(true)
            val root = doc.createElementNS(schema, root)
            doc.appendChild(root);
            XmlDOMBuilder(doc, schema, root).f()
            doc.normalize()
            return doc
        }
    }
}

private class XmlStreamBuilder(private val w: XMLStreamWriter): XmlBuilder {
    override fun el(path: String, lambda: XmlBuilder.() -> Unit) {
        path.splitToSequence('/').forEach { 
            w.writeStartElement(it)
        }
        lambda()
        path.splitToSequence('/').forEach {
            w.writeEndElement()
        }
    }

    override fun attr(namespace: String, name: String, value: String) {
        w.writeAttribute(namespace, name, value)
    }

    override fun attr(name: String, value: String) {
        w.writeAttribute(name, value)
    }

    override fun text(content: String) {
        w.writeCharacters(content)
    }
}

private class XmlDOMBuilder(private val doc: Document, private val schema: String?, private var node: Element): XmlBuilder {
    override fun el(path: String, lambda: XmlBuilder.() -> Unit) {
        val current = node
        path.splitToSequence('/').forEach {
            val new = doc.createElementNS(schema, it)
            node.appendChild(new)
            node = new
        }
        lambda()
        node = current
    }

    override fun attr(namespace: String, name: String, value: String) {
        node.setAttributeNS(namespace, name, value)
    }

    override fun attr(name: String, value: String) {
        node.setAttribute(name, value)
    }

    override fun text(content: String) {
        node.appendChild(doc.createTextNode(content));
    }
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
    inline fun <reified T : Enum<T>> enum(): T = java.lang.Enum.valueOf(T::class.java, text())

    fun attr(index: String): String = el.getAttribute(index)

    companion object {
        fun <T> fromStream(xml: InputStream, root: String, f: XmlDestructor.() -> T): T {
            val doc = XMLUtil.parseIntoDom(xml)
            return fromDoc(doc, root, f)
        }

        fun <T> fromDoc(doc: Document, root: String, f: XmlDestructor.() -> T): T {
            if (doc.documentElement.tagName != root) {
                throw DestructionError("expected root '$root' got '${doc.documentElement.tagName}'")
            }
            val destr = XmlDestructor(doc.documentElement)
            return f(destr)
        }
    }
}

fun <T> destructXml(xml: InputStream, root: String, f: XmlDestructor.() -> T): T 
    = XmlDestructor.fromStream(xml, root, f)
