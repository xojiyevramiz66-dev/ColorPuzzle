package com.example.colorpuzzle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PrebuiltLevel(
    val level: Int,
    val cols: Int,
    val rows: Int,
    val grid: String
)

object PrebuiltLevelCatalog {

    const val MAX_LEVEL = 500_000

    private const val ASSET_FILE = "levels.dat"
    private const val VALID_SYMBOLS = ".UDLRVBQWEA"

    /**
     * Загружает конкретный уровень непосредственно
     * из assets/levels.dat.
     *
     * Дополнительные файлы не создаются.
     */
    @Synchronized
    fun load(
        context: Context,
        level: Int
    ): PrebuiltLevel? {
        if (level !in 1..MAX_LEVEL) return null

        return try {
            context.applicationContext.assets
                .open(ASSET_FILE)
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines ->
                    lines
                        .mapNotNull { line ->
                            parseLine(line)
                        }
                        .firstOrNull { item ->
                            item.level == level
                        }
                }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Предварительная загрузка в фоновом потоке.
     */
    suspend fun preloadAsync(context: Context) {
        withContext(Dispatchers.IO) {
            prepare(context)
        }
    }

    /**
     * Проверяет исходный файл без копирования
     * во внутреннее хранилище приложения.
     */
    fun prepare(context: Context) {
        try {
            context.applicationContext.assets
                .open(ASSET_FILE)
                .bufferedReader(Charsets.UTF_8)
                .use { reader ->
                    val firstLevel = reader.lineSequence()
                        .mapNotNull { line ->
                            parseLine(line)
                        }
                        .firstOrNull()

                    requireNotNull(firstLevel) {
                        "Файл assets/levels.dat пуст или повреждён"
                    }
                }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Не удалось прочитать assets/levels.dat",
                e
            )
        }
    }

    /**
     * Формат строки:
     * уровень|столбцы|строки|символы поля
     */
    private fun parseLine(
        line: String
    ): PrebuiltLevel? {
        val first = line.indexOf('|')
        if (first <= 0) return null

        val second = line.indexOf('|', first + 1)
        if (second <= first + 1) return null

        val third = line.indexOf('|', second + 1)
        if (third <= second + 1) return null

        val level = line.substring(
            0,
            first
        ).toIntOrNull() ?: return null

        val cols = line.substring(
            first + 1,
            second
        ).toIntOrNull() ?: return null

        val rows = line.substring(
            second + 1,
            third
        ).toIntOrNull() ?: return null

        val grid = line.substring(third + 1)

        if (level !in 1..MAX_LEVEL) return null
        if (cols !in 4..10 || rows !in 4..10) return null
        if (grid.length != cols * rows) return null
        if (grid.any { it !in VALID_SYMBOLS }) return null

        return PrebuiltLevel(
            level = level,
            cols = cols,
            rows = rows,
            grid = grid
        )
    }
}
