package io.ymusic.desktop

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kmpcommon.log.Logger
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * The desktop's small durable store, `app.db`: settings, and [secret]s — the sealed cookie jar and
 * the OS key material it is sealed with. SQLite commits are atomic and synced (`synchronous=FULL`),
 * so a crash or power loss leaves the previous value, never an empty or torn one.
 *
 * Blocking: call it off the main thread.
 */
internal class AppStore private constructor(private val driver: SqlDriver) {

    private val transacter = object : TransacterImpl(driver) {}

    fun settings(): Map<String, String> = driver.executeQuery(
        null,
        "SELECT key, value FROM setting",
        { cursor ->
            val values = LinkedHashMap<String, String>()
            while (cursor.next().value) values[cursor.getString(0)!!] = cursor.getString(1)!!
            QueryResult.Value(values)
        },
        0,
    ).value

    fun putSettings(values: Map<String, String>) = transacter.transaction {
        for ((key, value) in values) {
            driver.execute(null, "INSERT OR REPLACE INTO setting(key, value) VALUES (?, ?)", 2) {
                bindString(0, key)
                bindString(1, value)
            }
        }
    }

    fun secret(name: String): ByteArray? = driver.executeQuery(
        null,
        "SELECT value FROM secret WHERE name = ?",
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getBytes(0) else null) },
        1,
    ) { bindString(0, name) }.value

    fun putSecret(name: String, value: ByteArray) {
        driver.execute(null, "INSERT OR REPLACE INTO secret(name, value) VALUES (?, ?)", 2) {
            bindString(0, name)
            bindBytes(1, value)
        }
    }

    /** [value] unless [name] already holds one — then that one. Two first launches agree on one key. */
    fun secretOrPut(name: String, value: ByteArray): ByteArray {
        driver.execute(null, "INSERT OR IGNORE INTO secret(name, value) VALUES (?, ?)", 2) {
            bindString(0, name)
            bindBytes(1, value)
        }
        return checkNotNull(secret(name))
    }

    fun deleteSecret(name: String) {
        driver.execute(null, "DELETE FROM secret WHERE name = ?", 1) { bindString(0, name) }
    }

    /** One-time import of what 4.0.6 and earlier kept as loose files in [dataDir]; each file goes
     *  once its rows commit. Rows already here win: a crash between commit and delete re-imports. */
    private fun importLegacyFiles(dataDir: File, log: Logger?) {
        val settingsFile = File(dataDir, LEGACY_SETTINGS)
        if (!settingsFile.exists()) return
        val settings = try {
            Properties().apply { settingsFile.inputStream().use(::load) }.entries.associate { (key, value) -> key.toString() to value.toString() }
        } catch (error: Exception) {
            log?.log(Logger.Level.WARN, "legacy $LEGACY_SETTINGS unreadable, starting on defaults", error)
            emptyMap()
        }
        transacter.transaction {
            for ((key, value) in settings) {
                driver.execute(null, "INSERT OR IGNORE INTO setting(key, value) VALUES (?, ?)", 2) {
                    bindString(0, key)
                    bindString(1, value)
                }
            }
        }
        settingsFile.delete()
        log?.log(Logger.Level.INFO, "imported legacy $LEGACY_SETTINGS into $DB_FILE")
    }

    companion object {
        private const val DB_FILE = "app.db"
        private const val LEGACY_SETTINGS = "settings.properties"

        fun open(dataDir: File, log: Logger?): AppStore {
            val file = File(dataDir.apply { mkdirs() }, DB_FILE)
            createOwnerOnly(file)
            val properties = Properties().apply {
                setProperty("journal_mode", "WAL")
                setProperty("synchronous", "FULL")
                setProperty("busy_timeout", "5000")
            }
            return AppStore(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", properties, Schema)).also {
                it.importLegacyFiles(dataDir, log)
            }
        }

        /** Owner read/write only where the OS has the notion; SQLite gives its -wal/-shm the same mode. */
        private fun createOwnerOnly(file: File) {
            if (file.exists()) return
            try {
                Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } catch (_: UnsupportedOperationException) {
                // Windows: no POSIX modes; the user's profile folder is already private.
            } catch (_: FileAlreadyExistsException) {
                // Another launch made it first: same file, same mode.
            }
        }
    }

    private object Schema : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long get() = 1

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE setting (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)", 0)
            driver.execute(null, "CREATE TABLE secret (name TEXT NOT NULL PRIMARY KEY, value BLOB NOT NULL)", 0)
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }
}
