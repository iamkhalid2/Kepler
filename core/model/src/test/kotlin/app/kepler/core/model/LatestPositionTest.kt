package app.kepler.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestPositionTest {
    private fun repo(listen: Position?, read: Position?) = object : PositionRepository {
        override fun position(bookId: BookId, kind: PositionKind): Flow<Position?> =
            flowOf(if (kind == PositionKind.LISTENING) listen else read)
        override suspend fun savePosition(bookId: BookId, kind: PositionKind, ordinal: Int) = Unit
        override suspend fun markConsumed(bookId: BookId, fromOrdinal: Int, toOrdinal: Int, via: PositionKind) = Unit
        override suspend fun frontier(bookId: BookId) = -1
    }

    private fun p(kind: PositionKind, ordinal: Int, at: Long) = Position(1, kind, ordinal, at)

    @Test fun readingAfterListeningWins() = runTest {
        val r = repo(p(PositionKind.LISTENING, 10, 100), p(PositionKind.READING, 40, 200))
        assertEquals(40, r.latestPosition(1, PositionKind.LISTENING)?.ordinal)
    }

    @Test fun listeningAfterReadingWins() = runTest {
        val r = repo(p(PositionKind.LISTENING, 55, 300), p(PositionKind.READING, 40, 200))
        assertEquals(55, r.latestPosition(1, PositionKind.READING)?.ordinal)
    }

    @Test fun tieGoesToPreferredKind() = runTest {
        val r = repo(p(PositionKind.LISTENING, 5, 100), p(PositionKind.READING, 9, 100))
        assertEquals(5, r.latestPosition(1, PositionKind.LISTENING)?.ordinal)
        assertEquals(9, r.latestPosition(1, PositionKind.READING)?.ordinal)
    }

    @Test fun singleOrNone() = runTest {
        assertEquals(7, repo(null, p(PositionKind.READING, 7, 1)).latestPosition(1, PositionKind.LISTENING)?.ordinal)
        assertNull(repo(null, null).latestPosition(1, PositionKind.LISTENING))
    }
}
