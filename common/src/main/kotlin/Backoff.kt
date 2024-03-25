/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023-2024 Taler Systems S.A.

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

import kotlin.random.Random

/** Infinite exponential backoff with decorrelated jitter */
class ExpoBackoffDecorr(
    private val base: Long = 100,  // 0.1 second
    private val max: Long = 60000, // 60 seconds
    private val factor: Double = 2.0,
) {
    private var sleep: Long = base

    fun next() : Long {
        sleep = Random.nextDouble(base.toDouble(), sleep.toDouble() * factor)
                        .toLong().coerceAtMost(max)
        return sleep
    }

    fun reset() {
        sleep = base
    }
}