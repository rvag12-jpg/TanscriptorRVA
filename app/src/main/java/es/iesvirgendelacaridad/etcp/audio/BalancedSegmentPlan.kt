package es.iesvirgendelacaridad.etcp.audio

/** Balanced partitions at packet boundaries, with a strict duration cap. */
internal object BalancedSegmentPlan {
    const val MAX_MINUTES = 45

    fun create(boundaries: LongArray, maxMinutes: Int = MAX_MINUTES): LongArray {
        require(maxMinutes in 1..MAX_MINUTES) { "El límite debe estar entre 1 y 45 minutos" }
        require(boundaries.size >= 2 && boundaries[0] >= 0) { "El audio no contiene muestras" }
        val maxUs = maxMinutes * 60L * 1_000_000L
        val samples = boundaries.lastIndex
        val farthest = IntArray(samples)
        var end = 1
        for (start in 0 until samples) {
            require(boundaries[start + 1] > boundaries[start]) { "Marcas temporales de audio no crecientes" }
            require(boundaries[start + 1] - boundaries[start] <= maxUs) { "Una muestra supera el límite" }
            end = maxOf(end, start + 1)
            while (end < samples && boundaries[end + 1] - boundaries[start] <= maxUs) end++
            farthest[start] = end
        }
        // Minimum feasible part count when encoded packets must stay whole.
        val minimumParts = IntArray(samples + 1)
        for (start in samples - 1 downTo 0) minimumParts[start] = 1 + minimumParts[farthest[start]]
        var remaining = minimumParts[0]
        val cuts = LongArray(remaining + 1)
        cuts[0] = boundaries[0]
        var start = 0
        var part = 1
        while (remaining > 1) {
            var low = start + 1
            val high = minOf(farthest[start], samples - (remaining - 1))
            var right = high
            // Leave a suffix that can still fit in the remaining parts.
            while (low < right) {
                val mid = (low + right) ushr 1
                if (minimumParts[mid] <= remaining - 1) right = mid else low = mid + 1
            }
            val earliest = low
            check(earliest <= high && minimumParts[earliest] <= remaining - 1)
            val target = boundaries[start] + (boundaries.last() - boundaries[start]) / remaining
            right = high
            while (low < right) {
                val mid = (low + right) ushr 1
                if (boundaries[mid] < target) low = mid + 1 else right = mid
            }
            var chosen = low
            if (chosen > earliest && kotlin.math.abs(boundaries[chosen - 1] - target) <= kotlin.math.abs(boundaries[chosen] - target)) {
                chosen--
            }
            cuts[part++] = boundaries[chosen]
            start = chosen
            remaining--
        }
        cuts[part] = boundaries.last()
        check(cuts.asSequence().zipWithNext().all { (a, b) -> b - a in 1..maxUs })
        return cuts
    }
}
