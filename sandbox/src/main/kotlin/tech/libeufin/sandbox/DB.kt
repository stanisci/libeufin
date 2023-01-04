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

package tech.libeufin.sandbox

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

/**
 * All the states to give a subscriber.
 */
enum class SubscriberState {
    /**
     * No keys at all given to the bank.
     */
    NEW,

    /**
     * Only INI electronic message was successfully sent.
     */
    PARTIALLY_INITIALIZED_INI,

    /**r
     * Only HIA electronic message was successfully sent.
     */
    PARTIALLY_INITIALIZED_HIA,

    /**
     * Both INI and HIA were electronically sent with success.
     */
    INITIALIZED,

    /**
     * All the keys accounted in INI and HIA have been confirmed
     * via physical mail.
     */
    READY
}

/**
 * All the states that one key can be assigned.
 */
enum class KeyState {

    /**
     * The key was never communicated.
     */
    MISSING,

    /**
     * The key has been electronically sent.
     */
    NEW,

    /**
     * The key has been confirmed (either via physical mail
     * or electronically -- e.g. with certificates)
     */
    RELEASED
}

object DemobankConfigsTable : LongIdTable() {
    val currency = text("currency")
    val allowRegistrations = bool("allowRegistrations")
    val withSignupBonus = bool("withSignupBonus")
    val bankDebtLimit = integer("bankDebtLimit")
    val usersDebtLimit = integer("usersDebtLimit")
    val name = text("hostname")
    val suggestedExchangeBaseUrl = text("suggestedExchangeBaseUrl").nullable()
    val suggestedExchangePayto = text("suggestedExchangePayto").nullable()
    val captchaUrl = text("captchaUrl").nullable()
}

class DemobankConfigEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<DemobankConfigEntity>(DemobankConfigsTable)
    var currency by DemobankConfigsTable.currency
    var allowRegistrations by DemobankConfigsTable.allowRegistrations
    var withSignupBonus by DemobankConfigsTable.withSignupBonus
    var bankDebtLimit by DemobankConfigsTable.bankDebtLimit
    var usersDebtLimit by DemobankConfigsTable.usersDebtLimit
    var name by DemobankConfigsTable.name
    var captchaUrl by DemobankConfigsTable.captchaUrl
    var suggestedExchangeBaseUrl by DemobankConfigsTable.suggestedExchangeBaseUrl
    var suggestedExchangePayto by DemobankConfigsTable.suggestedExchangePayto
}

/**
 * Users who are allowed to log into the demo bank.
 * Created via the /demobanks/{demobankname}/register endpoint.
 */
object DemobankCustomersTable : LongIdTable() {
    val username = text("username")
    val passwordHash = text("passwordHash")
    val name = text("name").nullable()
    val email = text("email").nullable()
    val phone = text("phone").nullable()
    val cashout_address = text("cashout_address").nullable()
}

class DemobankCustomerEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<DemobankCustomerEntity>(DemobankCustomersTable)
    var username by DemobankCustomersTable.username
    var passwordHash by DemobankCustomersTable.passwordHash
    var name by DemobankCustomersTable.name
    var email by DemobankCustomersTable.email
    var phone by DemobankCustomersTable.phone
    var cashout_address by DemobankCustomersTable.cashout_address
}

/**
 * This table stores RSA public keys of subscribers.
 */
object EbicsSubscriberPublicKeysTable : IntIdTable() {
    val rsaPublicKey = blob("rsaPublicKey")
    val state = enumeration("state", KeyState::class)
}

class EbicsSubscriberPublicKeyEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsSubscriberPublicKeyEntity>(EbicsSubscriberPublicKeysTable)
    var rsaPublicKey by EbicsSubscriberPublicKeysTable.rsaPublicKey
    var state by EbicsSubscriberPublicKeysTable.state
}

/**
 * Ebics 'host'(s) that are served by one Sandbox instance.
 */
object EbicsHostsTable : IntIdTable() {
    val hostID = text("hostID")
    val ebicsVersion = text("ebicsVersion")
    val signaturePrivateKey = blob("signaturePrivateKey")
    val encryptionPrivateKey = blob("encryptionPrivateKey")
    val authenticationPrivateKey = blob("authenticationPrivateKey")
}

class EbicsHostEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsHostEntity>(EbicsHostsTable)
    var hostId by EbicsHostsTable.hostID
    var ebicsVersion by EbicsHostsTable.ebicsVersion
    var signaturePrivateKey by EbicsHostsTable.signaturePrivateKey
    var encryptionPrivateKey by EbicsHostsTable.encryptionPrivateKey
    var authenticationPrivateKey by EbicsHostsTable.authenticationPrivateKey
}

/**
 * Ebics Subscribers table.
 */
object EbicsSubscribersTable : IntIdTable() {
    val userId = text("userID")
    val partnerId = text("partnerID")
    val systemId = text("systemID").nullable()
    val hostId = text("hostID")
    val signatureKey = reference("signatureKey", EbicsSubscriberPublicKeysTable).nullable()
    val encryptionKey = reference("encryptionKey", EbicsSubscriberPublicKeysTable).nullable()
    val authenticationKey = reference("authorizationKey", EbicsSubscriberPublicKeysTable).nullable()
    val nextOrderID = integer("nextOrderID")
    val state = enumeration("state", SubscriberState::class)
    val bankAccount = reference("bankAccount", BankAccountsTable).nullable()
}

class EbicsSubscriberEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsSubscriberEntity>(EbicsSubscribersTable)
    var userId by EbicsSubscribersTable.userId
    var partnerId by EbicsSubscribersTable.partnerId
    var systemId by EbicsSubscribersTable.systemId
    var hostId by EbicsSubscribersTable.hostId
    var signatureKey by EbicsSubscriberPublicKeyEntity optionalReferencedOn EbicsSubscribersTable.signatureKey
    var encryptionKey by EbicsSubscriberPublicKeyEntity optionalReferencedOn EbicsSubscribersTable.encryptionKey
    var authenticationKey by EbicsSubscriberPublicKeyEntity optionalReferencedOn EbicsSubscribersTable.authenticationKey
    var nextOrderID by EbicsSubscribersTable.nextOrderID
    var state by EbicsSubscribersTable.state
    var bankAccount by BankAccountEntity optionalReferencedOn EbicsSubscribersTable.bankAccount
}

/**
 * Details of a download order.
 */
object EbicsDownloadTransactionsTable : IdTable<String>() {
    override val id = text("transactionID").entityId()
    val orderType = text("orderType")
    val host = reference("host", EbicsHostsTable)
    val subscriber = reference("subscriber", EbicsSubscribersTable)
    val encodedResponse = text("encodedResponse")
    val transactionKeyEnc = blob("transactionKeyEnc")
    val numSegments = integer("numSegments")
    val segmentSize = integer("segmentSize")
    val receiptReceived = bool("receiptReceived")
}

class EbicsDownloadTransactionEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, EbicsDownloadTransactionEntity>(EbicsDownloadTransactionsTable)

    var orderType by EbicsDownloadTransactionsTable.orderType
    var host by EbicsHostEntity referencedOn EbicsDownloadTransactionsTable.host
    var subscriber by EbicsSubscriberEntity referencedOn EbicsDownloadTransactionsTable.subscriber
    var encodedResponse by EbicsDownloadTransactionsTable.encodedResponse
    var numSegments by EbicsDownloadTransactionsTable.numSegments
    var transactionKeyEnc by EbicsDownloadTransactionsTable.transactionKeyEnc
    var segmentSize by EbicsDownloadTransactionsTable.segmentSize
    var receiptReceived by EbicsDownloadTransactionsTable.receiptReceived
}

/**
 * Details of a upload order.
 */
object EbicsUploadTransactionsTable : IdTable<String>() {
    override val id = text("transactionID").entityId()
    val orderType = text("orderType")
    val orderID = text("orderID")
    val host = reference("host", EbicsHostsTable)
    val subscriber = reference("subscriber", EbicsSubscribersTable)
    val numSegments = integer("numSegments")
    val lastSeenSegment = integer("lastSeenSegment")
    val transactionKeyEnc = blob("transactionKeyEnc")
}

class EbicsUploadTransactionEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, EbicsUploadTransactionEntity>(EbicsUploadTransactionsTable)
    var orderType by EbicsUploadTransactionsTable.orderType
    var orderID by EbicsUploadTransactionsTable.orderID
    var host by EbicsHostEntity referencedOn EbicsUploadTransactionsTable.host
    var subscriber by EbicsSubscriberEntity referencedOn EbicsUploadTransactionsTable.subscriber
    var numSegments by EbicsUploadTransactionsTable.numSegments
    var lastSeenSegment by EbicsUploadTransactionsTable.lastSeenSegment
    var transactionKeyEnc by EbicsUploadTransactionsTable.transactionKeyEnc
}

/**
 * FIXME: document this.
 */
object EbicsOrderSignaturesTable : IntIdTable() {
    val orderID = text("orderID")
    val orderType = text("orderType")
    val partnerID = text("partnerID")
    val userID = text("userID")
    val signatureAlgorithm = text("signatureAlgorithm")
    val signatureValue = blob("signatureValue")
}

class EbicsOrderSignatureEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsOrderSignatureEntity>(EbicsOrderSignaturesTable)
    var orderID by EbicsOrderSignaturesTable.orderID
    var orderType by EbicsOrderSignaturesTable.orderType
    var partnerID by EbicsOrderSignaturesTable.partnerID
    var userID by EbicsOrderSignaturesTable.userID
    var signatureAlgorithm by EbicsOrderSignaturesTable.signatureAlgorithm
    var signatureValue by EbicsOrderSignaturesTable.signatureValue
}

/**
 * FIXME: document this.
 */
object EbicsUploadTransactionChunksTable : IdTable<String>() {
    override val id = text("transactionID").entityId()
    val chunkIndex = integer("chunkIndex")
    val chunkContent = blob("chunkContent")
}

// FIXME: Is upload chunking not implemented somewhere?!
class EbicsUploadTransactionChunkEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, EbicsUploadTransactionChunkEntity>(EbicsUploadTransactionChunksTable)
    var chunkIndex by EbicsUploadTransactionChunksTable.chunkIndex
    var chunkContent by EbicsUploadTransactionChunksTable.chunkContent
}


/**
 * Holds those transactions that aren't yet reported in a Camt.053 document.
 * After reporting those, the table gets emptied.  Rows are merely references
 * to the main ledger.
 */
object BankAccountFreshTransactionsTable : LongIdTable() {
    val transactionRef = reference("transaction", BankAccountTransactionsTable)
}
class BankAccountFreshTransactionEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<BankAccountFreshTransactionEntity>(BankAccountFreshTransactionsTable)
    var transactionRef by BankAccountTransactionEntity referencedOn BankAccountFreshTransactionsTable.transactionRef
}

/**
 * Table that keeps all the payments initiated by PAIN.001.
 */
object BankAccountTransactionsTable : LongIdTable() {
    val creditorIban = text("creditorIban")
    val creditorBic = text("creditorBic").nullable()
    val creditorName = text("creditorName")
    val debtorIban = text("debtorIban")
    val debtorBic = text("debtorBic").nullable()
    val debtorName = text("debtorName")
    val subject = text("subject")
    // Amount is a BigDecimal in String form.
    val amount = text("amount")
    val currency = text("currency")
    val date = long("date")
    // Unique ID for this payment within the bank account.
    val accountServicerReference = text("accountServicerReference")
    /**
     * Payment information ID, which is a reference to the payment initiation
     * that triggered this transaction.  Typically, only available with outgoing transactions.
     */
    val pmtInfId = text("pmtInfId").nullable()
    val direction = text("direction")
    /**
     * Bank account of the party whose 'direction' refers.  This version allows
     * only both parties to be registered at the running Sandbox.
     */
    val account = reference("account", BankAccountsTable)
    // Redundantly storing the demobank for query convenience.
    val demobank = reference("demobank", DemobankConfigsTable)
}

class BankAccountTransactionEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<BankAccountTransactionEntity>(BankAccountTransactionsTable) {
        override fun new(init: BankAccountTransactionEntity.() -> Unit): BankAccountTransactionEntity {
            /**
             * Fresh transactions are those that wait to be included in a
             * "history" report, likely a Camt.5x message.  The "fresh transactions"
             * table keeps a list of such transactions.
             */
            val freshTx = super.new(init)
            BankAccountFreshTransactionsTable.insert {
                it[transactionRef] = freshTx.id
            }
            /**
             * The bank account involved in this transaction points to
             * it as the "last known" transaction, to make it easier to
             * build histories that depend on such record.
             */
            freshTx.account.lastTransaction = freshTx
            return freshTx
        }
    }
    var creditorIban by BankAccountTransactionsTable.creditorIban
    var creditorBic by BankAccountTransactionsTable.creditorBic
    var creditorName by BankAccountTransactionsTable.creditorName
    var debtorIban by BankAccountTransactionsTable.debtorIban
    var debtorBic by BankAccountTransactionsTable.debtorBic
    var debtorName by BankAccountTransactionsTable.debtorName
    var subject by BankAccountTransactionsTable.subject
    var amount by BankAccountTransactionsTable.amount
    var currency by BankAccountTransactionsTable.currency
    var date by BankAccountTransactionsTable.date
    var accountServicerReference by BankAccountTransactionsTable.accountServicerReference
    var pmtInfId by BankAccountTransactionsTable.pmtInfId
    var direction by BankAccountTransactionsTable.direction
    var account by BankAccountEntity referencedOn BankAccountTransactionsTable.account
    var demobank by DemobankConfigEntity referencedOn BankAccountTransactionsTable.demobank
}

/**
 * Table that keeps information about which bank accounts (iban+bic+name)
 * are active in the system.  In the current version, 'label' and 'owner'
 * are always equal; future versions may change this, when one customer can
 * own multiple bank accounts.
 */
object BankAccountsTable : IntIdTable() {
    val iban = text("iban")
    val bic = text("bic").default("SANDBOXX")
    val label = text("label").uniqueIndex("accountLabelIndex")
    /**
     * This field is the username of the customer that owns the
     * bank account.  Admin is the only exception: that can specify
     * this field as "admin" although no customer backs it.
     */
    val owner = text("owner")
    val isPublic = bool("isPublic").default(false)
    val demoBank = reference("demoBank", DemobankConfigsTable)

    /**
     * Point to the last transaction related to this account, regardless
     * of it being credit or debit.  This reference helps to construct
     * history results that start from / depend on the last transaction.
     */
    val lastTransaction = reference("lastTransaction", BankAccountTransactionsTable).nullable()
}

class BankAccountEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BankAccountEntity>(BankAccountsTable)

    var iban by BankAccountsTable.iban
    var bic by BankAccountsTable.bic
    var label by BankAccountsTable.label
    var owner by BankAccountsTable.owner
    var isPublic by BankAccountsTable.isPublic
    var demoBank by DemobankConfigEntity referencedOn BankAccountsTable.demoBank
    var lastTransaction by BankAccountTransactionEntity optionalReferencedOn BankAccountsTable.lastTransaction
}

object BankAccountStatementsTable : IntIdTable() {
    val statementId = text("statementId")
    val creationTime = long("creationTime")
    val xmlMessage = text("xmlMessage")
    val bankAccount = reference("bankAccount", BankAccountsTable)
    // Signed BigDecimal representing a Camt.053 CLBD field.
    val balanceClbd = text("balanceClbd")
}

class BankAccountStatementEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BankAccountStatementEntity>(BankAccountStatementsTable)
    var statementId by BankAccountStatementsTable.statementId
    var creationTime by BankAccountStatementsTable.creationTime
    var xmlMessage by BankAccountStatementsTable.xmlMessage
    var bankAccount by BankAccountEntity referencedOn BankAccountStatementsTable.bankAccount
    var balanceClbd by BankAccountStatementsTable.balanceClbd
}

enum class CashoutOperationState { CONFIRMED, PENDING }
object CashoutOperationsTable : LongIdTable() {
    val uuid = uuid("uuid").autoGenerate()
    /**
     * This amount is the one the user entered in the cash-out
     * dialog.  That will show up as the outgoing transfer in their
     * local currency bank account.
     */
    val amountDebit = text("amountDebit")
    val subject = text("subject")
    val creationTime = long("creationTime") // in seconds.
    val tanChannel = text("tanChannel")
    val account = text("account")
    val tan = text("tan")
    val state = enumeration("state", CashoutOperationState::class).default(CashoutOperationState.PENDING)
}

class CashoutOperationEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<CashoutOperationEntity>(CashoutOperationsTable)
    var uuid by CashoutOperationsTable.uuid
    var amountDebit by CashoutOperationsTable.amountDebit
    var subject by CashoutOperationsTable.subject
    var creationTime by CashoutOperationsTable.creationTime
    var tanChannel by CashoutOperationsTable.tanChannel
    var account by CashoutOperationsTable.account
    var tan by CashoutOperationsTable.tan
    var state by CashoutOperationsTable.state
}
object TalerWithdrawalsTable : LongIdTable() {
    val wopid = uuid("wopid").autoGenerate()
    val amount = text("amount") // $currency:x.y
    /**
     * Turns to true after the wallet gave the reserve public key
     * and the exchange details to the bank.
     */
    val selectionDone = bool("selectionDone").default(false)
    val aborted = bool("aborted").default(false)
    /**
     * Turns to true after the wire transfer to the exchange bank account
     * gets completed _on the bank's side_.  This does never guarantees that
     * the payment arrived at the exchange's bank yet.
     */
    val confirmationDone = bool("confirmationDone").default(false)
    val reservePub = text("reservePub").nullable()
    val selectedExchangePayto = text("selectedExchangePayto").nullable()
    val walletBankAccount = reference("walletBankAccount", BankAccountsTable)
}
class TalerWithdrawalEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TalerWithdrawalEntity>(TalerWithdrawalsTable)
    var wopid by TalerWithdrawalsTable.wopid
    var selectionDone by TalerWithdrawalsTable.selectionDone
    var confirmationDone by TalerWithdrawalsTable.confirmationDone
    var reservePub by TalerWithdrawalsTable.reservePub
    var selectedExchangePayto by TalerWithdrawalsTable.selectedExchangePayto
    var amount by TalerWithdrawalsTable.amount
    var walletBankAccount by BankAccountEntity referencedOn TalerWithdrawalsTable.walletBankAccount
    var aborted by TalerWithdrawalsTable.aborted
}

object BankAccountReportsTable : IntIdTable() {
    val reportId = text("reportId")
    val creationTime = long("creationTime")
    val xmlMessage = text("xmlMessage")
    val bankAccount = reference("bankAccount", BankAccountsTable)
}

fun dbDropTables(dbConnectionString: String) {
    Database.connect(dbConnectionString)
    transaction {
        SchemaUtils.drop(
            EbicsSubscribersTable,
            EbicsHostsTable,
            EbicsDownloadTransactionsTable,
            EbicsUploadTransactionsTable,
            EbicsUploadTransactionChunksTable,
            EbicsOrderSignaturesTable,
            BankAccountTransactionsTable,
            BankAccountFreshTransactionsTable,
            BankAccountsTable,
            BankAccountReportsTable,
            BankAccountStatementsTable,
            DemobankConfigsTable,
            TalerWithdrawalsTable
        )
    }
}

fun dbCreateTables(dbConnectionString: String) {
    Database.connect(dbConnectionString)
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    transaction {
        SchemaUtils.create(
            DemobankConfigsTable,
            EbicsSubscribersTable,
            EbicsHostsTable,
            EbicsDownloadTransactionsTable,
            EbicsUploadTransactionsTable,
            EbicsUploadTransactionChunksTable,
            EbicsOrderSignaturesTable,
            BankAccountTransactionsTable,
            BankAccountFreshTransactionsTable,
            BankAccountsTable,
            BankAccountReportsTable,
            BankAccountStatementsTable,
            TalerWithdrawalsTable,
            DemobankCustomersTable,
            CashoutOperationsTable
        )
    }
}
