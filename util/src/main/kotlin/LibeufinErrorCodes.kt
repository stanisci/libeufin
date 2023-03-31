/*
     This file is part of GNU Taler
     Copyright (C) 2012-2020 Taler Systems SA

     GNU Taler is free software: you can redistribute it and/or modify it
     under the terms of the GNU Lesser General Public License as published
     by the Free Software Foundation, either version 3 of the License,
     or (at your option) any later version.

     GNU Taler is distributed in the hope that it will be useful, but
     WITHOUT ANY WARRANTY; without even the implied warranty of
     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
     Lesser General Public License for more details.

     You should have received a copy of the GNU Lesser General Public License
     along with this program.  If not, see <http://www.gnu.org/licenses/>.

     SPDX-License-Identifier: LGPL3.0-or-later

     Note: the LGPL does not apply to all components of GNU Taler,
     but it does apply to this file.
 */

package tech.libeufin.util

enum class LibeufinErrorCode(val code: Int) {

    /**
     * The error case didn't have a dedicate code.
     */
    LIBEUFIN_EC_NONE(0),

    /**
     * A payment being processed is neither CRDT not DBIT.  This
     * type of error should be detected _before_ storing the data
     * into the database.
     */
    LIBEUFIN_EC_INVALID_PAYMENT_DIRECTION(1),

    /**
     * A bad piece of information made it to the database.  For
     * example, a transaction whose direction is neither CRDT nor DBIT
     * was found in the database.
     */
    LIBEUFIN_EC_INVALID_STATE(2),

    /**
     * A bank's invariant is not holding anymore.  For example, a customer's
     * balance doesn't match the history of their bank account.
     */
    LIBEUFIN_EC_INCONSISTENT_STATE(3),

    /**
     * Access was forbidden due to wrong credentials.
     */
    LIBEUFIN_EC_AUTHENTICATION_FAILED(4),

    /**
     * A parameter in the request was malformed.
     * Returned with an HTTP status code of #MHD_HTTP_BAD_REQUEST (400).
     * (A value of 0 indicates that the error is generated client-side).
     */
    LIBEUFIN_EC_GENERIC_PARAMETER_MALFORMED(5),

    /**
     * Two different resources are NOT having the same currency.
     */
    LIBEUFIN_EC_CURRENCY_INCONSISTENT(6),

    /**
     * A request is using a unsupported currency.  Usually returned
     * along 400 Bad Request
     */
    LIBEUFIN_EC_BAD_CURRENCY(7),

    LIBEUFIN_EC_TIMEOUT_EXPIRED(8)
}