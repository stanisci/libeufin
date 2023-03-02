import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.transactionManager
import tech.libeufin.nexus.*
import tech.libeufin.nexus.dbCreateTables
import tech.libeufin.nexus.dbDropTables
import tech.libeufin.nexus.iso20022.*
import tech.libeufin.nexus.server.CurrencyAmount
import tech.libeufin.nexus.server.FetchLevel
import tech.libeufin.nexus.server.FetchSpecAllJson
import tech.libeufin.sandbox.*
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.EbicsInitState
import java.io.File
import tech.libeufin.util.getIban

data class EbicsKeys(
    val auth: CryptoUtil.RsaCrtKeyPair,
    val enc: CryptoUtil.RsaCrtKeyPair,
    val sig: CryptoUtil.RsaCrtKeyPair
)
const val TEST_DB_FILE = "/tmp/nexus-test.sqlite3"
// const val TEST_DB_CONN = "jdbc:sqlite:$TEST_DB_FILE"
// Convenience DB connection to switch to Postgresql:
const val TEST_DB_CONN = "jdbc:postgresql://localhost:5432/talercheck?user=job"
val BANK_IBAN = getIban()
val FOO_USER_IBAN = getIban()
val BAR_USER_IBAN = getIban()

val bankKeys = EbicsKeys(
    auth = CryptoUtil.generateRsaKeyPair(2048),
    enc = CryptoUtil.generateRsaKeyPair(2048),
    sig = CryptoUtil.generateRsaKeyPair(2048)
)
val userKeys = EbicsKeys(
    auth = CryptoUtil.generateRsaKeyPair(2048),
    enc = CryptoUtil.generateRsaKeyPair(2048),
    sig = CryptoUtil.generateRsaKeyPair(2048)
)

// New versions of JUnit provide this!
inline fun <reified ExceptionType> assertException(
    block: () -> Unit,
    assertBlock: (Throwable) -> Unit = {}
) {
    try {
        block()
    } catch (e: Throwable) {
        assert(e.javaClass == ExceptionType::class.java)
        // Expected type, try more custom asserts on it
        assertBlock(e)
        return
    }
    return assert(false)
}

/**
 * Run a block after connecting to the test database.
 * Cleans up the DB file afterwards.
 */
fun withTestDatabase(f: () -> Unit) {
    File(TEST_DB_FILE).also {
        if (it.exists()) {
            it.delete()
        }
    }
    Database.connect(TEST_DB_CONN)
    TransactionManager.manager.defaultIsolationLevel = java.sql.Connection.TRANSACTION_SERIALIZABLE
    dbDropTables(TEST_DB_CONN)
    tech.libeufin.sandbox.dbDropTables(TEST_DB_CONN)
    try { f() }
    finally {
        File(TEST_DB_FILE).also {
            if (it.exists()) {
                it.delete()
            }
        }
    }
}

val reportSpec: String = jacksonObjectMapper().
writerWithDefaultPrettyPrinter().
writeValueAsString(
    FetchSpecAllJson(
        level = FetchLevel.REPORT,
        "foo"
    )
)

fun prepNexusDb() {
    dbCreateTables(TEST_DB_CONN)
    transaction {
        val u = NexusUserEntity.new {
            username = "foo"
            passwordHash = CryptoUtil.hashpw("foo")
            superuser = true
        }
        val c = NexusBankConnectionEntity.new {
            connectionId = "foo"
            owner = u
            type = "ebics"
        }
        tech.libeufin.nexus.EbicsSubscriberEntity.new {
            // ebicsURL = "http://localhost:5000/ebicsweb"
            ebicsURL = "http://localhost/ebicsweb"
            hostID = "eufinSandbox"
            partnerID = "foo"
            userID = "foo"
            systemID = "foo"
            signaturePrivateKey = ExposedBlob(userKeys.sig.private.encoded)
            encryptionPrivateKey = ExposedBlob(userKeys.enc.private.encoded)
            authenticationPrivateKey = ExposedBlob(userKeys.auth.private.encoded)
            nexusBankConnection = c
            ebicsIniState = EbicsInitState.NOT_SENT
            ebicsHiaState = EbicsInitState.NOT_SENT
            bankEncryptionPublicKey = ExposedBlob(bankKeys.enc.public.encoded)
            bankAuthenticationPublicKey = ExposedBlob(bankKeys.auth.public.encoded)
        }
        NexusBankAccountEntity.new {
            bankAccountName = "foo"
            iban = FOO_USER_IBAN
            bankCode = "SANDBOXX"
            defaultBankConnection = c
            highestSeenBankMessageSerialId = 0
            accountHolder = "foo"
        }
        NexusBankAccountEntity.new {
            bankAccountName = "bar"
            iban = BAR_USER_IBAN
            bankCode = "SANDBOXX"
            defaultBankConnection = c
            highestSeenBankMessageSerialId = 0
            accountHolder = "bar"
        }
        NexusScheduledTaskEntity.new {
            resourceType = "bank-account"
            resourceId = "foo"
            this.taskCronspec = "* * *" // Every second.
            this.taskName = "read-report"
            this.taskType = "fetch"
            this.taskParams = reportSpec
        }
        NexusScheduledTaskEntity.new {
            resourceType = "bank-account"
            resourceId = "foo"
            this.taskCronspec = "* * *" // Every second.
            this.taskName = "send-payment"
            this.taskType = "submit"
            this.taskParams = "{}"
        }
        // Giving 'foo' a Taler facade.
        val f = FacadeEntity.new {
            facadeName = "taler"
            type = "taler-wire-gateway"
            creator = u
        }
        FacadeStateEntity.new {
            bankAccount = "foo"
            bankConnection = "foo"
            currency = "TESTKUDOS"
            reserveTransferLevel = "report"
            facade = f
            highestSeenMessageSerialId = 0
        }
    }
}

fun prepSandboxDb() {
    tech.libeufin.sandbox.dbCreateTables(TEST_DB_CONN)
    transaction {
        val demoBank = DemobankConfigEntity.new {
            currency = "TESTKUDOS"
            bankDebtLimit = 10000
            usersDebtLimit = 1000
            allowRegistrations = true
            name = "default"
            this.withSignupBonus = false
            captchaUrl = "http://example.com/" // unused
            suggestedExchangePayto = "payto://iban/${BAR_USER_IBAN}"
        }
        BankAccountEntity.new {
            iban = BANK_IBAN
            label = "admin" // used by the wire helper
            owner = "admin" // used by the person name finder
            // For now, the model assumes always one demobank
            this.demoBank = demoBank
        }
        EbicsHostEntity.new {
            this.ebicsVersion = "3.0"
            this.hostId = "eufinSandbox"
            this.authenticationPrivateKey = ExposedBlob(bankKeys.auth.private.encoded)
            this.encryptionPrivateKey = ExposedBlob(bankKeys.enc.private.encoded)
            this.signaturePrivateKey = ExposedBlob(bankKeys.sig.private.encoded)
        }
        val bankAccount = BankAccountEntity.new {
            iban = FOO_USER_IBAN
            /**
             * For now, keep same semantics of Pybank: a username
             * is AS WELL a bank account label.  In other words, it
             * identifies a customer AND a bank account.
             */
            label = "foo"
            owner = "foo"
            this.demoBank = demoBank
            isPublic = false
        }
        BankAccountEntity.new {
            iban = BAR_USER_IBAN
            /**
             * For now, keep same semantics of Pybank: a username
             * is AS WELL a bank account label.  In other words, it
             * identifies a customer AND a bank account.
             */
            label = "bar"
            owner = "bar"
            this.demoBank = demoBank
            isPublic = false
        }
        tech.libeufin.sandbox.EbicsSubscriberEntity.new {
            hostId = "eufinSandbox"
            partnerId = "foo"
            userId = "foo"
            systemId = "foo"
            signatureKey = EbicsSubscriberPublicKeyEntity.new {
                rsaPublicKey = ExposedBlob(userKeys.sig.public.encoded)
                state = KeyState.RELEASED
            }
            encryptionKey = EbicsSubscriberPublicKeyEntity.new {
                rsaPublicKey = ExposedBlob(userKeys.enc.public.encoded)
                state = KeyState.RELEASED
            }
            authenticationKey = EbicsSubscriberPublicKeyEntity.new {
                rsaPublicKey = ExposedBlob(userKeys.auth.public.encoded)
                state = KeyState.RELEASED
            }
            state = SubscriberState.INITIALIZED
            nextOrderID = 1
            this.bankAccount = bankAccount
        }
        DemobankCustomerEntity.new {
            username = "foo"
            passwordHash = CryptoUtil.hashpw("foo")
            name = "Foo"
            cashout_address = "payto://iban/OUTSIDE"
        }
        DemobankCustomerEntity.new {
            username = "bar"
            passwordHash = CryptoUtil.hashpw("bar")
            name = "Bar"
            cashout_address = "payto://iban/FIAT"
        }
        DemobankCustomerEntity.new {
            username = "baz"
            passwordHash = CryptoUtil.hashpw("foo")
            name = "Baz"
            cashout_address = "payto://iban/OTHERBANK"
        }
    }
}

fun withNexusAndSandboxUser(f: () -> Unit) {
    withTestDatabase {
        prepNexusDb()
        prepSandboxDb()
        f()
    }
}

// Creates tables, the default demobank, and admin's bank account.
fun withSandboxTestDatabase(f: () -> Unit) {
    withTestDatabase {
        tech.libeufin.sandbox.dbCreateTables(TEST_DB_CONN)
        transaction {
            val d = DemobankConfigEntity.new {
                currency = "TESTKUDOS"
                bankDebtLimit = 10000
                usersDebtLimit = 1000
                allowRegistrations = true
                name = "default"
                this.withSignupBonus = false
                captchaUrl = "http://example.com/" // unused
            }
            // admin's bank account.
            BankAccountEntity.new {
                iban = BANK_IBAN
                label = "admin" // used by the wire helper
                owner = "admin" // used by the person name finder
                // For now, the model assumes always one demobank
                this.demoBank = d
            }
        }
        f()
    }
}

fun talerIncomingForFoo(currency: String, value: String, subject: String) {
    transaction {
        val inc = NexusBankTransactionEntity.new {
            bankAccount = NexusBankAccountEntity.findByName("foo")!!
            accountTransactionId = "mock"
            creditDebitIndicator = "CRDT"
            this.currency = currency
            this.amount = value
            status = EntryStatus.BOOK
            transactionJson = jacksonObjectMapper(
            ).writerWithDefaultPrettyPrinter(
            ).writeValueAsString(
                genNexusIncomingPayment(
                    amount = CurrencyAmount(currency,value),
                    subject = subject
                )
            )
        }
        TalerIncomingPaymentEntity.new {
            payment = inc
            reservePublicKey = "mock"
            timestampMs = 0L
            debtorPaytoUri = "mock"
        }
    }
}


fun genNexusIncomingPayment(
    amount: CurrencyAmount,
    subject: String,
): CamtBankAccountEntry =
    CamtBankAccountEntry(
        amount = amount,
        creditDebitIndicator = CreditDebitIndicator.CRDT,
        status = EntryStatus.BOOK,
        bankTransactionCode = "mock",
        valueDate = null,
        bookingDate = null,
        accountServicerRef = null,
        entryRef = null,
        currencyExchange = null,
        counterValueAmount = null,
        instructedAmount = null,
        batches = listOf(
            Batch(
                paymentInformationId = null,
                messageId = null,
                batchTransactions = listOf(
                    BatchTransaction(
                        amount = amount,
                        creditDebitIndicator = CreditDebitIndicator.CRDT,
                        details = TransactionDetails(
                            unstructuredRemittanceInformation = subject,
                            debtor = null,
                            debtorAccount = null,
                            debtorAgent = null,
                            creditor = null,
                            creditorAccount = null,
                            creditorAgent = null,
                            ultimateCreditor = null,
                            ultimateDebtor = null,
                            purpose = null,
                            proprietaryPurpose = null,
                            currencyExchange = null,
                            instructedAmount = null,
                            counterValueAmount = null,
                            interBankSettlementAmount = null,
                            returnInfo = null
                        )
                    )
                )
            )
        )
    )