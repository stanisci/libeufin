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
import kotlinx.serialization.SerialName

/**
 * Allowed lengths for fractional digits in amounts.
 */
enum class FracDigits {
    TWO, EIGHT
}

// Allowed values for bank transactions directions.
enum class TransactionDirection {
    credit,
    debit
}

enum class CashoutStatus {
    pending,
    confirmed
}

enum class RoundingMode {
    zero,
    up,
    nearest
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
    val cashout_payto_uri: IbanPayTo? = null,
    // Bank account internal to Libeufin-Bank.
    val internal_payto_uri: IbanPayTo? = null
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

@Serializable
sealed class MonitorResponse {
    abstract val talerPayoutCount: Long
    abstract val talerPayoutInternalVolume: TalerAmount
}

@Serializable
@SerialName("just-payouts")
data class MonitorJustPayouts(
    override val talerPayoutCount: Long,
    override val talerPayoutInternalVolume: TalerAmount
) : MonitorResponse()

@Serializable
@SerialName("with-cashout")
data class MonitorWithCashout(
    val cashinCount: Long?,
    val cashinExternalVolume: TalerAmount,
    val cashoutCount: Long,
    val cashoutExternalVolume: TalerAmount,
    override val talerPayoutCount: Long,
    override val talerPayoutInternalVolume: TalerAmount
) : MonitorResponse()

/**
 * Convenience type to get and set bank account information
 * from/to the database.
 */
data class BankAccount(
    val internalPaytoUri: IbanPayTo,
    // Database row ID of the customer that owns this bank account.
    val owningCustomerId: Long,
    val bankAccountId: Long,
    val isPublic: Boolean,
    val isTalerExchange: Boolean,
    val balance: TalerAmount,
    val hasDebt: Boolean,
    val maxDebt: TalerAmount
)

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
    val dbRowId: Long,
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
    val selectionDone: Boolean,
    val aborted: Boolean,
    val confirmationDone: Boolean,
    val reservePub: EddsaPublicKey?,
    val selectedExchangePayto: IbanPayTo?,
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
@Serializable
data class Config(
    val currency: CurrencySpecification,
    val have_cashout: Boolean,
    val fiat_currency: String?,
    val conversion_info: ConversionInfo?,
    val allow_registrations: Boolean,
    val allow_deletions: Boolean
) {
    val name: String = "libeufin-bank"
    val version: String = "0:0:0"
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
    val accounts: List<AccountMinimalData>
)

/**
 * GET /accounts/$USERNAME response.
 */
@Serializable
data class AccountData(
    val name: String,
    val balance: Balance,
    val payto_uri: IbanPayTo,
    val debit_threshold: TalerAmount,
    val contact_data: ChallengeContactData? = null,
    val cashout_payto_uri: IbanPayTo? = null,
)

/**
 * Response type of corebank API transaction initiation.
 */
@Serializable
data class BankAccountTransactionCreate(
    val payto_uri: IbanPayTo,
    val amount: TalerAmount?
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
    val selected_reserve_pub: EddsaPublicKey? = null,
    val selected_exchange_account: IbanPayTo? = null
)

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
    val reserve_pub: EddsaPublicKey,
    val selected_exchange: IbanPayTo,
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

@Serializable
data class CashoutRequest(
    val subject: String?,
    val amount_debit: TalerAmount,
    val amount_credit: TalerAmount,
    val tan_channel: TanChannel?
)

@Serializable
data class CashoutPending(
    val cashout_id: String,
)

@Serializable
data class Cashouts(
    val cashouts: List<CashoutInfo>,
)

@Serializable
data class CashoutInfo(
    val cashout_id: String,
    val status: CashoutStatus,
)


@Serializable
data class GlobalCashouts(
    val cashouts: List<GlobalCashoutInfo>,
)

@Serializable
data class GlobalCashoutInfo(
    val cashout_id: String,
    val username: String,
    val status: CashoutStatus,
)

@Serializable
data class CashoutStatusResponse(
    val status: CashoutStatus,
    val amount_debit: TalerAmount,
    val amount_credit: TalerAmount,
    val subject: String,
    val credit_payto_uri: IbanPayTo,
    val creation_time: TalerProtocolTimestamp,
    val confirmation_time: TalerProtocolTimestamp?,
)

@Serializable
data class CashoutConfirm(
    val tan: String
)

@Serializable
data class ConversionResponse(
    val amount_debit: TalerAmount,
    val amount_credit: TalerAmount,
)

/**
 * Request to an /admin/add-incoming request from
 * the Taler Wire Gateway API.
 */
@Serializable
data class AddIncomingRequest(
    val amount: TalerAmount,
    val reserve_pub: EddsaPublicKey,
    val debit_account: IbanPayTo
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
    val credit_account: IbanPayTo, // Payto of the receiver.
    val wtid: ShortHashCode,
    val exchange_base_url: ExchangeUrl,
)

/**
 * TWG's request to pay a merchant.
 */
@Serializable
data class TransferRequest(
    val request_uid: HashCode,
    val amount: TalerAmount,
    val exchange_base_url: ExchangeUrl,
    val wtid: ShortHashCode,
    val credit_account: IbanPayTo
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
    val public_accounts: List<PublicAccount>
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
    val cashout_address: IbanPayTo?,
    val name: String?,
    val is_taler_exchange: Boolean?,
    val debit_threshold: TalerAmount?
)