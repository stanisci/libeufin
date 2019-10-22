//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.10.22 at 11:07:53 AM CEST 
//


package tech.libeufin.messages.ebics.response;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlSchemaType;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;


/**
 * Data type for public for identification and authentication.
 * 
 * <p>Java class for AuthenticationCertificateInfoType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="AuthenticationCertificateInfoType">
 *   &lt;complexContent>
 *     &lt;extension base="{urn:org:ebics:H004}CertificateInfoType">
 *       &lt;sequence>
 *         &lt;element name="AuthenticationVersion" type="{urn:org:ebics:H004}AuthenticationVersionType"/>
 *       &lt;/sequence>
 *     &lt;/extension>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "AuthenticationCertificateInfoType", propOrder = {
    "authenticationVersion"
})
public class AuthenticationCertificateInfoType
    extends CertificateInfoType
{

    @XmlElement(name = "AuthenticationVersion", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String authenticationVersion;

    /**
     * Gets the value of the authenticationVersion property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getAuthenticationVersion() {
        return authenticationVersion;
    }

    /**
     * Sets the value of the authenticationVersion property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setAuthenticationVersion(String value) {
        this.authenticationVersion = value;
    }

}
