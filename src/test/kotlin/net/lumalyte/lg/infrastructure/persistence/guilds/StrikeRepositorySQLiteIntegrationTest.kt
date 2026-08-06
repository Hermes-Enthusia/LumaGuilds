package net.lumalyte.lg.infrastructure.persistence.guilds

import co.aikar.idb.DbRow
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import net.lumalyte.lg.domain.entities.GuildStrike
import net.lumalyte.lg.infrastructure.persistence.storage.VirtualThreadSQLiteStorage

/**
 * Regression test for the SQLite numeric-type bug: idb's `DbRow.getLong()` /
 * `getInt()` hard-cast the driver value (`(Long) value`), but the SQLite JDBC
 * driver returns `java.lang.Integer` for INTEGER columns. Every strike row then
 * failed `toStrike()` with a ClassCastException, so `/g strikes <guild>` always
 * showed "no strikes" even though the global list counted them.
 *
 * This test drives the REAL repository against a REAL SQLite file (same storage
 * class the plugin uses) and asserts both the detail view and the count views
 * read the rows back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StrikeRepositorySQLiteIntegrationTest {

    private lateinit var storage: VirtualThreadSQLiteStorage
    private lateinit var repository: StrikeRepositorySQLite

    private val guildId = UUID.randomUUID()
    private val playerUuid = UUID.randomUUID()

    @BeforeAll
    fun setUp() {
        val dataFolder = Files.createTempDirectory("lumaguilds-strikes-test").toFile()
        storage = VirtualThreadSQLiteStorage(dataFolder)
        repository = StrikeRepositorySQLite(storage)

        storage.connection.executeUpdate(
            """
            CREATE TABLE guild_strikes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                guild_id TEXT NOT NULL,
                player_uuid TEXT NOT NULL,
                player_name TEXT,
                punishment_type TEXT NOT NULL,
                reason TEXT,
                executor_name TEXT,
                issued_at INTEGER NOT NULL,
                litebans_entry_id INTEGER,
                active INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )

        // Insert rows exactly as the production code does (guildId/playerUuid as
        // lowercase UUID strings, INTEGER timestamps).
        for (i in 1L..3L) {
            repository.recordStrike(
                GuildStrike(
                    id = i,
                    guildId = guildId,
                    playerUuid = playerUuid,
                    playerName = "Steve",
                    punishmentType = "BAN",
                    reason = "griefing",
                    executorName = "Mod",
                    issuedAt = Instant.parse("2026-08-01T12:00:00Z").plusSeconds(i),
                    litebansEntryId = 100L + i,
                    active = true
                )
            )
        }
    }

    @AfterAll
    fun tearDown() {
        storage.connection.close()
    }

    @Test
    fun `getByGuild returns rows despite SQLite Integer columns`() {
        val strikes = repository.getByGuild(guildId)

        assertEquals(3, strikes.size, "All three strikes should parse and load")
        assertEquals(playerUuid, strikes.first().playerUuid)
        assertEquals("BAN", strikes.first().punishmentType)
        // Newest first per ORDER BY issued_at DESC
        assertEquals(103L, strikes.first().litebansEntryId)
        assertTrue(strikes.all { it.active })
        assertEquals(setOf(101L, 102L, 103L), strikes.map { it.litebansEntryId }.toSet())
    }

    @Test
    fun `count views agree with detail view`() {
        assertEquals(3, repository.countByGuild(guildId))
        assertEquals(3, repository.countActiveByGuild(guildId))
        assertEquals(3, repository.countAll())
        assertEquals(3, repository.getAllCounts()[guildId])
        assertEquals(3, repository.getAllActiveCounts()[guildId])
    }

    @Test
    fun `dedupe by type and entry id still holds`() {
        // Re-inserting the same (type, entryId) must not double-count.
        val inserted = repository.recordStrike(
            GuildStrike(
                id = 99L,
                guildId = guildId,
                playerUuid = playerUuid,
                playerName = "Steve",
                punishmentType = "BAN",
                reason = "duplicate",
                executorName = "Mod",
                issuedAt = Instant.parse("2026-08-02T12:00:00Z"),
                litebansEntryId = 101L,
                active = true
            )
        )
        assertEquals(false, inserted)
        assertEquals(3, repository.countAll())
    }
}
