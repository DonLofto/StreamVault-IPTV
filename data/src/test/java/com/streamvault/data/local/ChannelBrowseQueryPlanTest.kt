package com.streamvault.data.local

import com.google.common.truth.Truth.assertThat
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * L1: channel browse queries order by `number` within a provider/category. The v62->v63
 * migration adds `(provider_id, number)` and `(provider_id, category_id, number)` so the
 * ordering can be satisfied from an index instead of a sort. These tests run the real
 * browse query shapes against SQLite and assert the plan uses the index (no temp B-tree).
 */
class ChannelBrowseQueryPlanTest {

    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE channels (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    stream_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    logo_url TEXT,
                    group_title TEXT,
                    category_id INTEGER,
                    category_name TEXT,
                    stream_url TEXT NOT NULL,
                    epg_channel_id TEXT,
                    number INTEGER NOT NULL,
                    catch_up_supported INTEGER NOT NULL DEFAULT 0,
                    catch_up_days INTEGER NOT NULL DEFAULT 0,
                    catchUpSource TEXT,
                    provider_id INTEGER NOT NULL,
                    is_adult INTEGER NOT NULL DEFAULT 0,
                    is_user_protected INTEGER NOT NULL DEFAULT 0,
                    logical_group_id TEXT NOT NULL DEFAULT '',
                    error_count INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            statement.execute(
                "CREATE INDEX index_channels_provider_id_number ON channels(provider_id, number)"
            )
            statement.execute(
                "CREATE INDEX index_channels_provider_id_category_id_number ON channels(provider_id, category_id, number)"
            )
            statement.execute("ANALYZE")
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    private fun queryPlan(sql: String): String {
        connection.createStatement().use { statement ->
            statement.executeQuery("EXPLAIN QUERY PLAN $sql").use { result ->
                val builder = StringBuilder()
                while (result.next()) {
                    builder.append(result.getString(4)).append('\n')
                }
                return builder.toString()
            }
        }
    }

    @Test
    fun providerBrowse_usesProviderNumberIndexWithoutSort() {
        val plan = queryPlan(
            "SELECT id FROM channels WHERE provider_id = 1 ORDER BY number ASC"
        )
        assertThat(plan).contains("index_channels_provider_id_number")
        assertThat(plan).doesNotContain("USE TEMP B-TREE")
    }

    @Test
    fun categoryBrowse_usesProviderCategoryNumberIndexWithoutSort() {
        val plan = queryPlan(
            "SELECT id FROM channels WHERE provider_id = 1 AND category_id = 2 ORDER BY number ASC"
        )
        assertThat(plan).contains("index_channels_provider_id_category_id_number")
        assertThat(plan).doesNotContain("USE TEMP B-TREE")
    }

    @Test
    fun providerBrowseWithLimit_usesIndexAndNoSort() {
        val plan = queryPlan(
            "SELECT id FROM channels WHERE provider_id = 1 ORDER BY number ASC LIMIT 50"
        )
        assertThat(plan).contains("index_channels_provider_id_number")
        assertThat(plan).doesNotContain("USE TEMP B-TREE")
    }
}
