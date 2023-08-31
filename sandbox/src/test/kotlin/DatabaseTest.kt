import org.junit.Test
import tech.libeufin.sandbox.*
import tech.libeufin.util.execCommand

class DatabaseTest {
    private val c = Customer(
        login = "foo",
        passwordHash = "hash",
        name = "Foo",
        phone = "+00",
        email = "foo@b.ar",
        cashoutPayto = "payto://external-IBAN",
        cashoutCurrency = "KUDOS"
    )
    private val c1 = Customer(
        login = "bar",
        passwordHash = "hash",
        name = "Bar",
        phone = "+00",
        email = "foo@b.ar",
        cashoutPayto = "payto://external-IBAN",
        cashoutCurrency = "KUDOS"
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
        return Database("jdbc:postgresql:///libeufincheck")
    }

    @Test
    fun bankTransactionTest() {
        val db = initDb()
        // Need accounts first.
        db.customerCreate(c)
        db.customerCreate(c1)
        db.bankAccountCreate(BankAccount(
            iban = "XYZ",
            bic = "not used",
            bankAccountLabel = "foo",
            lastNexusFetchRowId = 1L,
            owningCustomerId = 1L
        ))
        db.bankAccountCreate(BankAccount(
            iban = "ABC",
            bic = "not used",
            bankAccountLabel = "bar",
            lastNexusFetchRowId = 1L,
            owningCustomerId = 2L
        ))
        db.bankAccountSetMaxDebt("foo", TalerAmount(100, 0))
        val res = db.bankTransactionCreate(BankInternalTransaction(
            creditorAccountId = 2,
            debtorAccountId = 1,
            subject = "test",
            amount = TalerAmount(3, 333),
            accountServicerReference = "acct-svcr-ref",
            endToEndId = "end-to-end-id",
            paymentInformationId = "pmtinfid",
            transactionDate = 100000L
        ))
        assert(res == Database.BankTransactionResult.SUCCESS)
    }
    @Test
    fun customerCreationTest() {
        val db = initDb()
        assert(db.customerGetFromLogin("foo") == null)
        db.customerCreate(c)
        assert(db.customerGetFromLogin("foo")?.name == "Foo")
        // Trigger conflict.
        assert(!db.customerCreate(c))
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
            owningCustomerId = 1L
        )
        db.customerCreate(c) // Satisfies the REFERENCE
        assert(db.bankAccountCreate(bankAccount))
        assert(!db.bankAccountCreate(bankAccount)) // Triggers conflict.
        assert(db.bankAccountGetFromLabel("foo")?.bankAccountLabel == "foo")
        assert(db.bankAccountGetFromLabel("foo")?.balance?.equals(TalerAmount(0, 0)) == true)
    }
}