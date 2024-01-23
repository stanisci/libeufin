/**
 * This package-info.java file defines the default namespace for the JAXB bindings
 * defined in the package.
 */

@XmlSchema(
        namespace = "urn:org:ebics:H005",
        elementFormDefault = XmlNsForm.QUALIFIED
)
package tech.libeufin.ebics.ebics_h005;
import javax.xml.bind.annotation.XmlNs;
import javax.xml.bind.annotation.XmlNsForm;
import javax.xml.bind.annotation.XmlSchema;