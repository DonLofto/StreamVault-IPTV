package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ProviderType
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A54 - evidence for the decision the card leaves open.
 *
 * The grouped category counts re-run on every `channels` invalidation and are expensive for two
 * reasons: the aggregate cannot use an index, so every row of the provider is visited, and
 * `CAST(id AS TEXT)` allocates a string per row. The card offers debouncing (which does not stop the
 * SQL running - Room re-executes on invalidation, before any downstream operator sees it) or
 * maintaining counts incrementally.
 *
 * There is a third option the card does not mention: an **expression index** over exactly the counted
 * expression. SQLite would then walk rows already grouped by category and already sorted by the count
 * key, turning COUNT(DISTINCT) into a run-length count with no per-row cast and no ephemeral B-tree.
 * The engine maintains the index, so unlike a trigger-maintained count table it cannot drift.
 *
 * This test does not guess: it prints the query plan before and after creating the index, so the
 * decision can be made on what SQLite actually does.
 */
@RunWith(AndroidJUnit4::class)
class CategoryCountPlanTest {

    private lateinit var db: StreamVaultDatabase

    private val countedExpression =
        "CASE WHEN logical_group_id IS NOT NULL AND logical_group_id != '' " +
            "THEN logical_group_id ELSE CAST(id AS TEXT) END"

    private val groupedCountQuery = """
        SELECT category_id, COUNT(DISTINCT $countedExpression) AS item_count
        FROM channels
        WHERE provider_id = ? AND category_id IS NOT NULL
        GROUP BY category_id
    """.trimIndent()

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    private fun plan(label: String): List<String> {
        val rows = mutableListOf<String>()
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $groupedCountQuery", arrayOf(1L)).use { cursor ->
            while (cursor.moveToNext()) {
                val detail = cursor.getColumnIndex("detail")
                rows += if (detail >= 0) cursor.getString(detail) else cursor.getString(3)
            }
        }
        // Log rather than println: instrumentation stdout is not forwarded to the Gradle log.
        android.util.Log.i("A54Plan", "$label -> $rows")
        return rows
    }

    @Test
    fun expressionIndexChangesThePlanForTheGroupedCount() = runTest {
        db.providerDao().insert(
            ProviderEntity(
                id = 1L,
                name = "Provider",
                type = ProviderType.M3U,
                serverUrl = "https://provider.example.test"
            )
        )
        db.channelDao().insertAll(
            (0 until 500).map { index ->
                ChannelEntity(
                    streamId = index.toLong() + 1L,
                    name = "Channel $index",
                    categoryId = (index % 20).toLong() + 1L,
                    logicalGroupId = if (index % 3 == 0) "group-${index % 40}" else "",
                    providerId = 1L,
                    number = index
                )
            }
        )

        val before = plan("without index")

        db.openHelper.writableDatabase.execSQL(
            "CREATE INDEX IF NOT EXISTS index_channels_a54_grouped_count " +
                "ON channels(provider_id, category_id, $countedExpression)"
        )

        val after = plan("with expression index")

        // Recorded rather than asserted: the point of this test is to report what SQLite does, and a
        // plan that does not use the index is a legitimate result that decides against this option.
        android.util.Log.i("A54Plan", "final before=$before")
        android.util.Log.i("A54Plan", "final after=$after")
    }
}
