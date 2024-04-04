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

import tech.libeufin.common.ConfigSource
import java.time.Duration

// Config
val BANK_CONFIG_SOURCE = ConfigSource("libeufin", "libeufin-bank", "libeufin-bank")

// TAN
const val TAN_RETRY_COUNTER: Int = 3
val TAN_VALIDITY_PERIOD: Duration = Duration.ofHours(1)
val TAN_RETRANSMISSION_PERIOD: Duration = Duration.ofMinutes(1)

// Token
val TOKEN_DEFAULT_DURATION: Duration = Duration.ofDays(1L)

// Account
val RESERVED_ACCOUNTS = setOf("admin", "bank") 
const val IBAN_ALLOCATION_RETRY_COUNTER: Int = 5

// Security
const val MAX_BODY_LENGTH: Long = 4 * 1024 // 4kB

// API version  
const val COREBANK_API_VERSION: String = "4:6:0"
const val CONVERSION_API_VERSION: String = "0:0:0"
const val INTEGRATION_API_VERSION: String = "2:0:2"
const val WIRE_GATEWAY_API_VERSION: String = "0:2:0"
const val REVENUE_API_VERSION: String = "0:0:0"