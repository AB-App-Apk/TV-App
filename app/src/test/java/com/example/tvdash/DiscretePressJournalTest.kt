package com.example.tvdash

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.tvlink.RemotePressProtocol
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSQLiteConnection
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiscretePressJournalTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var database: File
    private lateinit var journal: DiscretePressJournal

    @Before
    fun setUp() {
        // Match Android 14 defaults before any test database connection is opened.
        ShadowSQLiteConnection.setDefaultJournalMode("TRUNCATE")
        ShadowSQLiteConnection.setDefaultSyncMode("FULL")

        context = RuntimeEnvironment.getApplication()
        directory = File(context.cacheDir, "press-journal-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        database = File(directory, "journal.db")
        journal = DiscretePressJournal(context, database.absolutePath)
    }

    @After
    fun tearDown() {
        if (::journal.isInitialized) journal.close()
        directory.deleteRecursively()
    }


    @Test
    fun robolectricSqliteDefaultsAndJournalMatchAndroid14Modes() {
        // Verify Robolectric's configured defaults independently of the app's onConfigure hook.
        val defaultsDatabase = SQLiteDatabase.openOrCreateDatabase(
            File(directory, "robolectric-defaults.db"),
            null
        )
        try {
            val defaultJournalMode = defaultsDatabase.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                check(cursor.moveToFirst()) { "SQLite did not report its default journal mode" }
                cursor.getString(0)
            }
            val defaultSynchronous = defaultsDatabase.rawQuery("PRAGMA synchronous", null).use { cursor ->
                check(cursor.moveToFirst()) { "SQLite did not report its default synchronous mode" }
                cursor.getInt(0)
            }
            assertEquals("TRUNCATE", defaultJournalMode.uppercase())
            assertEquals(2, defaultSynchronous) // SQLite FULL
        } finally {
            defaultsDatabase.close()
        }

        // Verify the effective modes on the actual durable press journal connection.
        val journalDatabase = journal.writableDatabase
        val effectiveJournalMode = journalDatabase.rawQuery("PRAGMA journal_mode", null).use { cursor ->
            check(cursor.moveToFirst()) { "Press journal did not report its journal mode" }
            cursor.getString(0)
        }
        val effectiveSynchronous = journalDatabase.rawQuery("PRAGMA synchronous", null).use { cursor ->
            check(cursor.moveToFirst()) { "Press journal did not report its synchronous mode" }
            cursor.getInt(0)
        }

        assertEquals("TRUNCATE", effectiveJournalMode.uppercase())
        assertEquals(2, effectiveSynchronous) // onConfigure must retain FULL durability
    }

    @Test
    fun completedRequestReplayedAfterJournalReopenDoesNotRepeatSideEffect() {
        val id = UUID.randomUUID().toString()
        var sideEffectCount = 0

        val first = journal.executeOnce(id, "ok", RemotePressProtocol.ACTION_LONG) {
            sideEffectCount++
            "ok"
        }
        journal.close()

        journal = DiscretePressJournal(context, database.absolutePath)
        val replay = journal.executeOnce(id, "ok", RemotePressProtocol.ACTION_LONG) {
            sideEffectCount++
            "ok"
        }

        assertEquals("ok", first)
        assertEquals("ok", replay)
        assertEquals("same-ID Wi-Fi/Bluetooth replay must not execute twice", 1, sideEffectCount)
    }

    @Test
    fun processDeathAfterSideEffectButBeforeCompletionLeavesInProgressAndBlocksReplay() {
        val id = UUID.randomUUID().toString()
        var sideEffectCount = 0

        assertEquals(
            DiscretePressJournal.Claim.Acquired,
            journal.claim(id, "ok", RemotePressProtocol.ACTION_LONG)
        )
        // Model the external input side effect completing, followed immediately by process death.
        sideEffectCount++
        journal.close()

        journal = DiscretePressJournal(context, database.absolutePath)
        val replay = journal.executeOnce(id, "ok", RemotePressProtocol.ACTION_LONG) {
            sideEffectCount++
            "ok"
        }

        assertEquals(RemotePressProtocol.RESULT_UNCERTAIN, replay)
        assertEquals("an IN_PROGRESS replay must never re-dispatch", 1, sideEffectCount)
    }

    @Test
    fun processDeathAfterReservationBeforeSideEffectDoesNotExecuteOnReplay() {
        val id = UUID.randomUUID().toString()
        assertEquals(
            DiscretePressJournal.Claim.Acquired,
            journal.claim(id, "ok", RemotePressProtocol.ACTION_LONG)
        )
        journal.close()

        journal = DiscretePressJournal(context, database.absolutePath)
        var sideEffectCount = 0
        val replay = journal.executeOnce(id, "ok", RemotePressProtocol.ACTION_LONG) {
            sideEffectCount++
            "ok"
        }

        assertEquals(RemotePressProtocol.RESULT_UNCERTAIN, replay)
        assertEquals(0, sideEffectCount)
    }

    @Test
    fun sameRequestIdWithDifferentPayloadIsRejected() {
        val id = UUID.randomUUID().toString()
        assertEquals(
            DiscretePressJournal.Claim.Acquired,
            journal.claim(id, "ok", RemotePressProtocol.ACTION_LONG)
        )

        assertEquals(
            DiscretePressJournal.Claim.PayloadConflict,
            journal.claim(id, "back", RemotePressProtocol.ACTION_LONG)
        )
    }

    @Test
    fun independentJournalInstancesCannotClaimTheSameRequestConcurrently() {
        val id = UUID.randomUUID().toString()
        val secondJournal = DiscretePressJournal(context, database.absolutePath)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val firstResult = AtomicReference<String>()
        var firstSideEffectCount = 0
        var secondSideEffectCount = 0

        val firstThread = Thread {
            firstResult.set(
                journal.executeOnce(id, "ok", RemotePressProtocol.ACTION_LONG) {
                    firstSideEffectCount++
                    callbackEntered.countDown()
                    check(releaseCallback.await(5, TimeUnit.SECONDS))
                    "ok"
                }
            )
        }
        firstThread.start()
        assertTrue("first callback did not start", callbackEntered.await(5, TimeUnit.SECONDS))

        val secondResult = secondJournal.executeOnce(id, "ok", RemotePressProtocol.ACTION_LONG) {
            secondSideEffectCount++
            "ok"
        }

        releaseCallback.countDown()
        firstThread.join(5_000)
        secondJournal.close()

        assertEquals("ok", firstResult.get())
        assertEquals(RemotePressProtocol.RESULT_UNCERTAIN, secondResult)
        assertEquals(1, firstSideEffectCount)
        assertEquals(0, secondSideEffectCount)
    }

    @Test
    fun completedTransitionCannotBeOverwritten() {
        val id = UUID.randomUUID().toString()
        assertEquals(
            DiscretePressJournal.Claim.Acquired,
            journal.claim(id, "ok", RemotePressProtocol.ACTION_LONG)
        )
        assertTrue(journal.complete(id, "ok", RemotePressProtocol.ACTION_LONG, "ok"))
        assertEquals(
            DiscretePressJournal.Claim.Completed("ok"),
            journal.claim(id, "ok", RemotePressProtocol.ACTION_LONG)
        )
        assertTrue(!journal.complete(id, "ok", RemotePressProtocol.ACTION_LONG, "action-failed"))
    }
}
