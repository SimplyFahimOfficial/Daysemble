package app.daysemble.core.time

/**
 * Side-by-side layout for overlapping timed events in a day/week column.
 * Events are grouped into clusters of transitively overlapping items; each cluster is split into
 * the minimum number of columns found greedily, and every event gets 1/columns of the width.
 * An event may expand right into adjacent columns that are free for its whole duration.
 */
object DayLayout {

    data class Item<K>(val key: K, val startMin: Int, val endMin: Int)

    data class Placed<K>(val key: K, val startMin: Int, val endMin: Int, val column: Int, val span: Int, val columns: Int) {
        val leftFraction: Float get() = column.toFloat() / columns
        val widthFraction: Float get() = span.toFloat() / columns
    }

    /** Minimum visual height, so very short events stay tappable and legible. */
    const val MIN_DURATION_MIN = 20
    private const val DAY_MIN = 24 * 60

    fun <K> layout(items: List<Item<K>>): List<Placed<K>> {
        if (items.isEmpty()) return emptyList()
        // The minimum length must not push a late event (23:50) past the end of the day.
        val sorted = items.map {
            val start = minOf(it.startMin, DAY_MIN - MIN_DURATION_MIN)
            it.copy(startMin = start, endMin = minOf(DAY_MIN, maxOf(it.endMin, start + MIN_DURATION_MIN)))
        }
            .sortedWith(compareBy<Item<K>> { it.startMin }.thenByDescending { it.endMin })
        val result = ArrayList<Placed<K>>(items.size)
        var cluster = ArrayList<Item<K>>()
        var clusterEnd = Int.MIN_VALUE
        for (it in sorted) {
            if (cluster.isNotEmpty() && it.startMin >= clusterEnd) {
                result += placeCluster(cluster); cluster = ArrayList(); clusterEnd = Int.MIN_VALUE
            }
            cluster += it
            clusterEnd = maxOf(clusterEnd, it.endMin)
        }
        if (cluster.isNotEmpty()) result += placeCluster(cluster)
        return result
    }

    private fun <K> placeCluster(cluster: List<Item<K>>): List<Placed<K>> {
        val columnsEnd = ArrayList<Int>()
        val colOf = IntArray(cluster.size)
        cluster.forEachIndexed { i, it ->
            val c = columnsEnd.indexOfFirst { end -> end <= it.startMin }
            if (c >= 0) { columnsEnd[c] = it.endMin; colOf[i] = c } else { columnsEnd += it.endMin; colOf[i] = columnsEnd.size - 1 }
        }
        val n = columnsEnd.size
        return cluster.mapIndexed { i, it ->
            var span = 1
            while (colOf[i] + span < n) {
                val next = colOf[i] + span
                val blocked = cluster.indices.any { j -> colOf[j] == next && cluster[j].startMin < it.endMin && cluster[j].endMin > it.startMin }
                if (blocked) break
                span++
            }
            Placed(it.key, it.startMin, it.endMin, colOf[i], span, n)
        }
    }
}
