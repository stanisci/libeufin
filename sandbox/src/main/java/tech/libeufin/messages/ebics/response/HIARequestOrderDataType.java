//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.10.22 at 11:07:53 AM CEST 
//


package tech.libeufin.messages.ebics.response;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAnyElement;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlSchemaType;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import org.w3c.dom.Element;


/**
 * Datentyp für Auftragsdaten für Auftragsart HIA (Anfrage: Initialisierung der Teilnehmerschlüssel für Authentifikation und Verschlüsselung).
 * 
 * <p>Java class for HIARequestOrderDataType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="HIARequestOrderDataType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="AuthenticationPubKeyInfo" type="{urn:org:ebics:H004}AuthenticationPubKeyInfoType"/>
 *         &lt;element name="EncryptionPubKeyInfo" type="{urn:org:ebics:H004}EncryptionPubKeyInfoType"/>
 *         &lt;element name="PartnerID" type="{urn:org:ebics:H004}PartnerIDType"/>
 *         &lt;element name="UserID" type="{urn:org:ebics:H004}UserIDType"/>
 *         &lt;any processContents='lax' namespace='##other' maxOccurs="unbounded" minOccurs="0"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "HIARequestOrderDataType", propOrder = {
    "authenticationPubKeyInfo",
    "encryptionPubKeyInfo",
    "partnerID",
    "userID",
    "any"
})
public class HIARequestOrderDataType {

    @XmlElement(name = "AuthenticationPubKeyInfo", required = true)
    protected AuthenticationPubKeyInfoType authenticationPubKeyInfo;
    @XmlElement(name = "EncryptionPubKeyInfo", required = true)
    protected EncryptionPubKeyInfoType encryptionPubKeyInfo;
    @XmlElement(name = "PartnerID", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String partnerID;
    @XmlElement(name = "UserID", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String userID;
    @XmlAnyElement(lax = true)
    protected List<Object> any;

    /**
     * Gets the value of the authenticationPubKeyInfo property.
     * 
     * @return
     *     possible object is
     *     {@link AuthenticationPubKeyInfoType }
     *     
     */
    public AuthenticationPubKeyInfoType getAuthenticationPubKeyInfo() {
        return authenticationPubKeyInfo;
    }

    /**
     * Sets the value of the authenticationPubKeyInfo property.
     * 
     * @param value
     *     allowed object is
     *     {@link AuthenticationPubKeyInfoType }
     *     
     */
    public void setAuthenticationPubKeyInfo(AuthenticationPubKeyInfoType value) {
        this.authenticationPubKeyInfo = value;
    }

    /**
     * Gets the value of the encryptionPubKeyInfo property.
     * 
     * @return
     *     possible object is
     *     {@link EncryptionPubKeyInfoType }
     *     
     */
    public EncryptionPubKeyInfoType getEncryptionPubKeyInfo() {
        return encryptionPubKeyInfo;
    }

    /**
     * Sets the value of the encryptionPubKeyInfo property.
     * 
     * @param value
     *     allowed object is
     *     {@link EncryptionPubKeyInfoType }
     *     
     */
    public void setEncryptionPubKeyInfo(EncryptionPubKeyInfoType value) {
        this.encryptionPubKeyInfo = value;
    }

    /**
     * Gets the value of the partnerID property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getPartnerID() {
        return partnerID;
    }

    /**
     * Sets the value of the partnerID property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setPartnerID(String value) {
        this.partnerID = value;
    }

    /**
     * Gets the value of the userID property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getUserID() {
        return userID;
    }

    /**
     * Sets the value of the userID property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setUserID(String value) {
        this.userID = value;
    }

    /**
     * Gets the value of the any property.
     * 
     * <p>
     * This accessor method returns a reference to the live list,
     * not a snapshot. Therefore any modification you make to the
     * returned list will be present inside the JAXB object.
     * This is why there is not a <CODE>set</CODE> method for the any property.
     * 
     * <p>
     * For example, to add a new item, do as follows:
     * <pre>
     *    getAny().add(newItem);
     * </pre>
     * 
     * 
     * <p>
     * Objects of the following type(s) are allowed in the list
     * {@link Element }
     * {@link Object }
     * 
     * 
     */
    public List<Object> getAny() {
        if (any == null) {
            any = new ArrayList<Object>();
        }
        return this.any;
    }

}
