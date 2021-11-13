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

package tech.libeufin.sandbox

import tech.libeufin.util.PaymentInfo

data class WithdrawalRequest(
    /**
     * Note: the currency is redundant, because at each point during
     * the execution the Demobank should have a handle of the currency.
     */
    val amount: String // $CURRENCY:X.Y
)

data class Demobank(
    val currency: String,
    val name: String,
    val userDebtLimit: Int,
    val bankDebtLimit: Int,
    val allowRegistrations: Boolean
)
/**
 * Used to show the list of Ebics hosts that exist
 * in the system.
 */
data class EbicsHostsResponse(
    val ebicsHosts: List<String>
)

data class EbicsHostCreateRequest(
    val hostID: String,
    val ebicsVersion: String
)

/**
 * List type that show all the payments existing in the system.
 */
data class AccountTransactions(
    val payments: MutableList<PaymentInfo> = mutableListOf()
)

/**
 * Used to create AND show one Ebics subscriber in the system.
 */
data class EbicsSubscriberInfo(
    val hostID: String,
    val partnerID: String,
    val userID: String,
    val systemID: String? = null,
    val demobankAccountLabel: String
)

data class AdminGetSubscribers(
    var subscribers: MutableList<EbicsSubscriberInfo> = mutableListOf()
)

/**
 * Some obsolete code creates a bank account and after the
 * Ebics subscriber.  This doesn't allow to have bank accounts
 * without a subscriber associated to it.  Demobank should allow
 * this instead, because only one user - the exchange - will
 * ever need a Ebics subscription at the Sandbox.
 *
 * The code is obsoleted by a new endpoint that's defined within
 * the /demobanks/${demobankId} trunk.  This one allows to first create
 * a bank account, and only optionally later give a Ebics account to
 * it.
 */
data class EbicsSubscriberObsoleteApi(
    val hostID: String,
    val partnerID: String,
    val userID: String,
    val systemID: String? = null
)
data class EbicsBankAccountRequest(
    val subscriber: EbicsSubscriberObsoleteApi,
    val iban: String,
    val bic: String,
    val name: String,
    val label: String,
)

data class CustomerRegistration(
    val username: String,
    val password: String
)

// Could be used as a general bank account info container.
data class PublicAccountInfo(
    val balance: String,
    val iban: String
    // more ..?
)

data class CamtParams(
    // name/label of the bank account to query.
    val bankaccount: String,
    val type: Int,
    // need range parameter
)

data class TalerWithdrawalStatus(
    val selection_done: Boolean,
    val transfer_done: Boolean,
    val amount: String,
    val wire_types: List<String> = listOf("iban"),
    val suggested_exchange: String? = null,
    val sender_wire: String? = null,
    val aborted: Boolean
)

data class TalerWithdrawalSelection(
    val reserve_pub: String,
    val selected_exchange: String?
)
