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
import tech.libeufin.bank.db.TransactionDAO.BankTransactionResult
import tech.libeufin.bank.db.WithdrawalDAO.WithdrawalCreationResult
import tech.libeufin.bank.db.*
import tech.libeufin.bank.*
import tech.libeufin.common.*
import java.time.Instant
import java.util.*
import org.postgresql.jdbc.PgConnection
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.*

class MigrationTest {
    @Test
    fun test() = runBlocking {
        val conn = pgDataSource("postgres:///libeufincheck").pgConnection()

        // Drop current schemas
        conn.execSQLUpdate(Path("../database-versioning/libeufin-bank-drop.sql").readText())
        conn.execSQLUpdate(Path("../database-versioning/libeufin-nexus-drop.sql").readText())

        // libeufin-bank-0001
        conn.execSQLUpdate(Path("../database-versioning/libeufin-bank-0001.sql").readText())
        conn.execSQLUpdate("""
            INSERT INTO customers (login, password_hash) VALUES
                ('account_0', 'fack_hash'), ('account_1', 'fack_hash');
            INSERT INTO bank_accounts (internal_payto_uri, owning_customer_id) VALUES
                ('payto_0', 1), ('payto_1', 2);
            INSERT INTO bank_account_transactions(creditor_payto_uri, creditor_name, debtor_payto_uri, debtor_name, subject, amount, transaction_date, direction, bank_account_id) VALUES
                ('payto_0', 'account_0', 'payto_1', 'account_1', 'subject', (0, 0)::taler_amount, 42, 'credit'::direction_enum, 1);
            INSERT INTO challenges(code, creation_date, expiration_date, retry_counter) VALUES
                ('secret_code', 42, 42, 42),
                ('secret_code', 42, 42, 42);
            INSERT INTO cashout_operations(request_uid, amount_debit, amount_credit, subject, creation_time, bank_account, challenge, local_transaction) VALUES
                ('\x6ca1ab1a76a484d7424064c51c49c1947405f42f7d185d052dbf6718d845ec6b'::bytea, (0, 0)::taler_amount, (0, 0)::taler_amount, 'subject', 42, 1, 1, 1),
                ('\xa605637a4852684e4957e6177f41311eacf8661a6a74b90178c487fe347b9918'::bytea, (0, 0)::taler_amount, (0, 0)::taler_amount, 'subject', 42, 1, 2, NULL);
            INSERT INTO taler_withdrawal_operations(withdrawal_uuid, amount, reserve_pub, wallet_bank_account) VALUES
                (gen_random_uuid(), (0, 0)::taler_amount, '\x6ca1ab1a76a484d7424064c51c49c1947405f42f7d185d052dbf6718d845ec6b'::bytea, 1),
                (gen_random_uuid(), (0, 0)::taler_amount, '\xa605637a4852684e4957e6177f41311eacf8661a6a74b90178c487fe347b9918'::bytea, 2);
        """)

        // libeufin-bank-0002
        conn.execSQLUpdate(Path("../database-versioning/libeufin-bank-0002.sql").readText())

        // libeufin-bank-0003
        conn.execSQLUpdate(Path("../database-versioning/libeufin-bank-0003.sql").readText())

        // libeufin-nexus-0001
        conn.execSQLUpdate(Path("../database-versioning/libeufin-nexus-0001.sql").readText())
        conn.execSQLUpdate("""
            INSERT INTO outgoing_transactions(amount, execution_time, message_id) VALUES
                ((0, 0)::taler_amount, 42, 'id');
            INSERT INTO initiated_outgoing_transactions(amount, wire_transfer_subject, initiation_time, credit_payto_uri, outgoing_transaction_id, request_uid) VALUES
                ((0, 0)::taler_amount, 'subject', 42, 'payto_0', 1, 'request_uid');
        """)

        // libeufin-nexus-0002
        conn.execSQLUpdate(Path("../database-versioning/libeufin-nexus-0002.sql").readText())
    }
}