
package com.example.colorpuzzle

import android.content.Context

data class PrebuiltLevel(
    val level: Int,
    val cols: Int,
    val rows: Int,
    val grid: String
)

object PrebuiltLevelCatalog {

    const val MAX_LEVEL = 100_000

    private const val VALID_SYMBOLS = ".UDLRVBQW"

    @Volatile
    private var cache: Map<Int, PrebuiltLevel>? = null

    fun load(context: Context, level: Int): PrebuiltLevel? {
        if (level !in 1..MAX_LEVEL) return null
        return loadAll(context)[level]
    }

    private fun loadAll(context: Context): Map<Int, PrebuiltLevel> {
        cache?.let { return it }

        return synchronized(this) {
            cache?.let { return@synchronized it }

            val levels = context.assets
                .open("levels.dat")
                .bufferedReader()
                .useLines { lines ->
                    lines.mapNotNull { line ->
                        val parts = line.split('|', limit = 4)
                        if (parts.size != 4) return@mapNotNull null

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
                            grid.any { it !in VALID_SYMBOLS }
                        ) {
                            return@mapNotNull null
                        }

                        PrebuiltLevel(level, cols, rows, grid)
                    }.associateBy { it.level }
                }

            cache = levels
            levels
        }
    }
}
