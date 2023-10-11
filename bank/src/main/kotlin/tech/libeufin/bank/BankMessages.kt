/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

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

package tech.libeufin.bank

import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.serialization.Serializable
import net.taler.wallet.crypto.Base32Crockford
import net.taler.wallet.crypto.EncodingException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

/**
 * 32-byte Crockford's Base32 encoded data.
 */
@Serializable(with = Base32Crockford32B.Serializer::class)
class Base32Crockford32B {
    private var encoded: String? = null
    val raw: ByteArray

    constructor(encoded: String) {
        val decoded = try {
            Base32Crockford.decode(encoded) 
        } catch (e: EncodingException) {
            null
        }
        
        require(decoded != null) {
            "Data should be encoded using Crockford's Base32"
        }
        require(decoded.size == 32) {
            "Encoded data should be 32 bytes long"
        }
        this.raw = decoded
        this.encoded = encoded
    }
    constructor(raw: ByteArray) {
        require(raw.size == 32) {
            "Encoded data should be 32 bytes long"
        }
        this.raw = raw
    }

    fun encoded(): String {
        encoded = encoded ?: Base32Crockford.encode(raw)
        return encoded!!
    }

    override fun equals(other: Any?) = (other is Base32Crockford32B) && Arrays.equals(raw, other.raw)

    internal object Serializer : KSerializer<Base32Crockford32B> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Base32Crockford32B", PrimitiveKind.STRING)
    
        override fun serialize(encoder: Encoder, value: Base32Crockford32B) {
            encoder.encodeString(value.encoded())
        }
    
        override fun deserialize(decoder: Decoder): Base32Crockford32B {
            return Base32Crockford32B(decoder.decodeString())
        }
    }
}

/**
 * 64-byte Crockford's Base32 encoded data.
 */
@Serializable(with = Base32Crockford64B.Serializer::class)
class Base32Crockford64B {
    private var encoded: String? = null
    val raw: ByteArray

    constructor(encoded: String) {
        val decoded = try {
            Base32Crockford.decode(encoded) 
        } catch (e: EncodingException) {
            null
        }
        
        require(decoded != null) {
            "Data should be encoded using Crockford's Base32"
        }
        require(decoded.size == 64) {
            "Encoded data should be 32 bytes long"
        }
        this.raw = decoded
        this.encoded = encoded
    }
    constructor(raw: ByteArray) {
        require(raw.size == 64) {
            "Encoded data should be 32 bytes long"
        }
        this.raw = raw
    }

    fun encoded(): String {
        encoded = encoded ?: Base32Crockford.encode(raw)
        return encoded!!
    }

    override fun equals(other: Any?) = (other is Base32Crockford64B) && Arrays.equals(raw, other.raw)

    internal object Serializer : KSerializer<Base32Crockford64B> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Base32Crockford64B", PrimitiveKind.STRING)
    
        override fun serialize(encoder: Encoder, value: Base32Crockford64B) {
            encoder.encodeString(value.encoded())
        }
    
        override fun deserialize(decoder: Decoder): Base32Crockford64B {
            return Base32Crockford64B(decoder.decodeString())
        }
    }
}

/** 32-byte hash code. */
typealias ShortHashCode = Base32Crockford32B;
/** 64-byte hash code. */
typealias HashCode = Base32Crockford64B;
/**
 * EdDSA and ECDHE public keys always point on Curve25519
 * and represented  using the standard 256 bits Ed25519 compact format,
 * converted to Crockford Base32.
 */
typealias EddsaPublicKey = Base32Crockford32B;

/**
 * Allowed lengths for fractional digits in amounts.
 */
enum class FracDigits {
    TWO, EIGHT
}

/**
 * Timestamp containing the number of seconds since epoch.
 */
@Serializable(with = TalerProtocolTimestampSerializer::class)
data class TalerProtocolTimestamp(
    val t_s: Instant,
) {
    companion object {
        fun fromMicroseconds(uSec: Long): TalerProtocolTimestamp {
            return TalerProtocolTimestamp(
                Instant.EPOCH.plus(uSec, ChronoUnit.MICROS)
            )
        }
    }
}

/**
 * HTTP response type of successful token refresh.
 * access_token is the Crockford encoding of the 32 byte
 * access token, whereas 'expiration' is the point in time
 * when this token expires.
 */
@Serializable
data class TokenSuccessResponse(
    val access_token: String,
    val expiration: TalerProtocolTimestamp
)

/**
 * Error object to respond to the client.  The
 * 'code' field takes values from the GANA gnu-taler-error-code
 * specification.  'hint' is a human-readable description
 * of the error.
 */
@Serializable
data class TalerError(
    val code: Int,
    val hint: String? = null,
    val detail: String? = null
)

/* Contains contact data to send TAN challges to the
* users, to let them complete cashout operations. */
@Serializable
data class ChallengeContactData(
    val email: String? = null,
    val phone: String? = null
)

// Type expected at POST /accounts
@Serializable
data class RegisterAccountRequest(
    val username: String,
    val password: String,
    val name: String,
    val is_public: Boolean = false,
    val is_taler_exchange: Boolean = false,
    val challenge_contact_data: ChallengeContactData? = null,
    // External bank account where to send cashout amounts.
    val cashout_payto_uri: String? = null,
    // Bank account internal to Libeufin-Bank.
    val internal_payto_uri: String? = null
)

/**
 * Internal representation of relative times.  The
 * "forever" case is represented with Long.MAX_VALUE.
 */
@Serializable(with = RelativeTimeSerializer::class)
data class RelativeTime(
    val d_us: Duration
)

/**
 * Type expected at POST /accounts/{USERNAME}/token
 * It complies with Taler's design document #49
 */
@Serializable
data class TokenRequest(
    val scope: TokenScope,
    val duration: RelativeTime? = null,
    val refreshable: Boolean = false
)

/**
 * Convenience type to throw errors along the bank activity
 * and that is meant to be caught by Ktor and responded to the
 * client.
 */
class LibeufinBankException(
    // Status code that Ktor will set for the response.
    val httpStatus: HttpStatusCode,
    // Error detail object, after Taler API.
    val talerError: TalerError
) : Exception(talerError.hint)

/**
 * Convenience type to hold customer data, typically after such
 * data gets fetched from the database.  It is also used to _insert_
 * customer data to the database.
 */
data class Customer(
    val login: String,
    val passwordHash: String,
    val name: String,
    /**
     * Only non-null when this object is defined _by_ the
     * database.
     */
    val dbRowId: Long? = null,
    val email: String? = null,
    val phone: String? = null,
    /**
     * External bank account where customers send
     * their cashout amounts.
     */
    val cashoutPayto: String? = null,
    /**
     * Currency of the external bank account where
     * customers send their cashout amounts.
     */
    val cashoutCurrency: String? = null
)

/**
 * Represents a Taler amount.  This type can be used both
 * to hold database records and amounts coming from the parser.
 * If maybeCurrency is null, then the constructor defaults it
 * to be the "internal currency".  Internal currency is the one
 * with which Libeufin-Bank moves funds within itself, therefore
 * not to be mistaken with the cashout currency, which is the one
 * that gets credited to Libeufin-Bank users to their cashout_payto_uri.
 *
 * maybeCurrency is typically null when the TalerAmount object gets
 * defined by the Database class.
 */
@Serializable(with = TalerAmountSerializer::class)
class TalerAmount(
    val value: Long,
    val frac: Int,
    val currency: String
) {
    override fun equals(other: Any?): Boolean {
        return other is TalerAmount &&
                other.value == this.value &&
                other.frac == this.frac &&
                other.currency == this.currency
    }

    override fun toString(): String {
        val fracNoTrailingZero = this.frac.toString().dropLastWhile { it == '0' }
        if (fracNoTrailingZero.isEmpty()) return "$currency:$value"
        return "$currency:$value.$fracNoTrailingZero"
    }
}

/**
 * Convenience type to get and set bank account information
 * from/to the database.
 */
data class BankAccount(
    val internalPaytoUri: String,
    // Database row ID of the customer that owns this bank account.
    val owningCustomerId: Long,
    val bankAccountId: Long? = null, // null at INSERT.
    val isPublic: Boolean = false,
    val isTalerExchange: Boolean = false,
    /**
     * Because bank accounts MAY be funded by an external currency,
     * local bank accounts need to query Nexus, in order to find this
     * out.  This field is a pointer to the latest incoming payment that
     * was contained in a Nexus history response.
     *
     * Typically, the 'admin' bank account uses this field, in order
     * to initiate Taler withdrawals that depend on an external currency
     * being wired by wallet owners.
     */
    val lastNexusFetchRowId: Long = 0L,
    val balance: TalerAmount? = null, // null when a new bank account gets created.
    val hasDebt: Boolean,
    val maxDebt: TalerAmount
)

// Allowed values for bank transactions directions.
enum class TransactionDirection {
    credit,
    debit
}

// Allowed values for cashout TAN channels.
enum class TanChannel {
    sms,
    email,
    file // Writes cashout TANs to /tmp, for testing.
}

// Scopes for authentication tokens.
enum class TokenScope {
    readonly,
    readwrite,
    refreshable // Not spec'd as a scope!
}

/**
 * Convenience type to set/get authentication tokens to/from
 * the database.
 */
data class BearerToken(
    val content: ByteArray,
    val scope: TokenScope,
    val isRefreshable: Boolean = false,
    val creationTime: Instant,
    val expirationTime: Instant,
    /**
     * Serial ID of the database row that hosts the bank customer
     * that is associated with this token.  NOTE: if the token is
     * refreshed by a client that doesn't have a user+password login
     * in the system, the creator remains always the original bank
     * customer that created the very first token.
     */
    val bankCustomer: Long
)

/**
 * Convenience type to _communicate_ a bank transfer to the
 * database procedure, NOT representing therefore any particular
 * table.  The procedure will then retrieve all the tables data
 * from this type.
 */
data class BankInternalTransaction(
    // Database row ID of the internal bank account sending the payment.
    val creditorAccountId: Long,
    // Database row ID of the internal bank account receiving the payment.
    val debtorAccountId: Long,
    val subject: String,
    val amount: TalerAmount,
    val transactionDate: Instant,
    val accountServicerReference: String = "not used", // ISO20022
    val endToEndId: String = "not used", // ISO20022
    val paymentInformationId: String = "not used" // ISO20022
)

/**
 * Convenience type representing bank transactions as they
 * are in the respective database table.  Only used to _get_
 * the information from the database.
 */
data class BankAccountTransaction(
    val creditorPaytoUri: String,
    val creditorName: String,
    val debtorPaytoUri: String,
    val debtorName: String,
    val subject: String,
    val amount: TalerAmount,
    val transactionDate: Instant,
    /**
     * Is the transaction debit, or credit for the
     * bank account pointed by this object?
     */
    val direction: TransactionDirection,
    /**
     * database row ID of the bank account that is
     * impacted by the direction.  For example, if the
     * direction is debit, then this value points to the
     * bank account of the payer.
     */
    val bankAccountId: Long,
    // Null if this type is used to _create_ one transaction.
    val dbRowId: Long? = null,
    // Following are ISO20022 specific.
    val accountServicerReference: String,
    val paymentInformationId: String,
    val endToEndId: String,
)

/**
 * Represents a Taler withdrawal operation, as it is
 * stored in the respective database table.
 */
data class TalerWithdrawalOperation(
    val withdrawalUuid: UUID,
    val amount: TalerAmount,
    val selectionDone: Boolean = false,
    val aborted: Boolean = false,
    val confirmationDone: Boolean = false,
    val reservePub: String?,
    val selectedExchangePayto: String?,
    val walletBankAccount: Long
)

/**
 * Represents a cashout operation, as it is stored
 * in the respective database table.
 */
data class Cashout(
    val cashoutUuid: UUID,
    val localTransaction: Long? = null,
    val amountDebit: TalerAmount,
    val amountCredit: TalerAmount,
    val buyAtRatio: Int,
    val buyInFee: TalerAmount,
    val sellAtRatio: Int,
    val sellOutFee: TalerAmount,
    val subject: String,
    val creationTime: Instant,
    val tanConfirmationTime: Instant? = null,
    val tanChannel: TanChannel,
    val tanCode: String,
    val bankAccount: Long,
    val credit_payto_uri: String,
    val cashoutCurrency: String
)

// Type to return as GET /config response
@Serializable // Never used to parse JSON.
data class Config(
    val currency: CurrencySpecification,
) {
    val name: String = "libeufin-bank"
    val version: String = "0:0:0"
    val have_cashout: Boolean = false
    // Following might probably get renamed:
    val fiat_currency: String? = null
}

enum class CorebankCreditDebitInfo {
    credit, debit
}

@Serializable
data class Balance(
    val amount: TalerAmount,
    val credit_debit_indicator: CorebankCreditDebitInfo,
)

/**
 * GET /accounts response.
 */
@Serializable
data class AccountMinimalData(
    val username: String,
    val name: String,
    val balance: Balance,
    val debit_threshold: TalerAmount
)

/**
 * Response type of GET /accounts.
 */
@Serializable
data class ListBankAccountsResponse(
    val accounts: MutableList<AccountMinimalData> = mutableListOf()
)

/**
 * GET /accounts/$USERNAME response.
 */
@Serializable
data class AccountData(
    val name: String,
    val balance: Balance,
    val payto_uri: String,
    val debit_threshold: TalerAmount,
    val contact_data: ChallengeContactData? = null,
    val cashout_payto_uri: String? = null,
)

/**
 * Response type of corebank API transaction initiation.
 */
@Serializable
data class BankAccountTransactionCreate(
    val payto_uri: String,
    val amount: TalerAmount
)

/* History element, either from GET /transactions/T_ID
  or from GET /transactions */
@Serializable
data class BankAccountTransactionInfo(
    val creditor_payto_uri: String,
    val debtor_payto_uri: String,
    val amount: TalerAmount,
    val direction: TransactionDirection,
    val subject: String,
    val row_id: Long, // is T_ID
    val date: TalerProtocolTimestamp
)

// Response type for histories, namely GET /transactions
@Serializable
data class BankAccountTransactionsResponse(
    val transactions: List<BankAccountTransactionInfo>
)

// Taler withdrawal request.
@Serializable
data class BankAccountCreateWithdrawalRequest(
    val amount: TalerAmount
)

// Taler withdrawal response.
@Serializable
data class BankAccountCreateWithdrawalResponse(
    val withdrawal_id: String,
    val taler_withdraw_uri: String
)

// Taler withdrawal details response
@Serializable
data class BankAccountGetWithdrawalResponse(
    val amount: TalerAmount,
    val aborted: Boolean,
    val confirmation_done: Boolean,
    val selection_done: Boolean,
    val selected_reserve_pub: String? = null,
    val selected_exchange_account: String? = null
)

typealias ResourceName = String

/**
 * Checks if the input Customer has the rights over ResourceName.
 */
fun ResourceName.canI(c: Customer, withAdmin: Boolean = true): Boolean {
    if (c.login == this) return true
    if (c.login == "admin" && withAdmin) return true
    return false
}

/**
 * Factors out the retrieval of the resource name from
 * the URI.  The resource looked for defaults to "USERNAME"
 * as this is frequently mentioned resource along the endpoints.
 *
 * This helper is recommended because it returns a ResourceName
 * type that then offers the ".canI()" helper to check if the user
 * has the rights on the resource.
 */
fun ApplicationCall.getResourceName(param: String): ResourceName =
    this.expectUriComponent(param)

/**
 * This type communicates the result of deleting an account
 * from the database.
 */
enum class CustomerDeletionResult {
    SUCCESS,
    CUSTOMER_NOT_FOUND,
    BALANCE_NOT_ZERO
}

/**
 * This type communicates the result of a database operation
 * to confirm one withdrawal operation.
 */
enum class WithdrawalConfirmationResult {
    SUCCESS,
    OP_NOT_FOUND,
    EXCHANGE_NOT_FOUND,
    BALANCE_INSUFFICIENT,

    /**
     * This state indicates that the withdrawal was already
     * confirmed BUT Kotlin did not detect it and still invoked
     * the SQL procedure to confirm the withdrawal.  This is
     * conflictual because only Kotlin is responsible to check
     * for idempotency, and this state witnesses a failure in
     * this regard.
     */
    CONFLICT
}

/**
 * Communicates the result of creating a bank transaction in the database.
 */
enum class BankTransactionResult {
    NO_CREDITOR,
    NO_DEBTOR,
    SUCCESS,
    CONFLICT // balance insufficient
}

// GET /config response from the Taler Integration API.
@Serializable
data class TalerIntegrationConfigResponse(
    val currency: String,
    val currency_specification: CurrencySpecification,
) {
    val name: String = "taler-bank-integration";
    val version: String = "0:0:0";
}

@Serializable
data class CurrencySpecification(
    val name: String,
    val decimal_separator: String,
    val num_fractional_input_digits: Int,
    val num_fractional_normal_digits: Int,
    val num_fractional_trailing_zero_digits: Int,
    val is_currency_name_leading: Boolean,
    val alt_unit_names: Map<String, String>
)

/**
 * Withdrawal status as specified in the Taler Integration API.
 */
@Serializable
data class BankWithdrawalOperationStatus(
    // Indicates whether the withdrawal was aborted.
    val aborted: Boolean,

    /* Has the wallet selected parameters for the withdrawal operation
      (exchange and reserve public key) and successfully sent it
      to the bank? */
    val selection_done: Boolean,

    /* The transfer has been confirmed and registered by the bank.
       Does not guarantee that the funds have arrived at the exchange
       already. */
    val transfer_done: Boolean,

    /* Amount that will be withdrawn with this operation
       (raw amount without fee considerations). */
    val amount: TalerAmount,

    /* Bank account of the customer that is withdrawing, as a
      ``payto`` URI. */
    val sender_wire: String? = null,

    // Suggestion for an exchange given by the bank.
    val suggested_exchange: String? = null,

    /* URL that the user needs to navigate to in order to
       complete some final confirmation (e.g. 2FA).
       It may contain withdrawal operation id */
    val confirm_transfer_url: String? = null,

    // Wire transfer types supported by the bank.
    val wire_types: MutableList<String> = mutableListOf("iban")
)

/**
 * Selection request on a Taler withdrawal.
 */
@Serializable
data class BankWithdrawalOperationPostRequest(
    val reserve_pub: String,
    val selected_exchange: String,
)

/**
 * Response to the wallet after it selects the exchange
 * and the reserve pub.
 */
@Serializable
data class BankWithdrawalOperationPostResponse(
    val transfer_done: Boolean,
    val confirm_transfer_url: String? = null
)

/**
 * Request to an /admin/add-incoming request from
 * the Taler Wire Gateway API.
 */
@Serializable
data class AddIncomingRequest(
    val amount: TalerAmount,
    val reserve_pub: EddsaPublicKey,
    val debit_account: String
)

/**
 * Response to /admin/add-incoming
 */
@Serializable
data class AddIncomingResponse(
    val timestamp: TalerProtocolTimestamp,
    val row_id: Long
)

@Serializable
data class TWGConfigResponse(
    val name: String = "taler-wire-gateway",
    val version: String = "0:0:0",
    val currency: String
)

/**
 * Response of a TWG /history/incoming call.
 */
@Serializable
data class IncomingHistory(
    val incoming_transactions: List<IncomingReserveTransaction>,
    val credit_account: String // Receiver's Payto URI.
)

/**
 * TWG's incoming payment record.
 */
@Serializable
data class IncomingReserveTransaction(
    val type: String = "RESERVE",
    val row_id: Long, // DB row ID of the payment.
    val date: TalerProtocolTimestamp,
    val amount: TalerAmount,
    val debit_account: String, // Payto of the sender.
    val reserve_pub: EddsaPublicKey
)

/**
 * Response of a TWG /history/outgoing call.
 */
@Serializable
data class OutgoingHistory(
    val outgoing_transactions: List<OutgoingTransaction>,
    val debit_account: String // Debitor's Payto URI.
)

/**
 * TWG's outgoinf payment record.
 */
@Serializable
data class OutgoingTransaction(
    val row_id: Long, // DB row ID of the payment.
    val date: TalerProtocolTimestamp,
    val amount: TalerAmount,
    val credit_account: String, // Payto of the receiver.
    val wtid: ShortHashCode,
    val exchange_base_url: String,
)

/**
 * TWG's request to pay a merchant.
 */
@Serializable
data class TransferRequest(
    val request_uid: HashCode,
    val amount: TalerAmount,
    val exchange_base_url: String,
    val wtid: ShortHashCode,
    val credit_account: String
)

/**
 * TWG's response to merchant payouts
 */
@Serializable
data class TransferResponse(
    val timestamp: TalerProtocolTimestamp,
    val row_id: Long
)

/**
 * Response to GET /public-accounts
 */
@Serializable
data class PublicAccountsResponse(
    val public_accounts: MutableList<PublicAccount> = mutableListOf()
)

/**
 * Single element of GET /public-accounts list.
 */
@Serializable
data class PublicAccount(
    val payto_uri: String,
    val balance: Balance,
    val account_name: String
)

/**
 * Request of PATCH /accounts/{USERNAME}/auth
 */
@Serializable
data class AccountPasswordChange(
    val new_password: String
)

/**
 * Request of PATCH /accounts/{USERNAME}
 */
@Serializable
data class AccountReconfiguration(
    val challenge_contact_data: ChallengeContactData?,
    val cashout_address: String?,
    val name: String?,
    val is_exchange: Boolean?
)

/**
 * This type expresses the outcome of updating the account
 * data in the database.
 */
enum class AccountReconfigDBResult {
    /**
     * This indicates that despite the customer row was
     * found in the database, its related bank account was not.
     * This condition is a hard failure of the bank, since
     * every customer must have one (and only one) bank account.
     */
    BANK_ACCOUNT_NOT_FOUND,

    /**
     * The customer row wasn't found in the database.  This error
     * should be rare, as the client got authenticated in the first
     * place, before the handler could try the reconfiguration in
     * the database.
     */
    CUSTOMER_NOT_FOUND,

    /**
     * Reconfiguration successful.
     */
    SUCCESS
}