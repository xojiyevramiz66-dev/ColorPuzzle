
private fun all(context: Context): Map<Int, PrebuiltLevel> {
    val existing = cache
    if (existing != null) return existing

    return synchronized(this) {
        val current = cache
        if (current != null) {
            return@synchronized current
        }

        val parsed = context.assets
            .open("levels.dat")
            .bufferedReader()
            .useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split('|', limit = 4)
                    if (parts.size != 4) {
                        return@mapNotNull null
                    }

                    val level = parts[0].toIntOrNull()
                        ?: return@mapNotNull null
                    val cols = parts[1].toIntOrNull()
                        ?: return@mapNotNull null
                    val rows = parts[2].toIntOrNull()
                        ?: return@mapNotNull null
                    val grid = parts[3]

                    if (
                        level !in 1..MAX_LEVEL ||
                        cols !in 4..10 ||
                        rows !in 4..10 ||
                        grid.length != cols * rows ||
                        grid.any { it !in ".UD" }
                    ) {
                        return@mapNotNull null
                    }

                    PrebuiltLevel(
                        level = level,
                        cols = cols,
                        rows = rows,
                        grid = grid
                    )
                }.associateBy { it.level }
            }

        cache = parsed
        parsed
    }
}
