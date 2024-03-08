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

package tech.libeufin.nexus

import tech.libeufin.nexus.ebics.*
import io.ktor.http.*
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.*
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import javax.xml.XMLConstants
import javax.xml.crypto.*
import javax.xml.crypto.dom.DOMURIReference
import javax.xml.crypto.dsig.*
import javax.xml.crypto.dsig.dom.DOMSignContext
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec
import javax.xml.crypto.dsig.spec.TransformParameterSpec
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.Source
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import javax.xml.validation.Validator
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * This URI dereferencer allows handling the resource reference used for
 * XML signatures in EBICS.
 */
private class EbicsSigUriDereferencer : URIDereferencer {
    override fun dereference(myRef: URIReference?, myCtx: XMLCryptoContext?): Data {
        if (myRef !is DOMURIReference)
            throw Exception("invalid type")
        if (myRef.uri != "#xpointer(//*[@authenticate='true'])")
            throw Exception("invalid EBICS XML signature URI: '${myRef.uri}'")
        val xp: XPath = XPathFactory.newInstance().newXPath()
        val nodeSet = xp.compile("//*[@authenticate='true']/descendant-or-self::node()").evaluate(
            myRef.here.ownerDocument, XPathConstants.NODESET
        )
        if (nodeSet !is NodeList)
            throw Exception("invalid type")
        if (nodeSet.length <= 0) {
            throw Exception("no nodes to sign")
        }
        val nodeList = ArrayList<Node>()
        for (i in 0 until nodeSet.length) {
            val node = nodeSet.item(i)
            nodeList.add(node)
        }
        return NodeSetData { nodeList.iterator() }
    }
}

/**
 * Helpers for dealing with XML in EBICS.
 */
object XMLUtil {
    fun convertDomToBytes(document: Document): ByteArray {
        val w = ByteArrayOutputStream()
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.STANDALONE, "yes")
        transformer.transform(DOMSource(document), StreamResult(w))
        return w.toByteArray()
    }

    /** Parse [xml] into a XML DOM */
    fun parseIntoDom(xml: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val builder = factory.newDocumentBuilder()
        return xml.use { 
            builder.parse(InputSource(it))
        }
    }

    /**
     * Sign an EBICS document with the authentication and identity signature.
     */
    fun signEbicsDocument(
        doc: Document,
        signingPriv: PrivateKey,
        withEbics3: Boolean = false
    ) {
        val ns = if (withEbics3) "urn:org:ebics:H005" else "urn:org:ebics:H004"
        val authSigNode = XPathFactory.newInstance().newXPath()
            .evaluate("/*[1]/$ns:AuthSignature", doc, XPathConstants.NODE)
        if (authSigNode !is Node)
            throw java.lang.Exception("no AuthSignature")
        val fac = XMLSignatureFactory.getInstance("DOM")
        val c14n = fac.newTransform(CanonicalizationMethod.INCLUSIVE, null as TransformParameterSpec?)
        val ref: Reference =
            fac.newReference(
                "#xpointer(//*[@authenticate='true'])",
                fac.newDigestMethod(DigestMethod.SHA256, null),
                listOf(c14n),
                null,
                null
            )
        val canon: CanonicalizationMethod =
            fac.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, null as C14NMethodParameterSpec?)
        val signatureMethod = fac.newSignatureMethod("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256", null)
        val si: SignedInfo = fac.newSignedInfo(canon, signatureMethod, listOf(ref))
        val sig: XMLSignature = fac.newXMLSignature(si, null)
        val dsc = DOMSignContext(signingPriv, authSigNode)
        dsc.defaultNamespacePrefix = "ds"
        dsc.uriDereferencer = EbicsSigUriDereferencer()
        dsc.setProperty("javax.xml.crypto.dsig.cacheReference", true)
        sig.sign(dsc)
        val innerSig = authSigNode.firstChild
        while (innerSig.hasChildNodes()) {
            authSigNode.appendChild(innerSig.firstChild)
        }
        authSigNode.removeChild(innerSig)
    }

    fun verifyEbicsDocument(
        doc: Document,
        signingPub: PublicKey,
        withEbics3: Boolean = false
    ): Boolean {
        val doc2: Document = doc.cloneNode(true) as Document
        val ns = if (withEbics3) "urn:org:ebics:H005" else "urn:org:ebics:H004"
        val authSigNode = XPathFactory.newInstance().newXPath()
            .evaluate("/*[1]/$ns:AuthSignature", doc2, XPathConstants.NODE)
        if (authSigNode !is Node)
            throw java.lang.Exception("no AuthSignature")
        val sigEl = doc2.createElementNS("http://www.w3.org/2000/09/xmldsig#", "ds:Signature")
        authSigNode.parentNode.insertBefore(sigEl, authSigNode)
        while (authSigNode.hasChildNodes()) {
            sigEl.appendChild(authSigNode.firstChild)
        }
        authSigNode.parentNode.removeChild(authSigNode)
        val fac = XMLSignatureFactory.getInstance("DOM")
        val dvc = DOMValidateContext(signingPub, sigEl)
        dvc.setProperty("javax.xml.crypto.dsig.cacheReference", true)
        dvc.uriDereferencer = EbicsSigUriDereferencer()
        val sig = fac.unmarshalXMLSignature(dvc)
        // FIXME: check that parameters are okay!
        val valResult = sig.validate(dvc)
        sig.signedInfo.references[0].validate(dvc)
        return valResult
    }
}