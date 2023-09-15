import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import tech.libeufin.util.internalServerError

enum class CreditDebitIndicator {
    DBIT,
    CRDT
}

enum class EntryStatus {
    BOOK, // Booked
    PDNG, // Pending
    INFO, // Informational
}

class CurrencyAmountDeserializer(jc: Class<*> = CurrencyAmount::class.java) : StdDeserializer<CurrencyAmount>(jc) {
    override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): CurrencyAmount {
        if (p == null) {
            throw UnsupportedOperationException();
        }
        val s = p.valueAsString
        val components = s.split(":")
        // FIXME: error handling!
        return CurrencyAmount(components[0], components[1])
    }
}

class CurrencyAmountSerializer(jc: Class<CurrencyAmount> = CurrencyAmount::class.java) : StdSerializer<CurrencyAmount>(jc) {
    override fun serialize(value: CurrencyAmount?, gen: JsonGenerator?, provider: SerializerProvider?) {
        if (gen == null) {
            throw UnsupportedOperationException()
        }
        if (value == null) {
            gen.writeNull()
        } else {
            gen.writeString("${value.currency}:${value.value}")
        }
    }
}

// FIXME: this type duplicates AmountWithCurrency.
@JsonDeserialize(using = CurrencyAmountDeserializer::class)
@JsonSerialize(using = CurrencyAmountSerializer::class)
data class CurrencyAmount(
    val currency: String,
    val value: String
)

fun CurrencyAmount.toPlainString(): String {
    return "${this.currency}:${this.value}"
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CashAccount(
    val name: String?,
    val currency: String?,
    val iban: String?,
    val otherId: GenericId?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GenericId(
    val id: String,
    val schemeName: String?,
    val proprietarySchemeName: String?,
    val issuer: String?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PrivateIdentification(
    val birthDate: String?,
    val provinceOfBirth: String?,
    val cityOfBirth: String?,
    val countryOfBirth: String?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OrganizationIdentification(
    val bic: String?,
    val lei: String?
)

/**
 * Identification of a party, which can be a private party
 * or an organization.
 *
 * Mapping of ISO 20022 PartyIdentification135.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PartyIdentification(
    val name: String?,
    val countryOfResidence: String?,
    val privateId: PrivateIdentification?,
    val organizationId: OrganizationIdentification?,
    val postalAddress: PostalAddress?,

    /**
     * Identification that applies to both private parties and organizations.
     */
    val otherId: GenericId?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PostalAddress(
    val addressCode: String?,
    val addressProprietaryId: String?,
    val addressProprietarySchemeName: String?,
    val addressProprietaryIssuer: String?,
    val department: String?,
    val subDepartment: String?,
    val streetName: String?,
    val buildingNumber: String?,
    val buildingName: String?,
    val floor: String?,
    val postBox: String?,
    val room: String?,
    val postCode: String?,
    val townName: String?,
    val townLocationName: String?,
    val districtName: String?,
    val countrySubDivision: String?,
    val country: String?,
    val addressLines: List<String>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentIdentification(
    val name: String?,

    val bic: String?,

    /**
     * Legal entity identification.
     */
    val lei: String?,

    val clearingSystemMemberId: String?,

    val clearingSystemCode: String?,

    val proprietaryClearingSystemCode: String?,

    val postalAddress: PostalAddress?,

    val otherId: GenericId?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CurrencyExchange(
    val sourceCurrency: String,
    val targetCurrency: String,
    val unitCurrency: String?,
    val exchangeRate: String,
    val contractId: String?,
    val quotationDate: String?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Batch(
    val messageId: String?,
    val paymentInformationId: String?,
    val batchTransactions: List<BatchTransaction>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TransactionDetails(
    val debtor: PartyIdentification?,
    val debtorAccount: CashAccount?,
    val debtorAgent: AgentIdentification?,
    val creditor: PartyIdentification?,
    val creditorAccount: CashAccount?,
    val creditorAgent: AgentIdentification?,
    val ultimateCreditor: PartyIdentification?,
    val ultimateDebtor: PartyIdentification?,

    val endToEndId: String? = null,
    val paymentInformationId: String? = null,
    val messageId: String? = null,
    val accountServicerRef: String? = null,

    val purpose: String?,
    val proprietaryPurpose: String?,

    /**
     * Currency exchange information for the transaction's amount.
     */
    val currencyExchange: CurrencyExchange?,

    /**
     * Amount as given in the payment initiation.
     * Can be same or different currency as account currency.
     */
    val instructedAmount: CurrencyAmount?,

    /**
     * Raw amount used for currency exchange, before extra charges.
     * Can be same or different currency as account currency.
     */
    val counterValueAmount: CurrencyAmount?,

    /**
     * Money that was moved between banks.
     *
     * For CH, we use the "TxAmt".
     * For EPC, this amount is either blank or taken
     * from the "IBC" proprietary amount.
     */
    val interBankSettlementAmount: CurrencyAmount?,

    // PoFi shown entries lacking it.
    val unstructuredRemittanceInformation: String?,
    val returnInfo: ReturnInfo?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReturnInfo(
    val originalBankTransactionCode: String?,
    val originator: PartyIdentification?,
    val reason: String?,
    val proprietaryReason: String?,
    val additionalInfo: String?
)

data class BatchTransaction(
    val amount: CurrencyAmount, // Fuels Taler withdrawal amount.
    val creditDebitIndicator: CreditDebitIndicator,
    val details: TransactionDetails
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CamtBankAccountEntry(
    val amount: CurrencyAmount,
    /**
     * Is this entry debiting or crediting the account
     * it is reported for?
     */
    val creditDebitIndicator: CreditDebitIndicator,

    /**
     * Booked, pending, etc.
     */
    val status: EntryStatus,

    /**
     * Code that describes the type of bank transaction
     * in more detail
     */
    val bankTransactionCode: String,

    val valueDate: String?,

    val bookingDate: String?,

    val accountServicerRef: String?,

    val entryRef: String?,

    /**
     * Currency exchange information for the entry's amount.
     * Only present if currency exchange happened at the entry level.
     */
    val currencyExchange: CurrencyExchange?,

    /**
     * Value before/after currency exchange before charges have been applied.
     * Only present if currency exchange happened at the entry level.
     */
    val counterValueAmount: CurrencyAmount?,

    /**
     * Instructed amount.
     * Only present if currency exchange happens at the entry level.
     */
    val instructedAmount: CurrencyAmount?,

    // list of sub-transactions participating in this money movement.
    val batches: List<Batch>?
) {
    // Checks that the given list contains only one element and returns it.
    private fun <T>checkAndGetSingleton(maybeTxs: List<T>?): T {
        if (maybeTxs == null || maybeTxs.size > 1) throw internalServerError(
            "Only a singleton transaction is " +
                    "allowed inside ${this.javaClass}."
        )
        return maybeTxs[0]
    }
    private fun getSingletonTxDtls(): TransactionDetails {
        /**
         * Types breakdown until the meaningful payment information is reached.
         *
         * CamtBankAccountEntry contains:
         * - Batch 0
         * - Batch 1
         * - Batch N
         *
         * Batch X contains:
         * - BatchTransaction 0
         * - BatchTransaction 1
         * - BatchTransaction N
         *
         * BatchTransaction X contains:
         * - TransactionDetails
         *
         * TransactionDetails contains the involved parties
         * and the payment subject but MAY NOT contain the amount.
         * In this model, the amount is held in the BatchTransaction
         * type, that is also -- so far -- required to be a singleton
         * inside Batch.
         */
        val batch: Batch = checkAndGetSingleton(this.batches)
        val batchTransactions = batch.batchTransactions
        val tx: BatchTransaction = checkAndGetSingleton(batchTransactions)
        val details: TransactionDetails = tx.details
        return details
    }
    /**
     * This function returns the subject of the unique transaction
     * accounted in this object.  If the transaction is not unique,
     * it throws an exception.  NOTE: the caller has the responsibility
     * of not passing an empty report; those usually should be discarded
     * and never participate in the application logic.
     */
    @JsonIgnore
    fun getSingletonSubject(): String {
        val maybeSubject = getSingletonTxDtls().unstructuredRemittanceInformation
        if (maybeSubject == null) {
            throw internalServerError(
                "The parser let in a transaction without subject" +
                        ", acctSvcrRef: ${this.getSingletonAcctSvcrRef()}."
            )
        }
        return maybeSubject
    }
    @JsonIgnore
    fun getSingletonAcctSvcrRef(): String? {
        return getSingletonTxDtls().accountServicerRef
    }
}