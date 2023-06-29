package tech.libeufin.util.ebics_h005

import org.apache.xml.security.binding.xmldsig.SignatureType
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.EbicsStandardOrderParams
import tech.libeufin.util.EbicsOrderParams
import tech.libeufin.util.makeEbics3DateRange
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import java.util.*
import javax.xml.bind.annotation.*
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter
import javax.xml.bind.annotation.adapters.HexBinaryAdapter
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter
import javax.xml.datatype.XMLGregorianCalendar

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "", propOrder = ["header", "authSignature", "body"])
@XmlRootElement(name = "ebicsRequest")
class Ebics3Request {
    @get:XmlAttribute(name = "Version", required = true)
    @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
    lateinit var version: String

    @get:XmlAttribute(name = "Revision")
    var revision: Int? = null

    @get:XmlElement(name = "header", required = true)
    lateinit var header: Header

    @get:XmlElement(name = "AuthSignature", required = true)
    lateinit var authSignature: SignatureType

    @get:XmlElement(name = "body")
    lateinit var body: Body

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["static", "mutable"])
    class Header {
        @get:XmlElement(name = "static", required = true)
        lateinit var static: StaticHeaderType

        @get:XmlElement(required = true)
        lateinit var mutable: MutableHeader

        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "",
        propOrder = [
            "hostID", "nonce", "timestamp", "partnerID", "userID", "systemID",
            "product", "orderDetails", "bankPubKeyDigests", "securityMedium",
            "numSegments", "transactionID"
        ]
    )
    class StaticHeaderType {
        @get:XmlElement(name = "HostID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var hostID: String

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "Nonce", type = String::class)
        @get:XmlJavaTypeAdapter(HexBinaryAdapter::class)
        @get:XmlSchemaType(name = "hexBinary")
        var nonce: ByteArray? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "Timestamp")
        @get:XmlSchemaType(name = "dateTime")
        var timestamp: XMLGregorianCalendar? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "PartnerID")
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        var partnerID: String? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "UserID")
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        var userID: String? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "SystemID")
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        var systemID: String? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "Product")
        var product: Ebics3Types.Product? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "OrderDetails")
        var orderDetails: OrderDetails? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "BankPubKeyDigests")
        var bankPubKeyDigests: BankPubKeyDigests? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "SecurityMedium")
        var securityMedium: String? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElement(name = "NumSegments")
        var numSegments: BigInteger? = null

        /**
         * Present only in the transaction / finalization phase.
         */
        @get:XmlElement(name = "TransactionID")
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        var transactionID: String? = null
    }


    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["transactionPhase", "segmentNumber"])
    class MutableHeader {
        @get:XmlElement(name = "TransactionPhase", required = true)
        @get:XmlSchemaType(name = "token")
        lateinit var transactionPhase: Ebics3Types.TransactionPhaseType

        /**
         * Number of the currently transmitted segment, if this message
         * contains order data.
         */
        @get:XmlElement(name = "SegmentNumber")
        var segmentNumber: Ebics3Types.SegmentNumber? = null

    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(
        name = "",
        propOrder = ["adminOrderType", "btdOrderParams", "btuOrderParams", "orderID", "orderParams"]
    )
    class OrderDetails {
        @get:XmlElement(name = "AdminOrderType", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        lateinit var adminOrderType: String

        @XmlAccessorType(XmlAccessType.NONE)
        @XmlType(propOrder = ["serviceName", "scope", "serviceOption", "container", "messageName"])
        class Service {
            @get:XmlElement(name = "ServiceName", required = true)
            @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
            lateinit var serviceName: String

            @get:XmlElement(name = "Scope", required = true)
            @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
            lateinit var scope: String

            @XmlAccessorType(XmlAccessType.NONE)
            @XmlType(name = "", propOrder = ["value"])
            class MessageName {
                @XmlValue
                lateinit var value: String

                @XmlAttribute(name = "version")
                var version: String? = null
            }

            @get:XmlElement(name = "MsgName", required = true)
            lateinit var messageName: MessageName

            @get:XmlElement(name = "ServiceOption", required = true)
            @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
            var serviceOption: String? = null

            @XmlAccessorType(XmlAccessType.NONE)
            class Container {
                @XmlAttribute(name = "containerType")
                lateinit var containerType: String
            }

            @get:XmlElement(name = "Container", required = true)
            var container: Container? = null
        }

        @XmlAccessorType(XmlAccessType.NONE)
        class BTOrderParams {
            @get:XmlElement(name = "Service", required = true)
            lateinit var service: Service

            @get:XmlElement(name = "DateRange", required = true)
            var dateRange: DateRange? = null
        }

        @get:XmlElement(name = "BTUOrderParams", required = true)
        var btuOrderParams: BTOrderParams? = null

        @get:XmlElement(name = "BTDOrderParams", required = true)
        var btdOrderParams: BTOrderParams? = null

        /**
         * Only present if this ebicsRequest is an upload order
         * relating to an already existing order.
         */
        @get:XmlElement(name = "OrderID", required = true)
        @get:XmlJavaTypeAdapter(CollapsedStringAdapter::class)
        var orderID: String? = null

        /**
         * Present only in the initialization phase.
         */
        @get:XmlElements(
            XmlElement(
                name = "StandardOrderParams",
                type = StandardOrderParams::class // OrderParams inheritor
            ),
            XmlElement(
                name = "GenericOrderParams",
                type = GenericOrderParams::class // OrderParams inheritor
            )
        )
        // Same as the 2.5 version.
        var orderParams: OrderParams? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(propOrder = ["preValidation", "dataTransfer", "transferReceipt"])
    class Body {
        @get:XmlElement(name = "PreValidation")
        var preValidation: PreValidation? = null

        @get:XmlElement(name = "DataTransfer")
        var dataTransfer: DataTransfer? = null

        @get:XmlElement(name = "TransferReceipt")
        var transferReceipt: TransferReceipt? = null
    }

    /**
     * FIXME: not implemented yet
     */
    @XmlAccessorType(XmlAccessType.NONE)
    class PreValidation {
        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false
    }

    @XmlAccessorType(XmlAccessType.NONE)
    class SignatureData {
        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false

        @get:XmlValue
        var value: ByteArray? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(propOrder = ["dataEncryptionInfo", "signatureData", "dataDigest", "orderData", "hostId"])
    class DataTransfer {

        @get:XmlElement(name = "DataEncryptionInfo")
        var dataEncryptionInfo: Ebics3Types.DataEncryptionInfo? = null

        @get:XmlElement(name = "SignatureData")
        var signatureData: SignatureData? = null

        @XmlAccessorType(XmlAccessType.NONE)
        class DataDigest {
            @get:XmlAttribute(name = "SignatureVersion", required = true)
            var signatureVersion: String = "A006"

            @get:XmlValue
            var value: ByteArray? = null
        }

        @get:XmlElement(name = "DataDigest")
        var dataDigest: DataDigest? = null

        @get:XmlElement(name = "OrderData")
        var orderData: String? = null

        @get:XmlElement(name = "HostID")
        var hostId: String? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["receiptCode"])
    class TransferReceipt {
        @get:XmlAttribute(name = "authenticate", required = true)
        var authenticate: Boolean = false

        @get:XmlElement(name = "ReceiptCode")
        var receiptCode: Int? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    abstract class  OrderParams

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["dateRange"])
    class StandardOrderParams : OrderParams() {
        @get:XmlElement(name = "DateRange")
        var dateRange: DateRange? = null
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["parameterList"])
    class GenericOrderParams : OrderParams() {
        @get:XmlElement(type = Ebics3Types.Parameter::class)
        var parameterList: List<Ebics3Types.Parameter> = LinkedList()
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["start", "end"])
    class DateRange {
        @get:XmlElement(name = "Start")
        @get:XmlSchemaType(name = "date")
        lateinit var start: XMLGregorianCalendar

        @get:XmlElement(name = "End")
        @get:XmlSchemaType(name = "date")
        lateinit var end: XMLGregorianCalendar
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlType(name = "", propOrder = ["authentication", "encryption"])
    class BankPubKeyDigests {
        @get:XmlElement(name = "Authentication")
        lateinit var authentication: Ebics3Types.PubKeyDigest

        @get:XmlElement(name = "Encryption")
        lateinit var encryption: Ebics3Types.PubKeyDigest
    }

    companion object {

        fun createForDownloadReceiptPhase(
            transactionId: String?,
            hostId: String

        ): Ebics3Request {
            return Ebics3Request().apply {
                header = Header().apply {
                    version = "H005"
                    revision = 1
                    authenticate = true
                    static = StaticHeaderType().apply {
                        hostID = hostId
                        transactionID = transactionId
                    }
                    mutable = MutableHeader().apply {
                        transactionPhase = Ebics3Types.TransactionPhaseType.RECEIPT
                    }
                }
                authSignature = SignatureType()

                body = Body().apply {
                    transferReceipt = TransferReceipt().apply {
                        authenticate = true
                        receiptCode = 0 // always true at this point.
                    }
                }
            }
        }

        fun createForDownloadInitializationPhase(
            userId: String,
            partnerId: String,
            hostId: String,
            nonceArg: ByteArray,
            date: XMLGregorianCalendar,
            bankEncPub: RSAPublicKey,
            bankAuthPub: RSAPublicKey,
            myOrderService: OrderDetails.Service,
            myOrderParams: EbicsOrderParams? = null
        ): Ebics3Request {
            return Ebics3Request().apply {
                version = "H005"
                revision = 1
                authSignature = SignatureType()
                body = Body()
                header = Header().apply {
                    authenticate = true
                    static = StaticHeaderType().apply {
                        userID = userId
                        partnerID = partnerId
                        hostID = hostId
                        nonce = nonceArg
                        timestamp = date
                        partnerID = partnerId
                        orderDetails = OrderDetails().apply {
                            this.adminOrderType = "BTD"
                            this.btdOrderParams = OrderDetails.BTOrderParams().apply {
                                service = myOrderService
                                // Order params only used for date ranges so far.
                                when (myOrderParams) {
                                    is EbicsStandardOrderParams -> {
                                        this.dateRange = makeEbics3DateRange(myOrderParams.dateRange)
                                    }
                                    else -> {}
                                }
                            }
                        }
                        bankPubKeyDigests = BankPubKeyDigests().apply {
                            authentication = Ebics3Types.PubKeyDigest().apply {
                                algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                version = "X002"
                                value = CryptoUtil.getEbicsPublicKeyHash(bankAuthPub)
                            }
                            encryption = Ebics3Types.PubKeyDigest().apply {
                                algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                version = "E002"
                                value = CryptoUtil.getEbicsPublicKeyHash(bankEncPub)
                            }
                            securityMedium = "0000"
                        }
                    }
                    mutable = MutableHeader().apply {
                        transactionPhase =
                            Ebics3Types.TransactionPhaseType.INITIALISATION
                    }
                }
            }
        }

        fun createForUploadInitializationPhase(
            encryptedTransactionKey: ByteArray,
            encryptedSignatureData: ByteArray,
            aDataDigest: ByteArray,
            hostId: String,
            nonceArg: ByteArray,
            partnerId: String,
            userId: String,
            date: XMLGregorianCalendar,
            bankAuthPub: RSAPublicKey,
            bankEncPub: RSAPublicKey,
            segmentsNumber: BigInteger,
            aOrderService: OrderDetails.Service,
            // aOrderParams: OrderParamsEbics? = null
        ): Ebics3Request {

            return Ebics3Request().apply {
                header = Header().apply {
                    version = "H005"
                    revision = 1
                    authenticate = true
                    static = StaticHeaderType().apply {
                        hostID = hostId
                        nonce = nonceArg
                        timestamp = date
                        partnerID = partnerId
                        userID = userId
                        orderDetails = OrderDetails().apply {
                            this.adminOrderType = "BTU"
                            this.btuOrderParams = OrderDetails.BTOrderParams().apply {
                                service = aOrderService
                            }
                        }
                        bankPubKeyDigests = BankPubKeyDigests().apply {
                            authentication = Ebics3Types.PubKeyDigest().apply {
                                algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                version = "X002"
                                value = CryptoUtil.getEbicsPublicKeyHash(bankAuthPub)
                            }
                            encryption = Ebics3Types.PubKeyDigest().apply {
                                algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                version = "E002"
                                value = CryptoUtil.getEbicsPublicKeyHash(bankEncPub)
                            }
                        }
                        securityMedium = "0000"
                        numSegments = segmentsNumber
                    }
                    mutable = MutableHeader().apply {
                        transactionPhase =
                            Ebics3Types.TransactionPhaseType.INITIALISATION
                    }
                }
                authSignature = SignatureType()
                body = Body().apply {
                    dataTransfer = DataTransfer().apply {
                        signatureData = SignatureData().apply {
                            authenticate = true
                            value = encryptedSignatureData
                        }
                        dataDigest = DataTransfer.DataDigest().apply {
                            value = aDataDigest
                        }
                        dataEncryptionInfo = Ebics3Types.DataEncryptionInfo().apply {
                            transactionKey = encryptedTransactionKey
                            authenticate = true
                            encryptionPubKeyDigest = Ebics3Types.PubKeyDigest().apply {
                                algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                version = "E002"
                                value = CryptoUtil.getEbicsPublicKeyHash(bankEncPub)
                            }
                        }
                    }
                }
            }
        }

        fun createForUploadTransferPhase(
            hostId: String,
            transactionId: String?,
            segNumber: BigInteger,
            encryptedData: String
        ): Ebics3Request {
            return Ebics3Request().apply {
                header = Header().apply {
                    version = "H005"
                    revision = 1
                    authenticate = true
                    static = StaticHeaderType().apply {
                        hostID = hostId
                        transactionID = transactionId
                    }
                    mutable = MutableHeader().apply {
                        transactionPhase = Ebics3Types.TransactionPhaseType.TRANSFER
                        segmentNumber = Ebics3Types.SegmentNumber().apply {
                            lastSegment = true
                            value = segNumber
                        }
                    }
                }

                authSignature = SignatureType()
                body = Body().apply {
                    dataTransfer = DataTransfer().apply {
                        orderData = encryptedData
                    }
                }
            }
        }

        fun createForDownloadTransferPhase(
            hostID: String,
            transactionID: String?,
            segmentNumber: Int,
            numSegments: Int
        ): Ebics3Request {
            return Ebics3Request().apply {
                version = "H005"
                revision = 1
                authSignature = SignatureType()
                body = Body()
                header = Header().apply {
                    authenticate = true
                    static = StaticHeaderType().apply {
                        this.hostID = hostID
                        this.transactionID = transactionID
                    }
                    mutable = MutableHeader().apply {
                        transactionPhase =
                            Ebics3Types.TransactionPhaseType.TRANSFER
                        this.segmentNumber = Ebics3Types.SegmentNumber().apply {
                            this.value = BigInteger.valueOf(segmentNumber.toLong())
                            this.lastSegment = segmentNumber == numSegments
                        }
                    }
                }
            }
        }
    }
}