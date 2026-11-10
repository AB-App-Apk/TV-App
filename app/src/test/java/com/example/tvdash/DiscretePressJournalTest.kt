package com.example.tvdash

import android.content.Context
import com.example.tvlink.RemotePressProtocol
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    fun failedCompletionCommitCannotMakeRequestExecutableAgain() {
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
