/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
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

package tech.libeufin.util

enum class XLibeufinBankDirection(val direction: String) {
    DEBIT("debit"),
    CREDIT("credit");
    fun exportAsCamtDirection(): String =
        when(this) {
            CREDIT -> "CRDT"
            DEBIT -> "DBIT"
        }
}

data class XLibeufinBankPaytoReq(
    /**
     * This Payto address MUST contain the wire transfer
     * subject among its query parameters -- 'message' parameter.
     */
    val paytoUri: String,
    // $currency:X.Y format
    val amount: String?,
    /**
     * This value MAY be specified by the payment submitter to
     * help reconcile the payment when they later download new
     * transactions.  The name is only borrowed from CaMt terminology.
     */
    val pmtInfId: String?
)
data class XLibeufinBankTransaction(
    val creditorIban: String,
    val creditorBic: String?,
    val creditorName: String,
    val debtorIban: String,
    val debtorBic: String?,
    val debtorName: String,
    val amount: String,
    val currency: String,
    val subject: String,
    // Milliseconds since the Epoch.
    val date: String,
    val uid: String,
    val direction: XLibeufinBankDirection,
    /**
     * The following two values are rather CAMT/PAIN
     * specific, therefore do not need to be returned
     * along every API call using this object.
     */
    val pmtInfId: String? = null,
    val msgId: String? = null,
    val endToEndId: String? = null
)
data class IncomingPaymentInfo(
    val debtorIban: String,
    val debtorBic: String?,
    val debtorName: String,
    /**
     * A stringified number, no currency required.  This
     * one will be extracted from the demobank configuration.
     */
    val amount: String,
    val subject: String
)

data class TWGAdminAddIncoming(
    val amount: String,
    val reserve_pub: String,
    val debit_account: String
)

data class PaymentInfo(
    val accountLabel: String,
    val creditorIban: String,
    val creditorBic: String?,
    val creditorName: String,
    val debtorIban: String,
    val debtorBic: String?,
    val debtorName: String,
    val amount: String,
    val currency: String,
    val subject: String,
    val date: String? = null,
    val creditDebitIndicator: String,
    val accountServicerReference: String,
    val paymentInformationId: String?,
)