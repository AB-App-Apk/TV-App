package com.example.tvdash

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.tvlink.HeldInputProtocol
import com.example.tvlink.RemotePressProtocol
import java.io.File

/**
 * Durable at-most-once journal for discrete Back/OK requests arriving over Wi-Fi or Bluetooth data.
 *
 * IN_PROGRESS is committed before the input callback runs. COMPLETED plus its wire result is committed
 * afterwards. If the process dies between those commits, a replay sees IN_PROGRESS and is refused:
 * this deliberately prefers a potentially missed confirmation over a duplicate TV action.
 *
 * Rows are retained indefinitely. Pruning them would make sufficiently old request IDs executable again.
 */
internal class DiscretePressJournal(
    context: Context,
    databasePath: String = File(context.applicationContext.noBackupFilesDir, DATABASE_NAME).absolutePath
) : SQLiteOpenHelper(
    context.applicationContext,
    databasePath,
    null,
    DATABASE_VERSION,
    PRESERVE_CORRUPTION_HANDLER
) {
    internal sealed class Claim {
        object Acquired : Claim()
        data class Completed(val result: String) : Claim()
        object InProgress : Claim()
        object PayloadConflict : Claim()
    }

    /**
     * Atomically reserves [requestId] for this exact key/action tuple.
     *
     * SQLite's write transaction serializes independent helper instances/processes. The PRIMARY KEY
     * is the final uniqueness constraint; there is no read-then-write race outside the transaction.
     */
    @Synchronized
    fun claim(requestId: String, key: String, action: String): Claim {
        validatePayload(requestId, key, action)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.query(
                TABLE_NAME,
                arrayOf(COLUMN_KEY, COLUMN_ACTION, COLUMN_STATE, COLUMN_RESULT),
                "$COLUMN_REQUEST_ID = ?",
                arrayOf(requestId),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    ExistingRow(
                        key = cursor.getString(0),
                        action = cursor.getString(1),
                        state = cursor.getString(2),
                        result = if (cursor.isNull(3)) null else cursor.getString(3)
                    )
                } else {
                    null
                }
            }

            val claim = if (existing == null) {
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put(COLUMN_REQUEST_ID, requestId)
                    put(COLUMN_KEY, key)
                    put(COLUMN_ACTION, action)
                    put(COLUMN_STATE, STATE_IN_PROGRESS)
                    putNull(COLUMN_RESULT)
                    put(COLUMN_CREATED_AT_MS, now)
                    put(COLUMN_UPDATED_AT_MS, now)
                }
                db.insertOrThrow(TABLE_NAME, null, values)
                Claim.Acquired
            } else if (existing.key != key || existing.action != action) {
                Claim.PayloadConflict
            } else {
                when (existing.state) {
                    STATE_IN_PROGRESS -> Claim.InProgress
                    STATE_COMPLETED -> Claim.Completed(
                        existing.result ?: throw IllegalStateException("Completed journal row has no result")
                    )
                    else -> throw IllegalStateException("Unknown press journal state: ${existing.state}")
                }
            }

            db.setTransactionSuccessful()
            return claim
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Commits the terminal result for a previously acquired request.
     *
     * The conditional UPDATE is a compare-and-set: it cannot overwrite a completed row or a row
     * belonging to a different payload. FULL synchronous mode makes the transaction durable before
     * this method returns successfully.
     */
    @Synchronized
    fun complete(requestId: String, key: String, action: String, result: String): Boolean {
        validatePayload(requestId, key, action)
        require(result.matches(Regex("[a-z0-9-]{1,64}"))) { "Invalid press result token" }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(COLUMN_STATE, STATE_COMPLETED)
                put(COLUMN_RESULT, result)
                put(COLUMN_UPDATED_AT_MS, System.currentTimeMillis())
            }
            val updated = db.update(
                TABLE_NAME,
                values,
                "$COLUMN_REQUEST_ID = ? AND $COLUMN_KEY = ? AND $COLUMN_ACTION = ? AND $COLUMN_STATE = ?",
                arrayOf(requestId, key, action, STATE_IN_PROGRESS)
            )
            if (updated == 1) db.setTransactionSuccessful()
            return updated == 1
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Executes an action at most once for this durable request ID.
     *
     * A journal/read failure prevents execution. An action exception or completion-commit failure is
     * UNCERTAIN because the callback may already have caused an external side effect.
     */
    @Synchronized
    fun executeOnce(
        requestId: String,
        key: String,
        action: String,
        perform: () -> String
    ): String {
        val reservation = try {
            claim(requestId, key, action)
        } catch (e: Exception) {
            Log.e(TAG, "press journal reservation failed; refusing input dispatch", e)
            return RemotePressProtocol.RESULT_JOURNAL_UNAVAILABLE
        }

        when (reservation) {
            Claim.Acquired -> {
                Log.i(TAG, "press-journal state=IN_PROGRESS requestId=$requestId key=$key action=$action")
            }
            is Claim.Completed -> {
                Log.i(TAG, "press-journal replay=COMPLETED requestId=$requestId result=${reservation.result}")
                return reservation.result
            }
            Claim.InProgress -> {
                Log.w(TAG, "press-journal replay=IN_PROGRESS requestId=$requestId; side effect not repeated")
                return RemotePressProtocol.RESULT_UNCERTAIN
            }
            Claim.PayloadConflict -> {
                Log.e(TAG, "press-journal replay=PAYLOAD_CONFLICT requestId=$requestId; side effect not repeated")
                return RemotePressProtocol.RESULT_ID_CONFLICT
            }
        }

        val result = try {
            perform()
        } catch (e: Exception) {
            Log.e(TAG, "press callback failed after durable reservation; request remains IN_PROGRESS", e)
            return RemotePressProtocol.RESULT_UNCERTAIN
        }

        val committed = try {
            complete(requestId, key, action, result)
        } catch (e: Exception) {
            Log.e(TAG, "press completion commit failed; request remains fail-closed", e)
            false
        }
        if (!committed) {
            Log.e(TAG, "press completion compare-and-set failed; request remains fail-closed requestId=$requestId")
            return RemotePressProtocol.RESULT_UNCERTAIN
        }

        Log.i(TAG, "press-journal state=COMPLETED requestId=$requestId result=$result")
        return result
    }

    private data class ExistingRow(
        val key: String,
        val action: String,
        val state: String,
        val result: String?
    )

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        // Rollback journal + FULL synchronous commit: persist each state transition before returning.
        db.execSQL("PRAGMA synchronous=FULL")
        db.execSQL("PRAGMA busy_timeout=5000")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_REQUEST_ID TEXT NOT NULL PRIMARY KEY,
                $COLUMN_KEY TEXT NOT NULL CHECK ($COLUMN_KEY IN ('back', 'ok')),
                $COLUMN_ACTION TEXT NOT NULL CHECK ($COLUMN_ACTION IN ('tap', 'long')),
                $COLUMN_STATE TEXT NOT NULL CHECK ($COLUMN_STATE IN ('$STATE_IN_PROGRESS', '$STATE_COMPLETED')),
                $COLUMN_RESULT TEXT,
                $COLUMN_CREATED_AT_MS INTEGER NOT NULL,
                $COLUMN_UPDATED_AT_MS INTEGER NOT NULL,
                CHECK (
                    ($COLUMN_STATE = '$STATE_IN_PROGRESS' AND $COLUMN_RESULT IS NULL) OR
                    ($COLUMN_STATE = '$STATE_COMPLETED' AND $COLUMN_RESULT IS NOT NULL)
                )
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never drop an idempotency journal during an upgrade: that could make an old request executable again.
        throw IllegalStateException("No safe press journal migration from $oldVersion to $newVersion")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Press journal downgrade is not supported: $oldVersion to $newVersion")
    }

    private fun validatePayload(requestId: String, key: String, action: String) {
        require(HeldInputProtocol.isValidHoldId(requestId)) { "Invalid press request ID" }
        require(key == "back" || key == "ok") { "Invalid discrete press key" }
        require(action == RemotePressProtocol.ACTION_TAP || action == RemotePressProtocol.ACTION_LONG) {
            "Invalid discrete press action"
        }
    }

    companion object {
        private const val TAG = "TvDashPressJournal"
        private const val DATABASE_NAME = "discrete_press_journal.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_NAME = "discrete_press"
        private const val COLUMN_REQUEST_ID = "request_id"
        private const val COLUMN_KEY = "key_name"
        private const val COLUMN_ACTION = "action"
        private const val COLUMN_STATE = "state"
        private const val COLUMN_RESULT = "result"
        private const val COLUMN_CREATED_AT_MS = "created_at_ms"
        private const val COLUMN_UPDATED_AT_MS = "updated_at_ms"

        private const val STATE_IN_PROGRESS = "IN_PROGRESS"
        private const val STATE_COMPLETED = "COMPLETED"

        /**
         * SQLite's default corruption handler deletes the damaged database. That is unsafe here:
         * losing records can make a previously executed request runnable again. Preserve the file
         * and allow subsequent database operations to fail closed instead.
         */
        private val PRESERVE_CORRUPTION_HANDLER = DatabaseErrorHandler { db ->
            Log.e(TAG, "press journal corruption detected; preserving database instead of deleting it")
        }
    }
}
