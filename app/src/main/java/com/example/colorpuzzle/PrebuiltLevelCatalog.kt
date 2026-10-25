package com.example.colorpuzzle

import android.content.Context

data class PrebuiltLevel(
    val level: Int,
    val cols: Int,
    val rows: Int,
    val grid: String
)

object PrebuiltLevelCatalog {

    const val MAX_LEVEL = 500_000

    // Все символы, которые использует генератор уровней.
    // U — вверх, D — вниз, L — влево, R — вправо,
    // V — вверх и вниз, B — влево и вправо,
    // Q — вниз, W — влево, E — вверх, A — вправо.
    private const val VALID_SYMBOLS = ".UDLRVBQWEA"

    @Volatile
    private var cache: Map<Int, PrebuiltLevel>? = null

    /**
     * Загружает конкретный уровень.
     * Возвращает null, если уровень отсутствует
     * или его данные некорректны.
     */
    fun load(context: Context, level: Int): PrebuiltLevel? {
        if (level !in 1..MAX_LEVEL) {
            return null
        }

        return loadAll(context)[level]
    }

    /**
     * Загружает и проверяет уровни из assets/levels.dat.
     * Результат кешируется, чтобы не читать файл повторно.
     */
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

                        // Проверяем номер уровня и размеры поля.
                        if (
                            level !in 1..MAX_LEVEL ||
                            cols !in 4..10 ||
                            rows !in 4..10 ||
                            grid.length != cols * rows
                        ) {
                            return@mapNotNull null
                        }

                        // Проверяем все символы стрелок.
                        if (grid.any { it !in VALID_SYMBOLS }) {
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

            cache = levels
            levels
        }
    }
}
