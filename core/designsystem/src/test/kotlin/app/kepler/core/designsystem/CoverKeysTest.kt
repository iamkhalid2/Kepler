package app.kepler.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverKeysTest {
    @Test fun bucketsRoundUpAndCap() {
        assertEquals(192, CoverKeys.bucketFor(1))
        assertEquals(192, CoverKeys.bucketFor(192))
        assertEquals(384, CoverKeys.bucketFor(193))
        assertEquals(768, CoverKeys.bucketFor(700))
        assertEquals(768, CoverKeys.bucketFor(5000))
    }

    @Test fun keysSeparateBooksSourcesAndSizes() {
        val a = CoverKeys.jacket(1, "/c/1.jpg", 384)
        assertNotEquals(a, CoverKeys.jacket(2, "/c/1.jpg", 384))
        assertNotEquals(a, CoverKeys.jacket(1, "/c/1b.jpg", 384))
        assertNotEquals(a, CoverKeys.jacket(1, "/c/1.jpg", 768))
        assertNotEquals(a, CoverKeys.generated(1, "style", 384))
        assertNotEquals(CoverKeys.generated(1, "dark", 384), CoverKeys.generated(1, "light", 384))
    }

    @Test fun bookIdRoundTripsThroughKeysEvenWithPipesInPath() {
        assertEquals(42L, CoverKeys.bookOf(CoverKeys.jacket(42, "/odd|path/c.png", 192)))
        assertEquals(7L, CoverKeys.bookOf(CoverKeys.generated(7, "s", 768)))
        assertNull(CoverKeys.bookOf("garbage"))
    }

    @Test fun lookupPrefersExactThenLargerThenSmaller() {
        assertEquals(listOf(384, 768, 192), CoverKeys.lookupOrder(384))
        assertEquals(listOf(192, 384, 768), CoverKeys.lookupOrder(192))
        assertEquals(listOf(768, 384, 192), CoverKeys.lookupOrder(768))
    }

    @Test fun sampleSizeNeverDecodesNarrowerThanTarget() {
        assertEquals(1, CoverKeys.sampleSize(400, 384))
        assertEquals(2, CoverKeys.sampleSize(800, 384))
        assertEquals(4, CoverKeys.sampleSize(1600, 384))
        assertEquals(2, CoverKeys.sampleSize(1500, 384))
        assertEquals(1, CoverKeys.sampleSize(100, 384))
    }
}

class ByteLruTest {
    @Test fun evictsLeastRecentlyUsedBySize() {
        val lru = ByteLru<String, ByteArray>(10) { it.size.toLong() }
        lru.put("a", ByteArray(4))
        lru.put("b", ByteArray(4))
        lru["a"] // a is now the most recent
        lru.put("c", ByteArray(4))
        assertNull(lru["b"])
        assertEquals(8L, lru.size)
        assertEquals(listOf("a", "c"), lru.keys())
    }

    @Test fun replacingAKeyAdjustsSize() {
        val lru = ByteLru<String, ByteArray>(100) { it.size.toLong() }
        lru.put("a", ByteArray(10))
        lru.put("a", ByteArray(3))
        assertEquals(3L, lru.size)
        lru.remove("a")
        assertEquals(0L, lru.size)
    }

    @Test fun trimKeepsMostRecent() {
        val lru = ByteLru<Int, ByteArray>(100) { it.size.toLong() }
        for (i in 0 until 10) lru.put(i, ByteArray(10))
        lru.trimTo(30)
        assertEquals(listOf(7, 8, 9), lru.keys())
    }
}
