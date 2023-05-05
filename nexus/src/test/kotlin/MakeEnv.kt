import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.*
import tech.libeufin.nexus.dbCreateTables
import tech.libeufin.nexus.dbDropTables
import tech.libeufin.nexus.iso20022.*
import tech.libeufin.nexus.server.BankConnectionType
import tech.libeufin.nexus.server.FetchLevel
import tech.libeufin.nexus.server.FetchSpecAllJson
import tech.libeufin.sandbox.*
import tech.libeufin.util.*

data class EbicsKeys(
    val auth: CryptoUtil.RsaCrtKeyPair,
    val enc: CryptoUtil.RsaCrtKeyPair,
    val sig: CryptoUtil.RsaCrtKeyPair
)
// Convenience DB connection to switch to Postgresql:
val currentUser = System.getProperty("user.name")

val BANK_IBAN = getIban()
val FOO_USER_IBAN = getIban()
val BAR_USER_IBAN = getIban()
val TCP_POSTGRES_CONN="jdbc:postgresql://localhost:5432/libeufincheck?user=$currentUser"
val UNIX_SOCKET_CONN= "jdbc:postgresql://localhost/libeufincheck?socketFactory=org.newsclub.net.unix." +
        "AFUNIXSocketFactory\$FactoryArg&socketFactoryArg=/var/run/postgresql/.s.PGSQL.5432"
val TEST_DB_CONN = UNIX_SOCKET_CONN

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

fun assertWithPrint(cond: Boolean, msg: String) {
    try {
        assert(cond)
    } catch (e: AssertionError) {
        System.err.println(msg)
        throw e
    }
}

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
    Database.connect(TEST_DB_CONN, user = currentUser)
    TransactionManager.manager.defaultIsolationLevel = java.sql.Connection.TRANSACTION_SERIALIZABLE
    dbDropTables(TEST_DB_CONN)
    tech.libeufin.sandbox.dbDropTables(TEST_DB_CONN)
    f()
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
        val b = NexusUserEntity.new {
            username = "bar"
            passwordHash = CryptoUtil.hashpw("bar")
            superuser = true
        }
        val c = NexusBankConnectionEntity.new {
            connectionId = "bar"
            owner = b
            type = "x-libeufin-bank"
        }
        val d = NexusBankConnectionEntity.new {
            connectionId = "foo"
            owner = b
            type = "ebics"
        }
        XLibeufinBankUserEntity.new {
            username = "bar"
            password = "bar"
            // Only addressing mild cases where ONE slash ends the base URL.
            baseUrl = "http://localhost/demobanks/default/access-api"
            nexusBankConnection = c
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
            nexusBankConnection = d
            ebicsIniState = EbicsInitState.NOT_SENT
            ebicsHiaState = EbicsInitState.NOT_SENT
            bankEncryptionPublicKey = ExposedBlob(bankKeys.enc.public.encoded)
            bankAuthenticationPublicKey = ExposedBlob(bankKeys.auth.public.encoded)
        }
        NexusBankAccountEntity.new {
            bankAccountName = "foo"
            iban = FOO_USER_IBAN
            bankCode = "SANDBOXX"
            defaultBankConnection = d
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
            facadeName = "foo-facade"
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
        // Giving 'bar' a Taler facade
        val g = FacadeEntity.new {
            facadeName = "bar-facade"
            type = "taler-wire-gateway"
            creator = b
        }
        FacadeStateEntity.new {
            bankAccount = "bar"
            bankConnection = "bar" // uses x-libeufin-bank connection.
            currency = "TESTKUDOS"
            reserveTransferLevel = "report"
            facade = g
            highestSeenMessageSerialId = 0
        }
    }
}

fun prepSandboxDb(usersDebtLimit: Int = 1000, currency: String = "TESTKUDOS") {
    tech.libeufin.sandbox.dbCreateTables(TEST_DB_CONN)
    transaction {
        val config = DemobankConfig(
            currency = currency,
            bankDebtLimit = 10000,
            usersDebtLimit = usersDebtLimit,
            allowRegistrations = true,
            demobankName = "default",
            withSignupBonus = false,
            captchaUrl = "http://example.com/",
            suggestedExchangePayto = "payto://iban/${BAR_USER_IBAN}",
            nexusBaseUrl = "http://localhost/",
            usernameAtNexus = "foo",
            passwordAtNexus = "foo",
            enableConversionService = true
        )
        insertConfigPairs(config)
        val demoBank = DemobankConfigEntity.new { name = "default" }
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
        // Note: exchange doesn't have the cash-out address.
        DemobankCustomerEntity.new {
            username = "exchange-0"
            passwordHash = CryptoUtil.hashpw("foo")
            name = "Exchange"
        }
        BankAccountEntity.new {
            iban = "AT561936082973364859"
            /**
             * For now, keep same semantics of Pybank: a username
             * is AS WELL a bank account label.  In other words, it
             * identifies a customer AND a bank account.
             */
            label = "exchange-0"
            owner = "exchange-0"
            this.demoBank = demoBank
            isPublic = false
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
            val config = DemobankConfig(
                currency = "TESTKUDOS",
                bankDebtLimit = 10000,
                usersDebtLimit = 1000,
                allowRegistrations = true,
                demobankName = "default",
                withSignupBonus = false,
                captchaUrl = "http://example.com/" // unused
            )
            insertConfigPairs(config)
            val d = DemobankConfigEntity.new { name = "default" }
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

fun newNexusBankTransaction(
    currency: String,
    value: String,
    subject: String,
    creditorAcct: String = "foo",
    connType: BankConnectionType = BankConnectionType.EBICS
) {
    val jDetails: String = when(connType) {
        BankConnectionType.EBICS -> {
            jacksonObjectMapper(
            ).writerWithDefaultPrettyPrinter(
            ).writeValueAsString(
                genNexusIncomingCamt(
                    amount = CurrencyAmount(currency,value),
                    subject = subject
                )
            )
        }
        /**
         * Note: x-libeufin-bank ALSO stores the transactions in the
         * CaMt representation, hence this branch should be removed.
         */
        BankConnectionType.X_LIBEUFIN_BANK -> {
            jacksonObjectMapper(
            ).writerWithDefaultPrettyPrinter(
            ).writeValueAsString(genNexusIncomingCamt(
                amount = CurrencyAmount(currency, value),
                subject = subject
            ))
        }
        else -> throw Exception("Unsupported connection type: ${connType.typeName}")
    }
    transaction {
        NexusBankTransactionEntity.new {
            bankAccount = NexusBankAccountEntity.findByName(creditorAcct)!!
            accountTransactionId = "mock"
            creditDebitIndicator = "CRDT"
            this.currency = currency
            this.amount = value
            status = EntryStatus.BOOK
            transactionJson = jDetails
        }
    }
}

/**
 * This function generates the Nexus JSON model of one transaction
 * as if it got downloaded via one x-libeufin-bank connection.  The
 * non given values are either resorted from other sources by Nexus,
 * or actually not useful so far.
 */
private fun genNexusIncomingXLibeufinBank(
    amount: CurrencyAmount,
    subject: String
): XLibeufinBankTransaction =
    XLibeufinBankTransaction(
        creditorIban = "NOTUSED",
        creditorBic =  null,
        creditorName = "Not Used",
        debtorIban =  "NOTUSED",
        debtorBic = null,
        debtorName = "Not Used",
        amount = amount.value,
        currency =  amount.currency,
        subject =  subject,
        date = "0",
        uid =  "not-used",
        direction = XLibeufinBankDirection.CREDIT
    )
/**
 * This function generates the Nexus JSON model of one transaction
 * as if it got downloaded via one Ebics connection.  The non given
 * values are either resorted from other sources by Nexus, or actually
 * not useful so far.
 */
fun genNexusIncomingCamt(
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
                            debtor = PartyIdentification(
                                name = "Mock Payer",
                                countryOfResidence = null,
                                privateId = null,
                                organizationId = null,
                                postalAddress = null,
                                otherId = null
                            ),
                            debtorAccount = CashAccount(
                                iban = "MOCK-IBAN",
                                name = null,
                                currency = null,
                                otherId = null
                            ),
                            debtorAgent = AgentIdentification(
                                bic = "MOCK-BIC",
                                lei = null,
                                clearingSystemMemberId = null,
                                clearingSystemCode = null,
                                proprietaryClearingSystemCode = null,
                                postalAddress = null,
                                otherId = null,
                                name = null
                            ),
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
