/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

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
import org.postgresql.jdbc.PgConnection
import tech.libeufin.bank.*
import tech.libeufin.util.CryptoUtil
import java.sql.DriverManager
import java.time.Instant
import java.util.Random
import java.util.UUID
import kotlin.experimental.inv

// Foo pays Bar with custom subject.
fun genTx(
    subject: String = "test",
    creditorId: Long = 2,
    debtorId: Long = 1
): BankInternalTransaction =
    BankInternalTransaction(
        creditorAccountId = creditorId,
        debtorAccountId = debtorId,
        subject = subject,
        amount = TalerAmount( 10, 0, "KUDOS"),
        accountServicerReference = "acct-svcr-ref",
        endToEndId = "end-to-end-id",
        paymentInformationId = "pmtinfid",
        transactionDate = Instant.now()
    )

class DatabaseTest {
    private val customerFoo = Customer(
        login = "foo",
        passwordHash = "hash",
        name = "Foo",
        phone = "+00",
        email = "foo@b.ar",
        cashoutPayto = "payto://external-IBAN",
        cashoutCurrency = "KUDOS"
    )
    private val customerBar = Customer(
        login = "bar",
        passwordHash = "hash",
        name = "Bar",
        phone = "+00",
        email = "foo@b.ar",
        cashoutPayto = "payto://external-IBAN",
        cashoutCurrency = "KUDOS"
    )
    private val bankAccountFoo = BankAccount(
        internalPaytoUri = "payto://iban/FOO-IBAN-XYZ".lowercase(),
        lastNexusFetchRowId = 1L,
        owningCustomerId = 1L,
        hasDebt = false,
        maxDebt = TalerAmount(10, 1, "KUDOS")
    )
    private val bankAccountBar = BankAccount(
        internalPaytoUri = "payto://iban/BAR-IBAN-ABC".lowercase(),
        lastNexusFetchRowId = 1L,
        owningCustomerId = 2L,
        hasDebt = false,
        maxDebt = TalerAmount(10, 1, "KUDOS")
    )
    val fooPaysBar = genTx()

    // Testing the helper that creates the admin account.
    @Test
    fun createAdminTest() {
        val db = initDb()
        val ctx = getTestContext()
        // No admin accounts is expected.
        val noAdminCustomer = db.customerGetFromLogin("admin")
        assert(noAdminCustomer == null)
        // Now creating one.
        assert(maybeCreateAdminAccount(db, ctx))
        // Now expecting one.
        val yesAdminCustomer = db.customerGetFromLogin("admin")
        assert(yesAdminCustomer != null)
        // Expecting also its _bank_ account.
        assert(db.bankAccountGetFromOwnerId(yesAdminCustomer!!.expectRowId()) != null)
        // Checking idempotency.
        assert(maybeCreateAdminAccount(db, ctx))
        // Checking that the random password blocks a login.
        assert(!CryptoUtil.checkpw(
            "likely-wrong",
            yesAdminCustomer.passwordHash
        ))
    }

    /**
     * Tests the SQL function that performs the instructions
     * given by the exchange to pay one merchant.
     */
    @Test
    fun talerTransferTest() {
        val exchangeReq = TransferRequest(
            amount = TalerAmount(9, 0, "KUDOS"),
            credit_account = "payto://iban/BAR-IBAN-ABC".lowercase(), // foo pays bar
            exchange_base_url = "example.com/exchange",
            request_uid = randHashCode(),
            wtid = randShortHashCode()
        )
        val db = initDb()
        val fooId = db.customerCreate(customerFoo)
        assert(fooId != null)
        val barId = db.customerCreate(customerBar)
        assert(barId != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.bankAccountCreate(bankAccountBar) != null)
        val res = db.talerTransferCreate(
            req = exchangeReq,
            exchangeBankAccountId = 1L,
            timestamp = Instant.now()
        )
        assert(res.txResult == BankTransactionResult.SUCCESS)
    }

    @Test
    fun bearerTokenTest() {
        val db = initDb()
        val tokenBytes = ByteArray(32)
        Random().nextBytes(tokenBytes)
        val token = BearerToken(
            bankCustomer = 1L,
            content = tokenBytes,
            creationTime = Instant.now(),
            expirationTime = Instant.now().plusSeconds(10),
            scope = TokenScope.readonly
        )
        assert(db.bearerTokenGet(token.content) == null)
        assert(db.customerCreate(customerBar) != null) // Tokens need owners.
        assert(db.bearerTokenCreate(token))
        assert(db.bearerTokenGet(tokenBytes) != null)
    }

    @Test
    fun tokenDeletionTest() {
        val db = initDb()
        val token = ByteArray(32)
        // Token not there, must fail.
        assert(!db.bearerTokenDelete(token))
        assert(db.customerCreate(customerBar) != null) // Tokens need owners.
        assert(db.bearerTokenCreate(
            BearerToken(
                bankCustomer = 1L,
                content = token,
                creationTime = Instant.now(),
                expirationTime = Instant.now().plusSeconds(10),
                scope = TokenScope.readwrite
            )
        ))
        // Wrong token given, must fail
        val anotherToken = token.map {
            it.inv() // flipping every bit.
        }
        assert(!db.bearerTokenDelete(anotherToken.toByteArray()))
        // Token there, must succeed.
        assert(db.bearerTokenDelete(token))
    }

    @Test
    fun bankTransactionsTest() {
        val db = initDb()
        val fooId = db.customerCreate(customerFoo)
        assert(fooId != null)
        val barId = db.customerCreate(customerBar)
        assert(barId != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.bankAccountCreate(bankAccountBar) != null)
        var fooAccount = db.bankAccountGetFromOwnerId(fooId!!)
        assert(fooAccount?.hasDebt == false) // Foo has NO debit.
        val currency = "KUDOS"
        // Preparing the payment data.
        db.bankAccountSetMaxDebt(
            fooId,
            TalerAmount(100, 0, currency)
        )
        db.bankAccountSetMaxDebt(
            barId!!,
            TalerAmount(50, 0, currency)
        )
        val firstSpending = db.bankTransactionCreate(fooPaysBar) // Foo pays Bar and goes debit.
        assert(firstSpending == BankTransactionResult.SUCCESS)
        fooAccount = db.bankAccountGetFromOwnerId(fooId)
        // Foo: credit -> debit
        assert(fooAccount?.hasDebt == true) // Asserting Foo's debit.
        // Now checking that more spending doesn't get Foo out of debit.
        val secondSpending = db.bankTransactionCreate(fooPaysBar)
        assert(secondSpending == BankTransactionResult.SUCCESS)
        fooAccount = db.bankAccountGetFromOwnerId(fooId)
        // Checking that Foo's debit is two times the paid amount
        // Foo: debit -> debit
        assert(fooAccount?.balance?.value == 20L
                && fooAccount.balance?.frac == 0
                && fooAccount.hasDebt
        )
        // Asserting Bar has a positive balance and what Foo paid so far.
        var barAccount = db.bankAccountGetFromOwnerId(barId)
        val barBalance: TalerAmount? = barAccount?.balance
        assert(
            barAccount?.hasDebt == false
                    && barBalance?.value == 20L && barBalance.frac == 0
        )
        // Bar pays so that its balance remains positive.
        val barPaysFoo = BankInternalTransaction(
            creditorAccountId = 1,
            debtorAccountId = 2,
            subject = "test",
            amount = TalerAmount(10, 0, currency),
            accountServicerReference = "acct-svcr-ref",
            endToEndId = "end-to-end-id",
            paymentInformationId = "pmtinfid",
            transactionDate = Instant.now()
        )
        val barPays = db.bankTransactionCreate(barPaysFoo)
        assert(barPays == BankTransactionResult.SUCCESS)
        barAccount = db.bankAccountGetFromOwnerId(barId)
        val barBalanceTen: TalerAmount? = barAccount?.balance
        // Bar: credit -> credit
        assert(barAccount?.hasDebt == false && barBalanceTen?.value == 10L && barBalanceTen.frac == 0)
        // Bar pays again to let Foo return in credit.
        val barPaysAgain = db.bankTransactionCreate(barPaysFoo)
        assert(barPaysAgain == BankTransactionResult.SUCCESS)
        // Refreshing the two accounts.
        barAccount = db.bankAccountGetFromOwnerId(barId)
        fooAccount = db.bankAccountGetFromOwnerId(fooId)
        // Foo should have returned to zero and no debt, same for Bar.
        // Foo: debit -> credit
        assert(fooAccount?.hasDebt == false && barAccount?.hasDebt == false)
        assert(fooAccount?.balance?.equals(TalerAmount(0, 0, "KUDOS")) == true)
        assert(barAccount?.balance?.equals(TalerAmount(0, 0, "KUDOS")) == true)
        // Bringing Bar to debit.
        val barPaysMore = db.bankTransactionCreate(barPaysFoo)
        assert(barPaysMore == BankTransactionResult.SUCCESS)
        barAccount = db.bankAccountGetFromOwnerId(barId)
        fooAccount = db.bankAccountGetFromOwnerId(fooId)
        // Bar: credit -> debit
        assert(fooAccount?.hasDebt == false && barAccount?.hasDebt == true)
        assert(fooAccount?.balance?.equals(TalerAmount(10, 0, "KUDOS")) == true)
        assert(barAccount?.balance?.equals(TalerAmount(10, 0, "KUDOS")) == true)
    }

    // Testing customer(+bank account) deletion logic.
    @Test
    fun customerDeletionTest() {
        val db = initDb()
        // asserting false, as foo doesn't exist yet.
        assert(db.customerDeleteIfBalanceIsZero("foo") == CustomerDeletionResult.CUSTOMER_NOT_FOUND)
        // Creating foo.
        db.customerCreate(customerFoo).apply {
            assert(this != null)
            assert(db.bankAccountCreate(bankAccountFoo) != null)
        }
        // foo has zero balance, deletion should succeed.
        assert(db.customerDeleteIfBalanceIsZero("foo") == CustomerDeletionResult.SUCCESS)
        val db2 = initDb()
        // Creating foo again, artificially setting its balance != zero.
        db2.customerCreate(customerFoo).apply {
            assert(this != null)
            db2.bankAccountCreate(bankAccountFoo).apply {
                assert(this != null)
                val conn = DriverManager.getConnection("jdbc:postgresql:///libeufincheck").unwrap(PgConnection::class.java)
                conn.execSQLUpdate("UPDATE libeufin_bank.bank_accounts SET balance.frac = 1 WHERE bank_account_id = $this")
            }
        }
        assert(db.customerDeleteIfBalanceIsZero("foo") == CustomerDeletionResult.BALANCE_NOT_ZERO)
    }
    @Test
    fun customerCreationTest() {
        val db = initDb()
        assert(db.customerGetFromLogin("foo") == null)
        db.customerCreate(customerFoo)
        assert(db.customerGetFromLogin("foo")?.name == "Foo")
        // Trigger conflict.
        assert(db.customerCreate(customerFoo) == null)
    }

    @Test
    fun bankAccountTest() {
        val db = initDb()
        val currency = "KUDOS"
        assert(db.bankAccountGetFromOwnerId(1L) == null)
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) == null) // Triggers conflict.
        assert(db.bankAccountGetFromOwnerId(1L)?.balance?.equals(TalerAmount(0, 0, currency)) == true)
    }

    @Test
    fun withdrawalTest() {
        val db = initDb()
        val uuid = UUID.randomUUID()
        val currency = "KUDOS"
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.customerCreate(customerBar) != null) // plays the exchange.
        assert(db.bankAccountCreate(bankAccountBar) != null)
        // insert new.
        assert(db.talerWithdrawalCreate(
            uuid,
            1L,
            TalerAmount(1, 0, currency)
        ))
        // get it.
        val op = db.talerWithdrawalGet(uuid)
        assert(op?.walletBankAccount == 1L && op.withdrawalUuid == uuid)
        // Setting the details.
        assert(db.talerWithdrawalSetDetails(
            opUuid = uuid,
            exchangePayto = "payto://iban/BAR-IBAN-ABC".lowercase(),
            reservePub = "UNCHECKED-RESERVE-PUB"
        ))
        val opSelected = db.talerWithdrawalGet(uuid)
        assert(opSelected?.selectionDone == true && !opSelected.confirmationDone)
        assert(db.talerWithdrawalConfirm(uuid, Instant.now()) == WithdrawalConfirmationResult.SUCCESS)
        // Finally confirming the operation (means customer wired funds to the exchange.)
        assert(db.talerWithdrawalGet(uuid)?.confirmationDone == true)
    }
    // Only testing the interaction between Kotlin and the DBMS.  No actual logic tested.
    @Test
    fun historyTest() {
        val db = initDb()
        val currency = "KUDOS"
        db.customerCreate(customerFoo); db.bankAccountCreate(bankAccountFoo)
        db.customerCreate(customerBar); db.bankAccountCreate(bankAccountBar)
        assert(db.bankAccountSetMaxDebt(1L, TalerAmount(10000000, 0, currency)))
        // Foo pays Bar 100 times:
        for (i in 1..100) { db.bankTransactionCreate(genTx("test-$i")) }
        // Testing positive delta:
        val forward = db.bankTransactionGetHistory(
            start = 50L,
            delta = 2L,
            bankAccountId = 1L // asking as Foo
        )
        assert(forward[0].expectRowId() >= 50 && forward.size == 2 && forward[0].dbRowId!! < forward[1].dbRowId!!)
        val backward = db.bankTransactionGetHistory(
            start = 50L,
            delta = -2L,
            bankAccountId = 1L // asking as Foo
        )
        assert(backward[0].expectRowId() <= 50 && backward.size == 2 && backward[0].dbRowId!! > backward[1].dbRowId!!)
    }
    @Test
    fun cashoutTest() {
        val db = initDb()
        val currency = "KUDOS"
        val op = Cashout(
            cashoutUuid = UUID.randomUUID(),
            amountDebit = TalerAmount(1, 0, currency),
            amountCredit = TalerAmount(2, 0, currency),
            bankAccount = 1L,
            buyAtRatio = 3,
            buyInFee = TalerAmount(0, 22, currency),
            sellAtRatio = 2,
            sellOutFee = TalerAmount(0, 44, currency),
            credit_payto_uri = "IBAN",
            cashoutCurrency = "KUDOS",
            creationTime = Instant.now(),
            subject = "31st",
            tanChannel = TanChannel.sms,
            tanCode = "secret"
        )
        val fooId = db.customerCreate(customerFoo)
        assert(fooId != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.customerCreate(customerBar) != null)
        assert(db.bankAccountCreate(bankAccountBar) != null)
        assert(db.cashoutCreate(op))
        val fromDb = db.cashoutGetFromUuid(op.cashoutUuid)
        assert(fromDb?.subject == op.subject && fromDb.tanConfirmationTime == null)
        assert(db.cashoutDelete(op.cashoutUuid) == Database.CashoutDeleteResult.SUCCESS)
        assert(db.cashoutCreate(op))
        db.bankAccountSetMaxDebt(
            fooId!!,
            TalerAmount(100, 0, currency)
        )
        assert(db.bankTransactionCreate(
            BankInternalTransaction(
            creditorAccountId = 2,
            debtorAccountId = 1,
            subject = "backing the cash-out",
            amount = TalerAmount(10, 0, currency),
            accountServicerReference = "acct-svcr-ref",
            endToEndId = "end-to-end-id",
            paymentInformationId = "pmtinfid",
            transactionDate = Instant.now()
        )
        ) == BankTransactionResult.SUCCESS)
        // Confirming the cash-out
        assert(db.cashoutConfirm(op.cashoutUuid, 1L, 1L))
        // Checking the confirmation took place.
        assert(db.cashoutGetFromUuid(op.cashoutUuid)?.tanConfirmationTime != null)
        // Deleting the operation.
        assert(db.cashoutDelete(op.cashoutUuid) == Database.CashoutDeleteResult.CONFLICT_ALREADY_CONFIRMED)
        assert(db.cashoutGetFromUuid(op.cashoutUuid) != null) // previous didn't delete.
     }

    // Tests the retrieval of many accounts, used along GET /accounts
    @Test
    fun accountsForAdminTest() {
        val db = initDb()
        assert(db.accountsGetForAdmin().isEmpty()) // No data exists yet.
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.customerCreate(customerBar) != null)
        assert(db.bankAccountCreate(bankAccountBar) != null)
        assert(db.accountsGetForAdmin().size == 2)
        assert(db.accountsGetForAdmin("F%").size == 1) // gets Foo only
        assert(db.accountsGetForAdmin("%ar").size == 1) // gets Bar only
    }

    @Test
    fun passwordChangeTest() {
        val db = initDb()
        // foo not found, this fails.
        assert(!db.customerChangePassword("foo", "won't make it"))
        // creating foo.
        assert(db.customerCreate(customerFoo) != null)
        // foo exists, this succeeds.
        assert(db.customerChangePassword("foo", CryptoUtil.hashpw("new-pw")))
    }

    @Test
    fun getPublicAccountsTest() {
        val db = initDb()
        // Expecting empty, no accounts exist yet.
        assert(db.accountsGetPublic("KUDOS").isEmpty())
        // Make a NON-public account, so expecting still an empty result.
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.accountsGetPublic("KUDOS").isEmpty())

        // Make a public account, so expecting one result.
        db.customerCreate(customerBar).apply {
            assert(this != null)
            assert(db.bankAccountCreate(
                BankAccount(
                    isPublic = true,
                    internalPaytoUri = "payto://iban/non-used",
                    lastNexusFetchRowId = 1L,
                    owningCustomerId = this!!,
                    hasDebt = false,
                    maxDebt = TalerAmount(10, 1, "KUDOS")
                )
            ) != null)
        }
        assert(db.accountsGetPublic("KUDOS").size == 1)

        // Same expectation, filtering on the login "bar"
        assert(db.accountsGetPublic("KUDOS", "b%").size == 1)
        // Expecting empty, as the filter should match nothing.
        assert(db.accountsGetPublic("KUDOS", "x").isEmpty())
    }
}
