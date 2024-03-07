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

package tech.libeufin.nexus

enum class HacAction(val description: String) {
	FILE_UPLOAD("File submitted to the bank"),
	FILE_DOWNLOAD("File downloaded from the bank"),
	ES_UPLOAD("Electronic signature submitted to the bank"),
	ES_DOWNLOAD("Electronic signature downloaded from the bank"),
	ES_VERIFICATION("Signature verification"),
	VEU_FORWARDING("Forwarding to EDS"),
	VEU_VERIFICATION("EDS signature verification"),
	VEU_VERIFICATION_END("VEU_VERIFICATION_END"),
	VEU_CANCEL_ORDER("Cancellation of EDS order"),
	ADDITIONAL("Additional information"),
	ORDER_HAC_FINAL_POS("HAC end of order (positive)"),
	ORDER_HAC_FINAL_NEG("ORDER_HAC_FINAL_NEG")
}