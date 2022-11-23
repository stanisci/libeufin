package tech.libeufin.nexus

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import withTestDatabase
import java.io.File

object MyTable : Table() {
    val col1 = text("col1")
    val col2 = text("col2")
    override val primaryKey = PrimaryKey(col1, col2)
}

class DBTest {
    @Test(expected = ExposedSQLException::class)
    fun sqlDslTest() {
        withTestDatabase {
            transaction {
                addLogger(StdOutSqlLogger)
                SchemaUtils.create(MyTable)
                MyTable.insert {
                    it[col1] = "foo"
                    it[col2] = "bar"
                }
                // should throw ExposedSQLException
                MyTable.insert {
                    it[col1] = "foo"
                    it[col2] = "bar"
                }
                MyTable.insert {  } // shouldn't it fail for non-NULL constraint violation?
            }
        }
    }

    @Test
    fun facadeConfigTest() {
        withTestDatabase {
            transaction {
                addLogger(StdOutSqlLogger)
                SchemaUtils.create(
                    FacadesTable,
                    FacadeStateTable,
                    NexusUsersTable
                )
                val user = NexusUserEntity.new {
                    username = "testuser"
                    passwordHash = "x"
                    superuser = true
                }
                val facade = FacadeEntity.new {
                    facadeName = "testfacade"
                    type = "any"
                    creator = user
                }
                FacadeStateEntity.new {
                    bankAccount = "b"
                    bankConnection = "b"
                    reserveTransferLevel = "any"
                    this.facade = facade
                    currency = "UNUSED"
                }
            }
        }
    }
}