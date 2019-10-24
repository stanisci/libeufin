package tech.libeufin.sandbox

import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

const val CUSTOMER_NAME_MAX_LENGTH = 20
const val EBICS_USER_ID_MAX_LENGTH = 10
const val EBICS_PARTNER_ID_MAX_LENGTH = 10
const val EBICS_SYSTEM_ID_MAX_LENGTH = 10
const val PUBLIC_KEY_MAX_MODULUS_LENGTH = 2048 // FIXME review this value!
const val PUBLIC_KEY_MAX_EXPONENT_LENGTH = 64 // FIXME review this value!
const val PRIVATE_KEY_MODULUS_LENGTH = 1024 // FIXME review this value!
const val PRIVATE_KEY_EXPONENT_LENGTH = 10

/**
 * All the states to give a subscriber.
 */
enum class SubscriberStates {
    /**
     * No keys at all given to the bank.
     */
    NEW,

    /**
     * Only INI electronic message was successfully sent.
     */
    PARTIALLY_INITIALIZED_INI,

    /**
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
enum class KeyStates {

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

/**
 * This table information *not* related to EBICS, for all
 * its customers.
 */
object BankCustomers: IntIdTable() {
    // Customer ID is the default 'id' field provided by the constructor.
    val name = varchar("name", CUSTOMER_NAME_MAX_LENGTH).primaryKey()
    val ebicsSubscriber = reference("ebicsSubscriber", EbicsSubscribers)
}

class BankCustomer(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BankCustomer>(BankCustomers)

    var name by BankCustomers.name
    var ebicsSubscriber by EbicsSubscriber referencedOn BankCustomers.ebicsSubscriber
}

/**
 * The following tables define IDs that make a EBCIS
 * 'subscriber' exist.  Each EBICS subscriber is the tuple:
 *
 * - UserID, the human who is performing a EBICS task.
 * - PartnerID, the legal entity that signed a formal agreement with the financial institution.
 * - SystemID, (optional) the machine that is handling the EBICS task on behalf of the UserID.
 */

object EbicsUsers: IntIdTable() {
    /* EBICS user ID in the string form. */
    val userId = varchar("userId", EBICS_USER_ID_MAX_LENGTH).primaryKey().nullable()

}

class EbicsUser(id: EntityID<Int>) : IntEntity(id){
    companion object : IntEntityClass<EbicsUser>(EbicsUsers) {
        override fun new(init: EbicsUser.() -> Unit): EbicsUser {
            var row = super.new(init)
            row.userId = "u${row.id}"
            return row
        }
    }
    var userId by EbicsUsers.userId
}

/**
 * Table for UserID.
 */
object EbicsPartners: IntIdTable() {
    val partnerId = varchar("partnerId", EBICS_PARTNER_ID_MAX_LENGTH).primaryKey().nullable()
}


class EbicsPartner(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsPartner>(EbicsPartners) {
        override fun new(init: EbicsPartner.() -> Unit): EbicsPartner {
            var row = super.new(init)
            row.partnerId = "p${row.id}"
            return row
        }
    }
    var partnerId by EbicsPartners.partnerId
}


/**
 * Table for UserID.
 */
object EbicsSystems: IntIdTable() {
    val systemId = EbicsPartners.varchar("systemId", EBICS_SYSTEM_ID_MAX_LENGTH).nullable()
}

class EbicsSystem(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsSystem>(EbicsSystems) {
        override fun new(init: EbicsSystem.() -> Unit): EbicsSystem {
            var row = super.new(init)
            row.systemId = "s${row.id}"
            return row
        }
    }

    var systemId by EbicsSystems.systemId
}

/**
 * This table stores RSA public keys.
 */
object EbicsPublicKeys: IntIdTable() {
    val modulus = binary("modulus", PUBLIC_KEY_MAX_MODULUS_LENGTH)
    val exponent = binary("exponent", PUBLIC_KEY_MAX_EXPONENT_LENGTH)
    val state = enumeration("state", KeyStates::class)
}


/**
 * Definition of a row in the keys table
 */
class EbicsPublicKey(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsPublicKey>(EbicsPublicKeys)
    var modulus by EbicsPublicKeys.modulus
    var exponent by EbicsPublicKeys.exponent
    var state by EbicsPublicKeys.state
}

/**
 * Subscribers table.  This table associates users with partners
 * and systems.  Each value can appear multiple times in the same column.
 */
object EbicsSubscribers: IntIdTable() {

    val userId = reference("userId", EbicsUsers)
    val partnerId = reference("partnerId", EbicsPartners)
    val systemId = reference("systemId", EbicsSystems)

    val signatureKey = reference("signatureKey", EbicsPublicKeys).nullable()
    val encryptionKey = reference("encryptionKey", EbicsPublicKeys).nullable()
    val authenticationKey = reference("authorizationKey", EbicsPublicKeys).nullable()

    val state = enumeration("state", SubscriberStates::class)
}

class EbicsSubscriber(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsSubscriber>(EbicsSubscribers)

    var userId by EbicsUser referencedOn EbicsSubscribers.userId
    var partnerId by EbicsPartner referencedOn EbicsSubscribers.partnerId
    var systemId by EbicsSystem referencedOn EbicsSubscribers.systemId

    var signatureKey by EbicsPublicKey optionalReferencedOn EbicsSubscribers.signatureKey
    var encryptionKey by EbicsPublicKey optionalReferencedOn EbicsSubscribers.encryptionKey
    var authenticationKey by EbicsPublicKey optionalReferencedOn EbicsSubscribers.authenticationKey
    var state by EbicsSubscribers.state
}

/**
 * Helper function that makes a new subscriber
 * @return new object
 */
fun createSubscriber() : EbicsSubscriber {

    return EbicsSubscriber.new {
        userId = EbicsUser.new {  }
        partnerId = EbicsPartner.new { }
        systemId = EbicsSystem.new {  }
        state = SubscriberStates.NEW
    }
}


/**
 * This table stores RSA private keys.
 */
object EbicsBankPrivateKeys: IntIdTable() {
    val modulus = binary("modulus", PRIVATE_KEY_MODULUS_LENGTH)
    val exponent = binary("exponent", PRIVATE_KEY_EXPONENT_LENGTH)
}

class EbicsBankPrivateKey(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsBankPrivateKey>(EbicsBankPrivateKeys)

    var modulus by EbicsBankPrivateKeys.modulus
    var exponent by EbicsBankPrivateKeys.exponent
}

fun dbCreateTables() {
    Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

    transaction {
        // addLogger(StdOutSqlLogger)

        SchemaUtils.create(
            BankCustomers,
            EbicsUsers,
            EbicsPartners,
            EbicsSystems,
            EbicsSubscribers,
            EbicsBankPrivateKeys
        )
    }
}
