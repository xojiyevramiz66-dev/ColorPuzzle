package com.example.colorpuzzle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

data class PrebuiltLevel(
    val level: Int,
    val cols: Int,
    val rows: Int,
    val grid: String
)

object PrebuiltLevelCatalog {

    const val MAX_LEVEL = 500_000

    private const val DATA_FILE = "levels-v1.dat"
    private const val INDEX_FILE = "levels-v1.idx"
    private const val INDEX_MAGIC = 0x43504C31

    private const val VALID_SYMBOLS = ".UDLRVBQWEA"

    @Volatile
    private var dataFile: File? = null

    @Volatile
    private var offsets: LongArray? = null

    /**
     * Загружает строго запрошенный готовый уровень.
     * Если каталог не готов или данные повреждены, возвращает null.
     */
    @Synchronized
    fun load(context: Context, level: Int): PrebuiltLevel? {
        if (level !in 1..MAX_LEVEL) return null

        return try {
            val appContext = context.applicationContext
            val file = getDataFile(appContext)
            val index = getOffsetsFast(appContext, file)
                ?: return null

            val offset = index.getOrNull(level) ?: -1L
            if (offset < 0L) return null

            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val line = raf.readLine() ?: return null
                val parsed = parseLine(line) ?: return null

                if (parsed.level != level) {
                    return null
                }

                parsed
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Готовит файл уровней и индекс в фоновом потоке.
     * Вызывать перед началом игры.
     */
    suspend fun preloadAsync(context: Context) {
        withContext(Dispatchers.IO) {
            prepare(context)
        }
    }

    /**
     * Синхронная подготовка каталога.
     * Обязательно вызывайте из фонового потока.
     */
    fun prepare(context: Context) {
        val appContext = context.applicationContext
        val file = getDataFile(appContext)
        getOffsets(appContext, file)
    }

    private fun getOffsetsFast(
        context: Context,
        file: File
    ): LongArray? {
        offsets?.let { return it }

        val indexFile = File(context.filesDir, INDEX_FILE)
        val cached = readIndex(indexFile, file.length())

        if (cached != null) {
            offsets = cached
            return cached
        }

        return null
    }

    @Synchronized
    private fun getDataFile(context: Context): File {
        dataFile?.let {
            if (it.isFile && it.length() > 0L) {
                return it
            }
        }

        val target = File(context.filesDir, DATA_FILE)

        if (!target.isFile || target.length() == 0L) {
            val temp = File(
                context.filesDir,
                "$DATA_FILE.tmp"
            )

            context.assets.open("levels.dat").use { input ->
                temp.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }

            if (target.exists() && !target.delete()) {
                throw IllegalStateException(
                    "Не удалось заменить файл уровней"
                )
            }

            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }

            File(context.filesDir, INDEX_FILE).delete()
            offsets = null
        }

        if (!target.isFile || target.length() == 0L) {
            throw IllegalStateException(
                "Файл levels.dat пуст или не найден"
            )
        }

        dataFile = target
        return target
    }

    @Synchronized
    private fun getOffsets(
        context: Context,
        file: File
    ): LongArray {
        offsets?.let { return it }

        val indexFile = File(context.filesDir, INDEX_FILE)

        val cached = readIndex(indexFile, file.length())
        if (cached != null) {
            offsets = cached
            return cached
        }

        val result = LongArray(MAX_LEVEL + 1) { -1L }

        RandomAccessFile(file, "r").use { raf ->
            while (raf.filePointer < raf.length()) {
                val position = raf.filePointer
                val line = raf.readLine() ?: break
                val separator = line.indexOf('|')

                if (separator <= 0) continue

                val parsedLevel = line
                    .substring(0, separator)
                    .toIntOrNull()
                    ?: continue

                if (parsedLevel in 1..MAX_LEVEL) {
                    result[parsedLevel] = position
                }
            }
        }

        if (result.drop(1).none { it >= 0L }) {
            throw IllegalStateException(
                "В каталоге не найдены готовые уровни"
            )
        }

        writeIndex(indexFile, file.length(), result)
        offsets = result

        return result
    }

    private fun readIndex(
        file: File,
        expectedDataLength: Long
    ): LongArray? {
        if (!file.isFile) return null

        return try {
            DataInputStream(
                BufferedInputStream(file.inputStream())
            ).use { input ->
                if (input.readInt() != INDEX_MAGIC) {
                    return null
                }

                if (input.readLong() != expectedDataLength) {
                    return null
                }

                if (input.readInt() != MAX_LEVEL) {
                    return null
                }

                LongArray(MAX_LEVEL + 1) {
                    input.readLong()
                }.takeIf { input.read() == -1 }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeIndex(
        file: File,
        dataLength: Long,
        positions: LongArray
    ) {
        val temp = File(
            file.parentFile,
            "${file.name}.tmp"
        )

        try {
            DataOutputStream(
                BufferedOutputStream(temp.outputStream())
            ).use { output ->
                output.writeInt(INDEX_MAGIC)
                output.writeLong(dataLength)
                output.writeInt(MAX_LEVEL)

                positions.forEach { offset ->
                    output.writeLong(offset)
                }
            }

            if (file.exists() && !file.delete()) {
                throw IllegalStateException(
                    "Не удалось заменить индекс уровней"
                )
            }

            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun parseLine(line: String): PrebuiltLevel? {
        val first = line.indexOf('|')
        if (first <= 0) return null

        val second = line.indexOf('|', first + 1)
        if (second <= first + 1) return null

        val third = line.indexOf('|', second + 1)
        if (third <= second + 1) return null

        val level = line.substring(0, first).toIntOrNull()
            ?: return null

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
