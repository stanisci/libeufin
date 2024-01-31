/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

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

import tech.libeufin.common.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.serialization.*
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

enum class Operation {
    account_reconfig,
    account_delete,
    account_auth_reconfig,
    bank_transaction,
    cashout,
    withdrawal
}

@Serializable(with = Option.Serializer::class)
sealed class Option<out T> {
    data object None : Option<Nothing>()
    data class Some<T>(val value: T) : Option<T>()

    fun get(): T? {
        return when (this) {
            Option.None -> null
            is Option.Some -> this.value
        }
    }

    inline fun some(lambda: (T) -> Unit) {
        if (this is Option.Some) {
            lambda(value)
        }
    }

    fun isSome(): Boolean = this is Option.Some

    @OptIn(ExperimentalSerializationApi::class)
    internal class Serializer<T> (
        private val valueSerializer: KSerializer<T>
    ) : KSerializer<Option<T>> {
        override val descriptor: SerialDescriptor = valueSerializer.descriptor

        override fun serialize(encoder: Encoder, value: Option<T>) {
            when (value) {
                Option.None -> encoder.encodeNull()
                is Option.Some -> valueSerializer.serialize(encoder, value.value)
            }
        }

        override fun deserialize(decoder: Decoder): Option<T> {
            return Option.Some(valueSerializer.deserialize(decoder))
        }
    }
}

@Serializable
data class TanChallenge(
    val challenge_id: Long
)

@Serializable
data class TanTransmission(
    val tan_info: String,
    val tan_channel: TanChannel
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
    val expiration: TalerProtocolTimestamp
)


/* Contains contact data to send TAN challges to the
* users, to let them complete cashout operations. */
@Serializable
data class ChallengeContactData(
    val email: Option<String?> = Option.None,
    val phone: Option<String?> = Option.None
) {
    init {
        if (email.get()?.let { !EMAIL_PATTERN.matches(it) } ?: false)
            throw badRequest("email contact data '$email' is malformed")

        if (phone.get()?.let { !PHONE_PATTERN.matches(it) } ?: false)
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
    val contact_data: ChallengeContactData? = null,
    val cashout_payto_uri: IbanPayto? = null,
    val payto_uri: IbanPayto? = null,
    val debit_threshold: TalerAmount? = null,
    val tan_channel: TanChannel? = null,
)

@Serializable
data class RegisterAccountResponse(
    val internal_payto_uri: String
)

/**
 * Request of PATCH /accounts/{USERNAME}
 */
@Serializable
data class AccountReconfiguration(
    val contact_data: ChallengeContactData? = null,
    val cashout_payto_uri: Option<IbanPayto?> = Option.None,
    val name: String? = null,
    val is_public: Boolean? = null,
    val debit_threshold: TalerAmount? = null,
    val tan_channel: Option<TanChannel?> = Option.None,
    val is_taler_exchange: Boolean? = null,
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
sealed interface MonitorResponse {
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
) : MonitorResponse

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
) : MonitorResponse

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

data class BearerToken(
    val scope: TokenScope,
    val isRefreshable: Boolean,
    val creationTime: Instant,
    val expirationTime: Instant,
    val login: String
)

@Serializable
data class Config(
    val currency: String,
    val currency_specification: CurrencySpecification,
    val allow_conversion: Boolean,
    val allow_registrations: Boolean,
    val allow_deletions: Boolean,
    val allow_edit_name: Boolean,
    val allow_edit_cashout_payto_uri: Boolean,
    val default_debit_threshold: TalerAmount,
    val supported_tan_channels: Set<TanChannel>
) {
    val name: String = "libeufin-bank"
    val version: String = COREBANK_API_VERSION
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
    val version: String = CONVERSION_API_VERSION
}

@Serializable
data class TalerIntegrationConfigResponse(
    val currency: String,
    val currency_specification: CurrencySpecification
) {
    val name: String = "taler-bank-integration"
    val version: String = INTEGRATION_API_VERSION
}

@Serializable
data class WireGatewayConfig(
    val currency: String
) {
    val name: String = "taler-wire-gateway"
    val version: String = WIRE_GATEWAY_API_VERSION
}

@Serializable
data class RevenueConfig(
    val currency: String
) {
    val name: String = "taler-revenue"
    val version: String = REVENUE_API_VERSION
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
    val payto_uri: String,
    val balance: Balance,
    val debit_threshold: TalerAmount,
    val is_public: Boolean,
    val is_taler_exchange: Boolean
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
    val tan_channel: TanChannel? = null,
    val is_public: Boolean,
    val is_taler_exchange: Boolean
)

@Serializable
data class TransactionCreateRequest(
    val payto_uri: IbanPayto,
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
    val status: WithdrawalStatus,
    val amount: TalerAmount,
    val username: String,
    val selected_reserve_pub: EddsaPublicKey? = null,
    val selected_exchange_account: String? = null,
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
    val selected_exchange: IbanPayto,
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
    val amount_credit: TalerAmount
)

@Serializable
data class CashoutResponse(
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
data class ChallengeSolve(
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
    val debit_account: IbanPayto
)

/**
 * Response to /admin/add-incoming
 */
@Serializable
data class AddIncomingResponse(
    val timestamp: TalerProtocolTimestamp,
    val row_id: Long
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
data class RevenueIncomingHistory(
    val incoming_transactions : List<RevenueIncomingBankTransaction>,
    val credit_account: String
)

@Serializable
data class RevenueIncomingBankTransaction(
    val row_id: Long,
    val date: TalerProtocolTimestamp,
    val amount: TalerAmount,
    val debit_account: String,
    val subject: String
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
    val credit_account: IbanPayto
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
    val username: String,
    val payto_uri: String,
    val balance: Balance,
    val is_taler_exchange: Boolean,
)

/**
 * Request of PATCH /accounts/{USERNAME}/auth
 */
@Serializable
data class AccountPasswordChange(
    val new_password: String,
    val old_password: String? = null
)