import org.junit.Test
import tech.libeufin.sandbox.*
import tech.libeufin.util.execCommand
import java.util.UUID

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
        iban = "FOO-IBAN-XYZ",
        bic = "FOO-BIC",
        bankAccountLabel = "foo",
        lastNexusFetchRowId = 1L,
        owningCustomerId = 1L,
        hasDebt = false
    )
    private val bankAccountBar = BankAccount(
        iban = "BAR-IBAN-ABC",
        bic = "BAR-BIC",
        bankAccountLabel = "bar",
        lastNexusFetchRowId = 1L,
        owningCustomerId = 2L,
        hasDebt = false
    )

    fun initDb(): Database {
        execCommand(
            listOf(
                "libeufin-bank-dbinit",
                "-d",
                "libeufincheck",
                "-r"
            ),
            throwIfFails = true
        )
        val db = Database("jdbc:postgresql:///libeufincheck")
        // Need accounts first.
        db.customerCreate(customerFoo)
        db.customerCreate(customerBar)
        db.bankAccountCreate(bankAccountFoo)
        db.bankAccountCreate(bankAccountBar)
        db.bankAccountSetMaxDebt(
            "foo",
            TalerAmount(100, 0)
        )
        db.bankAccountSetMaxDebt(
            "bar",
            TalerAmount(50, 0)
        )
        return db
    }

    @Test
    fun bankTransactionsTest() {
        val db = initDb()
        var fooAccount = db.bankAccountGetFromLabel("foo")
        assert(fooAccount?.hasDebt == false) // Foo has NO debit.
        // Preparing the payment data.
        val fooPaysBar = BankInternalTransaction(
            creditorAccountId = 2,
            debtorAccountId = 1,
            subject = "test",
            amount = TalerAmount(10, 0),
            accountServicerReference = "acct-svcr-ref",
            endToEndId = "end-to-end-id",
            paymentInformationId = "pmtinfid",
            transactionDate = 100000L
        )
        val firstSpending = db.bankTransactionCreate(fooPaysBar) // Foo pays Bar and goes debit.
        assert(firstSpending == Database.BankTransactionResult.SUCCESS)
        fooAccount = db.bankAccountGetFromLabel("foo")
        // Foo: credit -> debit
        assert(fooAccount?.hasDebt == true) // Asserting Foo's debit.
        // Now checking that more spending doesn't get Foo out of debit.
        val secondSpending = db.bankTransactionCreate(fooPaysBar)
        assert(secondSpending == Database.BankTransactionResult.SUCCESS)
        fooAccount = db.bankAccountGetFromLabel("foo")
        // Checking that Foo's debit is two times the paid amount
        // Foo: debit -> debit
        assert(fooAccount?.balance?.value == 20L
                && fooAccount.balance?.frac == 0
                && fooAccount.hasDebt
        )
        // Asserting Bar has a positive balance and what Foo paid so far.
        var barAccount = db.bankAccountGetFromLabel("bar")
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
            amount = TalerAmount(10, 0),
            accountServicerReference = "acct-svcr-ref",
            endToEndId = "end-to-end-id",
            paymentInformationId = "pmtinfid",
            transactionDate = 100000L
        )
        val barPays = db.bankTransactionCreate(barPaysFoo)
        assert(barPays == Database.BankTransactionResult.SUCCESS)
        barAccount = db.bankAccountGetFromLabel("bar")
        val barBalanceTen: TalerAmount? = barAccount?.balance
        // Bar: credit -> credit
        assert(barAccount?.hasDebt == false && barBalanceTen?.value == 10L && barBalanceTen.frac == 0)
        // Bar pays again to let Foo return in credit.
        val barPaysAgain = db.bankTransactionCreate(barPaysFoo)
        assert(barPaysAgain == Database.BankTransactionResult.SUCCESS)
        // Refreshing the two accounts.
        barAccount = db.bankAccountGetFromLabel("bar")
        fooAccount = db.bankAccountGetFromLabel("foo")
        // Foo should have returned to zero and no debt, same for Bar.
        // Foo: debit -> credit
        assert(fooAccount?.hasDebt == false && barAccount?.hasDebt == false)
        assert(fooAccount?.balance?.equals(TalerAmount(0, 0)) == true)
        assert(barAccount?.balance?.equals(TalerAmount(0, 0)) == true)
        // Bringing Bar to debit.
        val barPaysMore = db.bankTransactionCreate(barPaysFoo)
        assert(barPaysAgain == Database.BankTransactionResult.SUCCESS)
        barAccount = db.bankAccountGetFromLabel("bar")
        fooAccount = db.bankAccountGetFromLabel("foo")
        // Bar: credit -> debit
        assert(fooAccount?.hasDebt == false && barAccount?.hasDebt == true)
        assert(fooAccount?.balance?.equals(TalerAmount(10, 0)) == true)
        assert(barAccount?.balance?.equals(TalerAmount(10, 0)) == true)
    }
    @Test
    fun customerCreationTest() {
        val db = initDb()
        assert(db.customerGetFromLogin("foo") == null)
        db.customerCreate(customerFoo)
        assert(db.customerGetFromLogin("foo")?.name == "Foo")
        // Trigger conflict.
        assert(!db.customerCreate(customerFoo))
    }
    @Test
    fun configTest() {
        val db = initDb()
        assert(db.configGet("bar") == null)
        assert(db.configGet("bar") == null)
        db.configSet("foo", "bar")
        assert(db.configGet("foo") == "bar")
    }
    @Test
    fun bankAccountTest() {
        val db = initDb()
        assert(db.bankAccountGetFromLabel("foo") == null)
        val bankAccount = BankAccount(
            iban = "not used",
            bic = "not used",
            bankAccountLabel = "foo",
            lastNexusFetchRowId = 1L,
            owningCustomerId = 1L,
            hasDebt = false
        )
        db.customerCreate(customerFoo) // Satisfies the REFERENCE
        assert(db.bankAccountCreate(bankAccount))
        assert(!db.bankAccountCreate(bankAccount)) // Triggers conflict.
        assert(db.bankAccountGetFromLabel("foo")?.bankAccountLabel == "foo")
        assert(db.bankAccountGetFromLabel("foo")?.balance?.equals(TalerAmount(0, 0)) == true)
    }

    @Test
    fun withdrawalTest() {
        val db = initDb()
        val uuid = UUID.randomUUID()
        // insert new.
        assert(db.talerWithdrawalCreate(
            uuid,
            1L,
            TalerAmount(1, 0)
        ))
        // get it.
        val op = db.talerWithdrawalGet(uuid)
        assert(op?.walletBankAccount == 1L && op.withdrawalUuid == uuid)
        // Setting the details.
        assert(db.talerWithdrawalSetDetails(
            uuid,
            "exchange-payto",
            ByteArray(32)
        ))
        val opSelected = db.talerWithdrawalGet(uuid)
        assert(opSelected?.selectionDone == true && !opSelected.confirmationDone)
        assert(db.talerWithdrawalConfirm(uuid))
        // Finally confirming the operation (means customer wired funds to the exchange.)
        assert(db.talerWithdrawalGet(uuid)?.confirmationDone == true)
    }
    // Only testing the interaction between Kotlin and the DBMS.  No actual logic tested.
    @Test
    fun historyTest() {
        val db = initDb()
        val res = db.bankTransactionGetForHistoryPage(
            10L,
            1L,
            fromMs = 0,
            toMs = Long.MAX_VALUE
        )
        assert(res.isEmpty())
    }
}