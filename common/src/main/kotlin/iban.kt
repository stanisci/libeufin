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

package tech.libeufin.common

// Taken from the ISO20022 XSD schema
private val bicRegex = Regex("^[A-Z]{6}[A-Z2-9][A-NP-Z0-9]([A-Z0-9]{3})?$")

fun validateBic(bic: String): Boolean {
    return bicRegex.matches(bic)
}

// Taken from the ISO20022 XSD schema
private val ibanRegex = Regex("^[A-Z]{2}[0-9]{2}[a-zA-Z0-9]{1,30}$")

fun validateIban(iban: String): Boolean {
    return ibanRegex.matches(iban)
}