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

import java.nio.file.Path
import tech.libeufin.common.*
import tech.libeufin.nexus.ebics.Dialect

val NEXUS_CONFIG_SOURCE = ConfigSource("libeufin", "libeufin-nexus", "libeufin-nexus")


class NexusFetchConfig(config: TalerConfig) {
    val frequency = config.requireDuration("nexus-fetch", "frequency")
    val ignoreBefore = config.lookupDate("nexus-fetch", "ignore_transactions_before")
}

/** Configuration for libeufin-nexus */
class NexusConfig(val config: TalerConfig) {
    private fun requireString(option: String): String = config.requireString("nexus-ebics", option)
    private fun requirePath(option: String): Path = config.requirePath("nexus-ebics", option)

    /** The bank's currency */
    val currency = requireString("currency")
    /** The bank base URL */
    val hostBaseUrl = requireString("host_base_url")
    /** The bank EBICS host ID */
    val ebicsHostId = requireString("host_id")
    /** EBICS user ID */
    val ebicsUserId = requireString("user_id")
    /** EBICS partner ID */
    val ebicsPartnerId = requireString("partner_id")
    /** Bank account metadata */
    val account = IbanAccountMetadata(
        iban = requireString("iban"),
        bic = requireString("bic"),
        name = requireString("name")
    )
    /** Path where we store the bank public keys */
    val bankPublicKeysPath = requirePath("bank_public_keys_file")
    /** Path where we store our private keys */
    val clientPrivateKeysPath = requirePath("client_private_keys_file")

    val fetch = NexusFetchConfig(config)
    val dialect = when (val type = requireString("bank_dialect")) {
        "postfinance" -> Dialect.postfinance
        "gls" -> Dialect.gls
        else -> throw TalerConfigError.invalid("dialct", "libeufin-nexus", "bank_dialect", "expected 'postfinance' or 'gls' got '$type'")
    }
}

fun NexusConfig.checkCurrency(amount: TalerAmount) {
    if (amount.currency != currency) throw badRequest(
        "Wrong currency: expected regional $currency got ${amount.currency}",
        TalerErrorCode.GENERIC_CURRENCY_MISMATCH
    )
}