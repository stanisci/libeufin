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

import org.junit.Test
import tech.libeufin.common.*
import tech.libeufin.nexus.*
import tech.libeufin.nexus.ebics.*
import tech.libeufin.nexus.TxNotification.*
import java.nio.file.Files
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals

private fun instant(date: String): Instant =
    LocalDate.parse(date, DateTimeFormatter.ISO_DATE).atStartOfDay().toInstant(ZoneOffset.UTC)

class Iso20022Test {
    @Test
    fun postfinance_camt054() {
        val content = Files.newInputStream(Path("sample/platform/postfinance_camt054.xml"))
        val txs = parseTx(content, "CHF", Dialect.postfinance)
        assertEquals(
            listOf(
                OutgoingPayment(
                    messageId = "ZS1PGNTSV0ZNDFAJBBWWB8015G",
                    amount = TalerAmount("CHF:3.00"),
                    wireTransferSubject = null,
                    executionTime = instant("2024-01-15"),
                    creditPaytoUri = null
                ),
                IncomingPayment(
                    bankId = "62e2b511-7313-4ccd-8d40-c9d8e612cd71",
                    amount = TalerAmount("CHF:10"),
                    wireTransferSubject = "G1XTY6HGWGMVRM7E6XQ4JHJK561ETFDFTJZ7JVGV543XZCB27YBG",
                    executionTime = instant("2023-12-19"),
                    debitPaytoUri = "payto://iban/CH7389144832588726658?receiver-name=Mr+Test"
                ),
                IncomingPayment(
                    bankId = "62e2b511-7313-4ccd-8d40-c9d8e612cd71",
                    amount = TalerAmount("CHF:2.53"),
                    wireTransferSubject = "G1XTY6HGWGMVRM7E6XQ4JHJK561ETFDFTJZ7JVGV543XZCB27YB",
                    executionTime = instant("2023-12-19"),
                    debitPaytoUri = "payto://iban/CH7389144832588726658?receiver-name=Mr+Test"
                )
            ),
            txs
        )
    }

    @Test
    fun postfinance_camt053() {
        val content = Files.newInputStream(Path("sample/platform/postfinance_camt053.xml"))
        val txs = parseTx(content, "CHF", Dialect.postfinance)
        assertEquals(
            listOf(
                Reversal(
                    msgId = "889d1a80-1267-49bd-8fcc-85701a",
                    reason = "InconsistenWithEndCustomer 'Identification of end customer is not consistent with associated account number, organisation ID or private ID.' - 'Keine Uebereinstimmung von Kontonummer und Kontoinhaber'",
                    executionTime = instant("2023-11-22")
                ),
                Reversal(
                    msgId = "4cc61cc7-6230-49c2-b5e2-b40bbb",
                    reason = "MissingCreditorNameOrAddress 'Specification of the creditor’s name and/or address needed for regulatory requirements is insufficient or missing.' - 'Postadresse des Kreditors fehlt oder ist unvollständig'",
                    executionTime = instant("2023-11-22")
                )
            ),
            txs
        )
    }

    @Test
    fun gls() {
        val content = Files.newInputStream(Path("sample/platform/gls.xml"))
        val txs = parseTx(content, "EUR", Dialect.gls)
        assertEquals(
            listOf(
                OutgoingPayment(
                    messageId = "G059N0SR5V0WZ0XSFY1H92QBZ0",
                    amount = TalerAmount("EUR:2"),
                    wireTransferSubject = "TestABC123",
                    executionTime = instant("2024-04-18"),
                    creditPaytoUri = "payto://iban/DE20500105172419259181"
                ),
                OutgoingPayment(
                    messageId = "YF5QBARGQ0MNY0VK59S477VDG4",
                    amount = TalerAmount("EUR:1.1"),
                    wireTransferSubject = "This should fail because dummy",
                    executionTime = instant("2024-04-18"),
                    creditPaytoUri = "payto://iban/DE20500105172419259181"
                ),
                IncomingPayment(
                    bankId = "BYLADEM1WOR-G2910276709458A2",
                    amount = TalerAmount("EUR:3"),
                    wireTransferSubject = "Taler FJDQ7W6G7NWX4H9M1MKA12090FRC9K7DA6N0FANDZZFXTR6QHX5G Test.,-",
                    executionTime = instant("2024-04-12"),
                    debitPaytoUri = "payto://iban/DE84500105177118117964"
                ),
                // TODO add reversal
            ),
            txs
        )
    }
}