package app.kepler.core.designsystem

/**
 * Pure rules of the cover cache, free of Android so they can be unit tested. A cover is cached per book, per
 * source (the jacket file, or the generated cover for the current palette) and per size bucket.
 */
object CoverKeys {
    /** Decode widths in px. A request is served from the smallest bucket at least as wide as it asks for. */
    val BUCKETS = intArrayOf(192, 384, 768)

    fun bucketFor(widthPx: Int): Int = BUCKETS.firstOrNull { it >= widthPx } ?: BUCKETS.last()

    /** Key of a decoded jacket. The path is part of it so a replaced cover file is never served stale. */
    fun jacket(bookId: Long, path: String, bucket: Int): String = "j|$bookId|$bucket|$path"

    /** Key of a generated cover; [style] identifies the palette it was drawn with. */
    fun generated(bookId: Long, style: String, bucket: Int): String = "g|$bookId|$bucket|$style"

    fun bookOf(key: String): Long? = key.split('|').getOrNull(1)?.toLongOrNull()

    /**
     * The order in which buckets are tried for a request of [bucket]: the exact one, then larger (downscaled
     * draws are sharp), then smaller (a soft frame beats an empty one while the right size decodes).
     */
    fun lookupOrder(bucket: Int): List<Int> {
        val larger = BUCKETS.filter { it > bucket }
        val smaller = BUCKETS.filter { it < bucket }.reversed()
        return listOf(bucket) + larger + smaller
    }

    /** Largest power-of-two sample size that keeps the decoded width at or above [targetWidth]. */
    fun sampleSize(sourceWidth: Int, targetWidth: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }
}

/**
 * A least-recently-used map bounded by the total of [sizeOf] (bytes for bitmaps). Not thread safe on its own;
 * [KpCovers] guards it. Kept free of `android.util.LruCache` so it runs in JVM unit tests.
 */
class ByteLru<K : Any, V : Any>(private val maxSize: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<K, V>(32, 0.75f, true)
    var size: Long = 0
        private set

    operator fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        val old = map.put(key, value)
        if (old != null) size -= sizeOf(old)
        size += sizeOf(value)
        trimTo(maxSize)
    }

    fun remove(key: K): V? = map.remove(key)?.also { size -= sizeOf(it) }

    fun keys(): List<K> = map.keys.toList()

    fun trimTo(limit: Long) {
        val it = map.entries.iterator()
        while (size > limit && it.hasNext()) {
            val e = it.next()
            size -= sizeOf(e.value)
            it.remove()
        }
    }

    fun clear() {
        map.clear()
        size = 0
    }
}
