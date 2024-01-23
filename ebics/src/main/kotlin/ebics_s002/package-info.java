/**
 * This package-info.java file defines the default namespace for the JAXB bindings
 * defined in the package.
 */

@XmlSchema(
        namespace = "http://www.ebics.org/S002",
        elementFormDefault = XmlNsForm.QUALIFIED
)
package tech.libeufin.ebics.ebics_s002;

import javax.xml.bind.annotation.XmlNsForm;
import javax.xml.bind.annotation.XmlSchema;