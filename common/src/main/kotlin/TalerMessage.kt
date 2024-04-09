/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.common

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Response GET /taler-wire-gateway/config */
@Serializable
data class WireGatewayConfig(
    val currency: String
) {
    val name: String = "taler-wire-gateway"
    val version: String = WIRE_GATEWAY_API_VERSION
}

/** Request POST /taler-wire-gateway/transfer */
@Serializable
data class TransferRequest(
    val request_uid: HashCode,
    val amount: TalerAmount,
    val exchange_base_url: ExchangeUrl,
    val wtid: ShortHashCode,
    val credit_account: Payto
)

/** Response POST /taler-wire-gateway/transfer */
@Serializable
data class TransferResponse(
    val timestamp: TalerProtocolTimestamp,
    val row_id: Long
)

/** Request POST /taler-wire-gateway/admin/add-incoming */
@Serializable
data class AddIncomingRequest(
    val amount: TalerAmount,
    val reserve_pub: EddsaPublicKey,
    val debit_account: Payto
)

/** Response POST /taler-wire-gateway/admin/add-incoming */
@Serializable
data class AddIncomingResponse(
    val timestamp: TalerProtocolTimestamp,
    val row_id: Long
)

/** Request GET /taler-wire-gateway/history/incoming */
@Serializable
data class IncomingHistory(
    val incoming_transactions: List<IncomingReserveTransaction>,
    val credit_account: String
)

@Serializable
data class IncomingReserveTransaction(
    val type: String = "RESERVE",
    val row_id: Long, // DB row ID of the payment.
    val date: TalerProtocolTimestamp,
    val amount: TalerAmount,
    val debit_account: String,
    val reserve_pub: EddsaPublicKey
)

/** Request GET /taler-wire-gateway/history/outgoing */
@Serializable
data class OutgoingHistory(
    val outgoing_transactions: List<OutgoingTransaction>,
    val debit_account: String
)

@Serializable
data class OutgoingTransaction(
    val row_id: Long, // DB row ID of the payment.
    val date: TalerProtocolTimestamp,
    val amount: TalerAmount,
    val credit_account: String,
    val wtid: ShortHashCode,
    val exchange_base_url: String,
)
