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
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.util.*

// Allowed lengths for fractional digits in amounts.
enum class FracDigits(howMany: Int) {
    TWO(2),
    EIGHT(8)
}


// It contains the number of microseconds since the Epoch.
@Serializable
data class Timestamp(
    val t_s: Long // FIXME (?): not supporting "never" at the moment.
)

/**
 * HTTP response type of successful token refresh.
 * access_token is the Crockford encoding of the 32 byte
 * access token, whereas 'expiration' is the point in time
 * when this token expires.
 */
@Serializable
data class TokenSuccessResponse(
    val access_token: String,
    val expiration: Timestamp
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
    val hint: String? = null
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

/* Internal representation of relative times.  The
* "forever" case is represented with Long.MAX_VALUE.
*/
data class RelativeTime(
    val d_us: Long
)

/**
 * Type expected at POST /accounts/{USERNAME}/token
 * It complies with Taler's design document #49
 */
@Serializable
data class TokenRequest(
    val scope: TokenScope,
    @Contextual
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
class TalerAmount(
    val value: Long,
    val frac: Int,
    maybeCurrency: String? = null
) {
    val currency: String = if (maybeCurrency == null) {
        val internalCurrency = db.configGet("internal_currency")
            ?: throw internalServerError("internal_currency not found in the config")
        internalCurrency
    } else maybeCurrency

    override fun equals(other: Any?): Boolean {
        return other is TalerAmount &&
                other.value == this.value &&
                other.frac == this.frac &&
                other.currency == this.currency
    }

    override fun toString(): String {
        return "$currency:$value.$frac"
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
    val creationTime: Long,
    val expirationTime: Long,
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
    val transactionDate: Long,
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
    val transactionDate: Long, // microseconds
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
    val creationTime: Long,
    val tanConfirmationTime: Long? = null,
    val tanChannel: TanChannel,
    val tanCode: String,
    val bankAccount: Long,
    val credit_payto_uri: String,
    val cashoutCurrency: String
)

// Type to return as GET /config response
@Serializable // Never used to parse JSON.
data class Config(
    val name: String = "libeufin-bank",
    val version: String = "0:0:0",
    val have_cashout: Boolean = false,
    // Following might probably get renamed:
    val fiat_currency: String? = null
)

// GET /accounts/$USERNAME response.
@Serializable
data class AccountData(
    val name: String,
    val balance: String,
    val payto_uri: String,
    val debit_threshold: String,
    val contact_data: ChallengeContactData? = null,
    val cashout_payto_uri: String? = null,
    val has_debit: Boolean
)

// Type of POST /transactions
@Serializable
data class BankAccountTransactionCreate(
    val payto_uri: String,
    val amount: String
)

/* History element, either from GET /transactions/T_ID
  or from GET /transactions */
@Serializable
data class BankAccountTransactionInfo(
    val creditor_payto_uri: String,
    val debtor_payto_uri: String,
    val amount: String,
    val direction: TransactionDirection,
    val subject: String,
    val row_id: Long, // is T_ID
    val date: Long
)

// Response type for histories, namely GET /transactions
@Serializable
data class BankAccountTransactionsResponse(
    val transactions: MutableList<BankAccountTransactionInfo>
)

// Taler withdrawal request.
@Serializable
data class BankAccountCreateWithdrawalRequest(
    val amount: String
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
    val amount: String,
    val aborted: Boolean,
    val confirmation_done: Boolean,
    val selection_done: Boolean,
    val selected_reserve_pub: String? = null,
    val selected_exchange_account: String? = null
)

typealias ResourceName = String


// Checks if the input Customer has the rights over ResourceName
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
 * This type communicates the result of a database operation
 * to confirm one withdrawal operation.
 */
enum class WithdrawalConfirmationResult {
    SUCCESS,
    OP_NOT_FOUND,
    EXCHANGE_NOT_FOUND,
    BALANCE_INSUFFICIENT
}

// GET /config response from the Taler Integration API.
@Serializable
data class TalerIntegrationConfigResponse(
    val name: String = "taler-bank-integration",
    val version: String = "0:0:0:",
    val currency: String
)

// Withdrawal status as spec'd in the Taler Integration API.
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
    val amount: String,

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

// Selection request on a Taler withdrawal.
@Serializable
data class BankWithdrawalOperationPostRequest(
    val reserve_pub: String,
    val selected_exchange: String? = null // Use suggested exchange if that's missing.
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
    val amount: String,
    val reserve_pub: String,
    val debit_account: String
)

// Response to /admin/add-incoming
@Serializable
data class AddIncomingResponse(
    val timestamp: Long,
    val row_id: Long
)

@Serializable
data class TWGConfigResponse(
    val name: String = "taler-wire-gateway",
    val version: String = "0:0:0:",
    val currency: String
)