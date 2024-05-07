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

package tech.libeufin.nexus.api

import tech.libeufin.nexus.*
import tech.libeufin.common.*
import tech.libeufin.common.api.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*

/** Apply api configuration for a route: conditional access and authentication */
fun Route.authApi(cfg: ApiConfig?, callback: Route.() -> Unit): Route =
    intercept(callback) {
        if (cfg == null) {
            throw apiError(HttpStatusCode.NotImplemented, "API not implemented", TalerErrorCode.END)
        }
        val header = context.request.headers["Authorization"]
        // Basic auth challenge
        when (cfg.authMethod) {
            AuthMethod.None -> {}
            is AuthMethod.Bearer -> {
                if (header == null) {
                    context.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                    throw unauthorized(
                        "Authorization header not found",
                        TalerErrorCode.GENERIC_PARAMETER_MISSING
                    )
                }
                val (scheme, content) = header.splitOnce(" ") ?: throw badRequest(
                    "Authorization is invalid",
                    TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED
                )
                when (scheme) {
                    "Bearer" -> {
                        // TODO choose between one of those
                        if (content != cfg.authMethod.token) {
                            throw unauthorized("Unknown token")
                        }
                    }
                    else -> throw unauthorized("Authorization method wrong or not supported")
                }
            }
        } 
    }