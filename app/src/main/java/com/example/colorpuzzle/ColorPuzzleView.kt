
package com.example.colorpuzzle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.random.Random

class ColorPuzzleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Собственные модели, чтобы этот файл не зависел
    // от внешних объявлений Cell и Direction.
    private data class Cell(
        val id: Int,
        val gridX: Int,
        val gridY: Int,
        val direction: Direction,
        val colorResId: Int,
        var currentX: Float = 0f,
        var currentY: Float = 0f
    )

    private enum class Direction {
        UP, DOWN, LEFT, RIGHT
    }

    private var cols = 4
    private var rows = 4

    private var cellSize = 0f
    private var offsetX = 0f
    private var offsetY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blocks = mutableListOf<Cell>()

    var onGameEventListener: OnGameEventListener? = null

    interface OnGameEventListener {
        fun onWrongMove()
        fun onLevelCompleted()
    }

    private val colors = intArrayOf(
        Color.rgb(255, 82, 82),
        Color.rgb(68, 138, 255),
        Color.rgb(76, 175, 80),
        Color.rgb(255, 179, 0),
        Color.rgb(171, 71, 188),
        Color.rgb(38, 166, 154),
        Color.rgb(255, 112, 67),
        Color.rgb(92, 107, 192)
    )

    init {
        loadLevel(1)
    }

    /*
     * Размер поля:
     * 1–5: 4x4
     * 6–10: 4x5
     * Далее постепенно увеличивается до 10x10.
     * После 140-го уровня размер остаётся 10x10.
     */
    private fun calculateBoardSize(level: Int): Pair<Int, Int> {
        var stage = (level.coerceAtLeast(1) - 1) / 5

        for (first in 4..10) {
            val stagesInRow = 11 - first

            if (stage < stagesInRow) {
                return first to (first + stage)
            }

            stage -= stagesInRow
        }

        return 10 to 10
    }

    /*
     * Целевая плотность блоков:
     * 1–10: 70%
     * 11–30: 80%
     * 31–60: 85%
     * 61–100: 90%
     * 101+: 95%
     */
    private fun calculateDensity(level: Int): Float {
        return when {
            level <= 10 -> 0.70f
            level <= 30 -> 0.80f
            level <= 60 -> 0.85f
            level <= 100 -> 0.90f
            else -> 0.95f
        }
    }

    fun loadLevel(level: Int) {
        val safeLevel = level.coerceAtLeast(1)

        val (newCols, newRows) = calculateBoardSize(safeLevel)
        cols = newCols
        rows = newRows

        val totalCells = cols * rows
        val density = calculateDensity(safeLevel)

        val requestedCount = minOf(
            totalCells - 1,
            (totalCells * density).toInt()
        ).coerceAtLeast(1)

        blocks.clear()
        blocks.addAll(createHardLevel(requestedCount))

        updateBoardGeometry(width, height)
        positionBlocks()
        invalidate()
    }

    fun loadLevel1() {
        loadLevel(1)
    }

    private fun createCell(
        id: Int,
        x: Int,
        y: Int,
        direction: Direction
    ): Cell {
        return Cell(
            id = id,
            gridX = x,
            gridY = y,
            direction = direction,
            colorResId = colors[Random.nextInt(colors.size)]
        )
    }

    /*
     * Проверяет, есть ли другие блоки на пути стрелки.
     */
    private fun isPathBlocked(
        candidate: Cell,
        others: List<Cell>
    ): Boolean {
        return others.any { other ->
            when (candidate.direction) {
                Direction.UP ->
                    other.gridX == candidate.gridX &&
                        other.gridY < candidate.gridY

                Direction.DOWN ->
                    other.gridX == candidate.gridX &&
                        other.gridY > candidate.gridY

                Direction.LEFT ->
                    other.gridY == candidate.gridY &&
                        other.gridX < candidate.gridX

                Direction.RIGHT ->
                    other.gridY == candidate.gridY &&
                        other.gridX > candidate.gridX
            }
        }
    }

    private fun countAvailableMoves(source: List<Cell>): Int {
        return source.count { block ->
            !isPathBlocked(
                block,
                source.filter { other -> other.id != block.id }
            )
        }
    }

    /*
     * Проверяет, можно ли последовательно удалить все блоки.
     */
    private fun isSolvable(source: List<Cell>): Boolean {
        val remaining = source.toMutableList()

        while (remaining.isNotEmpty()) {
            val removable = remaining.firstOrNull { block ->
                !isPathBlocked(
                    block,
                    remaining.filter { other -> other.id != block.id }
                )
            } ?: return false

            remaining.remove(removable)
        }

        return true
    }

    /*
     * Генерирует поле с высокой плотностью.
     * Если подходящая расстановка не найдена, уменьшает
     * целевое количество блоков и пробует снова.
     */
    private fun createHardLevel(
        requestedCount: Int
    ): MutableList<Cell> {

        var targetCount = requestedCount.coerceIn(
            1,
            cols * rows - 1
        )

        while (targetCount >= 1) {
            repeat(400) {
                val result = mutableListOf<Cell>()

                val positions = (0 until rows)
                    .flatMap { y ->
                        (0 until cols).map { x -> x to y }
                    }
                    .shuffled()

                for ((x, y) in positions) {
                    if (result.size >= targetCount) break

                    val directions = Direction.values().toList().shuffled()

                    val direction = directions.firstOrNull { dir ->
                        val candidate = createCell(
                            id = result.size + 1,
                            x = x,
                            y = y,
                            direction = dir
                        )

                        !isPathBlocked(candidate, result)
                    } ?: continue

                    result.add(
                        createCell(
                            id = result.size + 1,
                            x = x,
                            y = y,
                            direction = direction
                        )
                    )
                }

                if (result.size != targetCount) return@repeat
                if (!isSolvable(result)) return@repeat

                val available = countAvailableMoves(result)

                val accepted = when {
                    targetCount >= 12 -> available == 1
                    else -> available in 1..2
                }

                if (accepted) {
                    return result
                }
            }

            targetCount--
        }

        // Безопасный запасной вариант.
        return mutableListOf(
            createCell(
                id = 1,
                x = cols / 2,
                y = rows / 2,
                direction = Direction.UP
            )
        )
    }

    private fun updateBoardGeometry(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        val availableWidth = w * 0.94f
        val availableHeight = h * 0.90f

        cellSize = min(
            availableWidth / cols,
            availableHeight / rows
        )

        offsetX = (w - cellSize * cols) / 2f
        offsetY = (h - cellSize * rows) / 2f
    }

    private fun positionBlocks() {
        if (cellSize <= 0f) return

        blocks.forEach { block ->
            block.currentX = offsetX + block.gridX * cellSize
            block.currentY = offsetY + block.gridY * cellSize
        }
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        super.onSizeChanged(w, h, oldw, oldh)

        updateBoardGeometry(w, h)
        positionBlocks()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(Color.rgb(245, 245, 245))

        if (cellSize <= 0f) return

        // Сетка без теней.
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(218, 222, 228)
        paint.strokeWidth = 1.5f

        for (r in 0..rows) {
            val y = offsetY + r * cellSize

            canvas.drawLine(
                offsetX,
                y,
                offsetX + cols * cellSize,
                y,
                paint
            )
        }

        for (c in 0..cols) {
            val x = offsetX + c * cellSize

            canvas.drawLine(
                x,
                offsetY,
                x,
                offsetY + rows * cellSize,
                paint
            )
        }

        // Цветные блоки без теней.
        for (block in blocks) {
            paint.clearShadowLayer()
            paint.style = Paint.Style.FILL
            paint.color = block.colorResId

            val inset = cellSize * 0.08f

            val rect = RectF(
                block.currentX + inset,
                block.currentY + inset,
                block.currentX + cellSize - inset,
                block.currentY + cellSize - inset
            )

            canvas.drawRoundRect(
                rect,
                cellSize * 0.16f,
                cellSize * 0.16f,
                paint
            )

            drawArrow(canvas, block)
        }
    }

    private fun drawArrow(canvas: Canvas, block: Cell) {
        val cx = block.currentX + cellSize / 2f
        val cy = block.currentY + cellSize / 2f
        val len = cellSize * 0.23f
        val head = cellSize * 0.11f

        // Инициализация координат устраняет ошибку компиляции.
        var startX = cx
        var startY = cy
        var endX = cx
        var endY = cy

        when (block.direction) {
            Direction.UP -> {
                startX = cx
                startY = cy + len
                endX = cx
                endY = cy - len
            }

            Direction.DOWN -> {
                startX = cx
                startY = cy - len
                endX = cx
                endY = cy + len
            }

            Direction.LEFT -> {
                startX = cx + len
                startY = cy
                endX = cx - len
                endY = cy
            }

            Direction.RIGHT -> {
                startX = cx - len
                startY = cy
                endX = cx + len
                endY = cy
            }
        }

        paint.clearShadowLayer()
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (cellSize * 0.055f).coerceAtLeast(2f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        canvas.drawLine(startX, startY, endX, endY, paint)

        when (block.direction) {
            Direction.UP -> {
                canvas.drawLine(
                    endX, endY,
                    endX - head, endY + head,
                    paint
                )
                canvas.drawLine(
                    endX, endY,
                    endX + head, endY + head,
                    paint
                )
            }

            Direction.DOWN -> {
                canvas.drawLine(
                    endX, endY,
                    endX - head, endY - head,
                    paint
                )
                canvas.drawLine(
                    endX, endY,
                    endX + head, endY - head,
                    paint
                )
            }

            Direction.LEFT -> {
                canvas.drawLine(
                    endX, endY,
                    endX + head, endY - head,
                    paint
                )
                canvas.drawLine(
                    endX, endY,
                    endX + head, endY + head,
                    paint
                )
            }

            Direction.RIGHT -> {
                canvas.drawLine(
                    endX, endY,
                    endX - head, endY - head,
                    paint
                )
                canvas.drawLine(
                    endX, endY,
                    endX - head, endY + head,
                    paint
                )
            }
        }

        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true

            MotionEvent.ACTION_UP -> {
                if (cellSize <= 0f) return true

                val block = blocks.firstOrNull { item ->
                    event.x >= item.currentX &&
                        event.x <= item.currentX + cellSize &&
                        event.y >= item.currentY &&
                        event.y <= item.currentY + cellSize
                }

                if (block != null) {
                    evaluateMove(block)
                }

                performClick()
                return true
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun evaluateMove(block: Cell) {
        val blocked = isPathBlocked(
            block,
            blocks.filter { other -> other.id != block.id }
        )

        if (blocked) {
            onGameEventListener?.onWrongMove()
            return
        }

        blocks.remove(block)
        invalidate()

        if (blocks.isEmpty()) {
            onGameEventListener?.onLevelCompleted()
        }
    }
}
