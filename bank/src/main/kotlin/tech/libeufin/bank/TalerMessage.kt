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
    aborted,
    confirmed
}

enum class WithdrawalStatus {
    pending,
    aborted,
    selected,
    confirmed
}

enum class RoundingMode {
    zero,
    up,
    nearest
}

enum class Timeframe {
    hour,
    day,
    month,
    year
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
) {
    init {
        if (email != null && !EMAIL_PATTERN.matches(email))
            throw badRequest("email contact data '$email' is malformed")

        if (phone != null && !PHONE_PATTERN.matches(phone))
            throw badRequest("phone contact data '$phone' is malformed")
    }
    companion object {
        private val EMAIL_PATTERN = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,4}")
        private val PHONE_PATTERN = Regex("^\\+?[0-9]+$")
    }
}

// Type expected at POST /accounts
@Serializable
data class RegisterAccountRequest(
    val username: String,
    val password: String,
    val name: String,
    val is_public: Boolean = false,
    val is_taler_exchange: Boolean = false,
    val challenge_contact_data: ChallengeContactData? = null,
    // Fiat bank account where to send cashout amounts.
    val cashout_payto_uri: IbanPayTo? = null,
    // Bank account internal to Libeufin-Bank.
    val internal_payto_uri: IbanPayTo? = null
)

/**
 * Request of PATCH /accounts/{USERNAME}
 */
@Serializable
data class AccountReconfiguration(
    val challenge_contact_data: ChallengeContactData?,
    val cashout_payto_uri: IbanPayTo?,
    val name: String?,
    val is_taler_exchange: Boolean?,
    val debit_threshold: TalerAmount?
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
    abstract val talerInCount: Long
    abstract val talerInVolume: TalerAmount
    abstract val talerOutCount: Long
    abstract val talerOutVolume: TalerAmount
}

@Serializable
@SerialName("no-conversions")
data class MonitorNoConversion(
    override val talerInCount: Long,
    override val talerInVolume: TalerAmount,
    override val talerOutCount: Long,
    override val talerOutVolume: TalerAmount
) : MonitorResponse()

@Serializable
@SerialName("with-conversions")
data class MonitorWithConversion(
    val cashinCount: Long,
    val cashinRegionalVolume: TalerAmount,
    val cashinFiatVolume: TalerAmount,
    val cashoutCount: Long,
    val cashoutRegionalVolume: TalerAmount,
    val cashoutFiatVolume: TalerAmount,
    override val talerInCount: Long,
    override val talerInVolume: TalerAmount,
    override val talerOutCount: Long,
    override val talerOutVolume: TalerAmount
) : MonitorResponse()

/**
 * Convenience type to get bank account information
 * from/to the database.
 */
data class BankInfo(
    val internalPaytoUri: String,
    val bankAccountId: Long,
    val isTalerExchange: Boolean,
)

// Allowed values for cashout TAN channels.
enum class TanChannel {
    sms,
    email
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

@Serializable
data class Config(
    val currency: String,
    val currency_specification: CurrencySpecification,
    val allow_conversion: Boolean,
    val allow_registrations: Boolean,
    val allow_deletions: Boolean
) {
    val name: String = "libeufin-bank"
    val version: String = "0:0:0"
    // TODO as a config field
    val bank_name: String = "Stater Bank"
    // TODO spa file config ?
    val show_demo_nav: Boolean = false
}

@Serializable
data class ConversionConfig(
    val regional_currency: String,
    val regional_currency_specification: CurrencySpecification,
    val fiat_currency: String,
    val fiat_currency_specification: CurrencySpecification,
    val conversion_rate: ConversionRate
) {
    val name: String = "taler-conversion-info"
    val version: String = "0:0:0"
}

@Serializable
data class TalerIntegrationConfigResponse(
    val currency: String,
    val currency_specification: CurrencySpecification
) {
    val name: String = "taler-bank-integration";
    val version: String = "1:0:1";
}

enum class CreditDebitInfo {
    credit, debit
}

@Serializable
data class Balance(
    val amount: TalerAmount,
    val credit_debit_indicator: CreditDebitInfo,
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
    val payto_uri: String,
    val debit_threshold: TalerAmount,
    val contact_data: ChallengeContactData? = null,
    val cashout_payto_uri: String? = null,
)

@Serializable
data class TransactionCreateRequest(
    val payto_uri: IbanPayTo,
    val amount: TalerAmount?
)

@Serializable
data class TransactionCreateResponse(
    val row_id: Long
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

@Serializable
data class WithdrawalPublicInfo (
    val username: String
)

// Taler withdrawal details response // TODO remove
@Serializable
data class BankAccountGetWithdrawalResponse(
    val amount: TalerAmount,
    val aborted: Boolean,
    val confirmation_done: Boolean,
    val selection_done: Boolean,
    val selected_reserve_pub: EddsaPublicKey? = null,
    val selected_exchange_account: String? = null,
    val username: String
)

@Serializable
data class CurrencySpecification(
    val name: String,
    val num_fractional_input_digits: Int,
    val num_fractional_normal_digits: Int,
    val num_fractional_trailing_zero_digits: Int,
    val alt_unit_names: Map<String, String>
)


@Serializable
data class BankWithdrawalOperationStatus(
    val status: WithdrawalStatus,
    val amount: TalerAmount,
    val sender_wire: String? = null,
    val suggested_exchange: String? = null,
    val confirm_transfer_url: String? = null,
    val selected_reserve_pub: EddsaPublicKey? = null,
    val selected_exchange_account: String? = null,
    val wire_types: MutableList<String> = mutableListOf("iban"),
    // TODO remove
    val aborted: Boolean,
    val selection_done: Boolean,
    val transfer_done: Boolean,
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
    val status: WithdrawalStatus,
    val confirm_transfer_url: String? = null,
    // TODO remove
    val transfer_done: Boolean,
)

@Serializable
data class CashoutRequest(
    val request_uid: ShortHashCode,
    val subject: String?,
    val amount_debit: TalerAmount,
    val amount_credit: TalerAmount,
    val tan_channel: TanChannel?
)

@Serializable
data class CashoutPending(
    val cashout_id: Long,
)

@Serializable
data class Cashouts(
    val cashouts: List<CashoutInfo>,
)

@Serializable
data class CashoutInfo(
    val cashout_id: Long,
    val status: CashoutStatus,
)


@Serializable
data class GlobalCashouts(
    val cashouts: List<GlobalCashoutInfo>,
)

@Serializable
data class GlobalCashoutInfo(
    val cashout_id: Long,
    val username: String,
    val status: CashoutStatus,
)

@Serializable
data class CashoutStatusResponse(
    val status: CashoutStatus,
    val amount_debit: TalerAmount,
    val amount_credit: TalerAmount,
    val subject: String,
    val creation_time: TalerProtocolTimestamp,
    val confirmation_time: TalerProtocolTimestamp? = null,
    val tan_channel: TanChannel? = null,
    val tan_info: String? = null
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
    val credit_account: String, // Payto of the receiver.
    val wtid: ShortHashCode,
    val exchange_base_url: String,
)

@Serializable
data class MerchantIncomingHistory(
    val incoming_transactions : List<MerchantIncomingBankTransaction>,
    val credit_account: String
)

@Serializable
data class MerchantIncomingBankTransaction(
    val row_id: Long,
    val date: TalerProtocolTimestamp,
    val amount: TalerAmount,
    val debit_account: String,
    val exchange_url: String,
    val wtid: ShortHashCode
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
    val new_password: String,
    val old_password: String? = null
)