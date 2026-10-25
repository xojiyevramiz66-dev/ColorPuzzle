
package com.example.colorpuzzle

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

data class PrebuiltLevel(
    val level: Int,
    val cols: Int,
    val rows: Int,
    val grid: String
)

object PrebuiltLevelCatalog {

    const val MAX_LEVEL = 500_000

    private const val ASSET_FILE = "levels.dat"
    private const val DATA_FILE = "levels-v1.dat"
    private const val INDEX_FILE = "levels-v1.idx"
    private const val INDEX_MAGIC = 0x43504C31
    private const val VALID_SYMBOLS = ".UDLRVBQWEA"

    @Volatile
    private var dataFile: File? = null

    @Volatile
    private var offsets: LongArray? = null

    /**
     * Подготавливает локальную копию каталога и индекс.
     * Вызывать из фонового потока.
     */
    @Synchronized
    fun prepare(context: Context) {
        val appContext = context.applicationContext
        var file = getDataFile(appContext)

        try {
            getOffsets(appContext, file)
        } catch (firstError: Exception) {
            // Повреждённый кеш можно восстановить из assets APK.
            invalidateLocalCache(appContext)
            file = copyAssetToInternalStorage(appContext)
            dataFile = file
            offsets = null

            try {
                getOffsets(appContext, file)
            } catch (secondError: Exception) {
                secondError.addSuppressed(firstError)
                throw IOException(
                    "Не удалось прочитать каталог уровней из " +
                        "assets/$ASSET_FILE. Проверьте генерацию уровней.",
                    secondError
                )
            }
        }
    }

    @Synchronized
    fun load(context: Context, level: Int): PrebuiltLevel? {
        if (level !in 1..MAX_LEVEL) return null

        val appContext = context.applicationContext
        val file = getDataFile(appContext)
        val index = getOffsets(appContext, file)
        val offset = index[level]

        if (offset < 0L) {
            throw IOException("В каталоге отсутствует уровень $level.")
        }

        RandomAccessFile(file, "r").use { raf ->
            if (offset >= raf.length()) {
                throw IOException(
                    "Индекс уровня $level указывает за пределы файла."
                )
            }

            raf.seek(offset)

            val line = raf.readLine()
                ?: throw IOException("Не удалось прочитать уровень $level.")

            val parsed = parseLine(line)
                ?: throw IOException("Запись уровня $level повреждена.")

            if (parsed.level != level) {
                throw IOException(
                    "Индекс уровня $level повреждён: " +
                        "найден уровень ${parsed.level}."
                )
            }

            return parsed
        }
    }

    @Synchronized
    private fun getDataFile(context: Context): File {
        dataFile?.let { cached ->
            if (cached.isFile && cached.length() > 0L) {
                return cached
            }
        }

        val target = File(context.filesDir, DATA_FILE)

        if (target.isFile && target.length() > 0L) {
            dataFile = target
            return target
        }

        val copied = copyAssetToInternalStorage(context)
        dataFile = copied
        offsets = null
        return copied
    }

    private fun copyAssetToInternalStorage(context: Context): File {
        val target = File(context.filesDir, DATA_FILE)
        val temp = File(context.filesDir, "$DATA_FILE.tmp")

        try {
            context.assets.open(ASSET_FILE).use { input ->
                temp.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }

            if (!temp.isFile || temp.length() == 0L) {
                throw IOException("Ресурс assets/$ASSET_FILE пуст.")
            }

            replaceFile(temp, target)
            File(context.filesDir, INDEX_FILE).delete()
            offsets = null

            if (!target.isFile || target.length() == 0L) {
                throw IOException(
                    "Не удалось сохранить локальную копию $DATA_FILE."
                )
            }

            return target
        } catch (error: Exception) {
            temp.delete()

            throw IOException(
                "Не удалось открыть или скопировать assets/$ASSET_FILE. " +
                    "Убедитесь, что levels.dat создан до сборки APK.",
                error
            )
        } finally {
            temp.delete()
        }
    }

    private fun replaceFile(temp: File, target: File) {
        if (temp.renameTo(target)) return

        try {
            temp.copyTo(target, overwrite = true)
        } catch (error: Exception) {
            throw IOException(
                "Не удалось заменить файл ${target.name}.",
                error
            )
        } finally {
            temp.delete()
        }
    }

    private fun invalidateLocalCache(context: Context) {
        dataFile = null
        offsets = null

        File(context.filesDir, DATA_FILE).delete()
        File(context.filesDir, INDEX_FILE).delete()
        File(context.filesDir, "$DATA_FILE.tmp").delete()
    }

    @Synchronized
    private fun getOffsets(
        context: Context,
        file: File
    ): LongArray {
        offsets?.let { cached ->
            if (cached.size == MAX_LEVEL + 1) return cached
        }

        val indexFile = File(context.filesDir, INDEX_FILE)

        readIndex(indexFile, file.length())?.let { cached ->
            offsets = cached
            return cached
        }

        val result = LongArray(MAX_LEVEL + 1) { -1L }
        var expectedLevel = 1

        RandomAccessFile(file, "r").use { raf ->
            while (raf.filePointer < raf.length()) {
                val position = raf.filePointer
                val line = raf.readLine()
                    ?: throw IOException(
                        "Неожиданный конец файла уровней."
                    )

                val parsed = parseLine(line)
                    ?: throw IOException(
                        "Повреждена запись уровня около строки $expectedLevel."
                    )

                if (parsed.level != expectedLevel) {
                    throw IOException(
                        "Ожидался уровень $expectedLevel, " +
                            "найден ${parsed.level}."
                    )
                }

                result[expectedLevel] = position
                expectedLevel++
            }
        }

        if (expectedLevel != MAX_LEVEL + 1) {
            throw IOException(
                "В каталоге найдено ${expectedLevel - 1} уровней " +
                    "вместо $MAX_LEVEL."
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
                if (input.readInt() != INDEX_MAGIC) return null
                if (input.readLong() != expectedDataLength) return null
                if (input.readInt() != MAX_LEVEL) return null

                val values = LongArray(MAX_LEVEL + 1) {
                    input.readLong()
                }

                if (input.read() != -1) return null
                if (values[0] != -1L) return null

                var previous = -1L

                for (level in 1..MAX_LEVEL) {
                    val offset = values[level]

                    if (
                        offset <= previous ||
                        offset >= expectedDataLength
                    ) {
                        return null
                    }

                    previous = offset
                }

                values
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
        val temp = File(file.parentFile, "${file.name}.tmp")

        try {
            DataOutputStream(
                BufferedOutputStream(temp.outputStream())
            ).use { output ->
                output.writeInt(INDEX_MAGIC)
                output.writeLong(dataLength)
                output.writeInt(MAX_LEVEL)

                positions.forEach { position ->
                    output.writeLong(position)
                }
            }

            replaceFile(temp, file)
        } catch (_: Exception) {
            // Индекс — кеш. При необходимости он будет построен заново.
            temp.delete()
        }
    }

    private fun parseLine(line: String): PrebuiltLevel? {
        val first = line.indexOf('|')
        if (first <= 0) return null

        val second = line.indexOf('|', first + 1)
        if (second <= first + 1) return null

        val third = line.indexOf('|', second + 1)
        if (third <= second + 1) return null

        val level =
            line.substring(0, first).toIntOrNull() ?: return null

        val cols =
            line.substring(first + 1, second).toIntOrNull()
                ?: return null

        val rows =
            line.substring(second + 1, third).toIntOrNull()
                ?: return null

        val grid = line.substring(third + 1)

        if (level !in 1..MAX_LEVEL) return null
        if (cols !in 4..10 || rows !in 4..10) return null
        if (grid.length != cols * rows) return null
        if (grid.any { it !in VALID_SYMBOLS }) return null
        if (grid.none { it == '.' }) return null

        return PrebuiltLevel(level, cols, rows, grid)
    }
}
