package tech.libeufin.nexus

import org.junit.Before
import org.junit.Test

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import tech.libeufin.util.Amount
import javax.sql.rowset.serial.SerialBlob


class DbTest {

    @Before
    fun connectAndMakeTables() {
        Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(EbicsSubscribersTable)
            SchemaUtils.create(Pain001Table)
        }
    }

    @Test
    fun makeEbicsSubscriber() {
        transaction {
            EbicsSubscriberEntity.new {
                ebicsURL = "ebics url"
                hostID = "host"
                partnerID = "partner"
                userID = "user"
                systemID = "system"
                signaturePrivateKey = SerialBlob("signturePrivateKey".toByteArray())
                authenticationPrivateKey = SerialBlob("authenticationPrivateKey".toByteArray())
                encryptionPrivateKey = SerialBlob("encryptionPrivateKey".toByteArray())
            }
        }
    }

    @Test
    fun testPain001() {
        createPain001entity(
            Pain001Data(
                creditorBic = "cb",
                creditorIban = "ci",
                creditorName = "cn",
                sum = Amount(2),
                subject = "s"
            ),
            "debtor acctid"
        )
    }
}