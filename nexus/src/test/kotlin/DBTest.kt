package tech.libeufin.nexus

import kotlinx.coroutines.*
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.ExposedConnection
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.transactionManager
import org.junit.Test
import org.postgresql.PGConnection
import org.postgresql.jdbc.PgConnection
import tech.libeufin.util.PostgresListenNotify
import withTestDatabase
import java.sql.Connection
import java.sql.DriverManager

class DBTest {

    // Testing database notifications (only postgresql)
    @Test
    fun notifications() {
        val genCon = DriverManager.getConnection("jdbc:postgresql://localhost:5432/talercheck?user=job")
        val pgCon = genCon.unwrap(org.postgresql.jdbc.PgConnection::class.java)
        val ln = PostgresListenNotify(pgCon, "x")
        ln.postrgesListen()
        ln.postgresNotify()
        runBlocking { ln.postgresWaitNotification(2000L) }
    }
}