//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.10.22 at 11:07:53 AM CEST 
//


package tech.libeufin.messages.ebics.response;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for AuthorisationLevelType.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * <p>
 * <pre>
 * &lt;simpleType name="AuthorisationLevelType">
 *   &lt;restriction base="{http://www.w3.org/2001/XMLSchema}token">
 *     &lt;length value="1"/>
 *     &lt;enumeration value="E"/>
 *     &lt;enumeration value="A"/>
 *     &lt;enumeration value="B"/>
 *     &lt;enumeration value="T"/>
 *   &lt;/restriction>
 * &lt;/simpleType>
 * </pre>
 * 
 */
@XmlType(name = "AuthorisationLevelType")
@XmlEnum
public enum AuthorisationLevelType {


    /**
     * Einzelunterschrift
     * 
     */
    E,

    /**
     * Erstunterschrift
     * 
     */
    A,

    /**
     * Zweitunterschrift
     * 
     */
    B,

    /**
     * Transportunterschrift
     * 
     */
    T;

    public String value() {
        return name();
    }

    public static AuthorisationLevelType fromValue(String v) {
        return valueOf(v);
    }

}
