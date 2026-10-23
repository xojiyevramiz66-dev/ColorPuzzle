
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
     * Высокая плотность с первого уровня.
     * Одна клетка остаётся свободной.
     */
    private fun calculateDensity(level: Int): Float {
        return 0.999f
    }

    fun loadLevel(level: Int) {
        val safeLevel = level.coerceAtLeast(1)

        val (newCols, newRows) = calculateBoardSize(safeLevel)
        cols = newCols
        rows = newRows

        val totalCells = cols * rows

        val requestedCount = minOf(
            totalCells - 1,
            (totalCells * calculateDensity(safeLevel)).toInt()
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
     * Проверяем, находится ли другой блок на пути стрелки.
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
                source.filter { it.id != block.id }
            )
        }
    }

    /*
     * Проверяем, можно ли удалить все блоки.
     */
    private fun isSolvable(source: List<Cell>): Boolean {
        val remaining = source.toMutableList()

        while (remaining.isNotEmpty()) {
            val removable = remaining.firstOrNull { block ->
                !isPathBlocked(
                    block,
                    remaining.filter { it.id != block.id }
                )
            } ?: return false

            remaining.remove(removable)
        }

        return true
    }

    /*
     * СЛУЧАЙНЫЙ ГЕНЕРАТОР УРОВНЕЙ
     *
     * Создаёт случайные маршруты и ветвящиеся зависимости.
     * Новый блок перекрывает текущий доступный ход и сам
     * получает свободное направление.
     *
     * Это позволяет последовательно удалять блоки в обратном
     * порядке построения. Каждая принятая раскладка проверяется.
     */
    private fun createHardLevel(
        requestedCount: Int
    ): MutableList<Cell> {
        val targetCount = requestedCount.coerceIn(
            1,
            cols * rows - 1
        )

        var best = mutableListOf<Cell>()

        val attempts = if (targetCount >= 60) 80 else 140

        repeat(attempts) {
            val candidate = createRandomBranchingAttempt(targetCount)

            if (candidate.size > best.size) {
                best = candidate
            }

            if (
                candidate.size == targetCount &&
                countAvailableMoves(candidate) == 1 &&
                isSolvable(candidate)
            ) {
                return candidate
            }
        }

        if (
            best.isNotEmpty() &&
            countAvailableMoves(best) == 1 &&
            isSolvable(best)
        ) {
            return best
        }

        // Запасной вариант на случай неудачи всех попыток.
        val seeds = mutableListOf<Triple<Int, Int, Direction>>()

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                for (direction in Direction.values()) {
                    if (hasCellAhead(x, y, direction)) {
                        seeds.add(Triple(x, y, direction))
                    }
                }
            }
        }

        val seed = seeds.random()

        return mutableListOf(
            createCell(
                1,
                seed.first,
                seed.second,
                seed.third
            )
        )
    }

    /*
     * Одна попытка построения случайного уровня.
     */
    private fun createRandomBranchingAttempt(
        targetCount: Int
    ): MutableList<Cell> {
        val result = mutableListOf<Cell>()
        val seeds = mutableListOf<Triple<Int, Int, Direction>>()

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                for (direction in Direction.values()) {
                    if (hasCellAhead(x, y, direction)) {
                        seeds.add(Triple(x, y, direction))
                    }
                }
            }
        }

        if (seeds.isEmpty()) return result

        val seed = seeds.random()

        result.add(
            createCell(
                1,
                seed.first,
                seed.second,
                seed.third
            )
        )

        while (result.size < targetCount) {
            val available = result.filter { block ->
                !isPathBlocked(
                    block,
                    result.filter { it.id != block.id }
                )
            }

            // Продолжаем только при одном доступном ходе.
            if (available.size != 1) break

            val active = available.single()
            val rayPositions = mutableListOf<Pair<Int, Int>>()

            var x = active.gridX
            var y = active.gridY

            // Находим свободные клетки вдоль стрелки активного блока.
            while (true) {
                when (active.direction) {
                    Direction.UP -> y--
                    Direction.DOWN -> y++
                    Direction.LEFT -> x--
                    Direction.RIGHT -> x++
                }

                if (x !in 0 until cols || y !in 0 until rows) {
                    break
                }

                if (result.none { it.gridX == x && it.gridY == y }) {
                    rayPositions.add(x to y)
                }
            }

            if (rayPositions.isEmpty()) break

            var chosen: Cell? = null

            // Перемешивание создаёт разные маршруты на каждом уровне.
            for ((px, py) in rayPositions.shuffled()) {
                for (direction in Direction.values().toList().shuffled()) {

                    // До последнего блока желательно оставить
                    // клетку впереди для продолжения маршрута.
                    if (
                        result.size + 1 < targetCount &&
                        !hasCellAhead(px, py, direction)
                    ) {
                        continue
                    }

                    val proposed = createCell(
                        id = (result.maxOfOrNull { it.id } ?: 0) + 1,
                        x = px,
                        y = py,
                        direction = direction
                    )

                    // Новый блок не должен быть заблокирован
                    // уже размещёнными блоками.
                    if (!isPathBlocked(proposed, result)) {
                        chosen = proposed
                        break
                    }
                }

                if (chosen != null) break
            }

            val next = chosen ?: break
            result.add(next)

            // Сохраняем только шаги с одним доступным ходом.
            if (countAvailableMoves(result) != 1) {
                result.removeAt(result.lastIndex)
                break
            }
        }

        return result
    }

    private fun hasCellAhead(
        x: Int,
        y: Int,
        direction: Direction
    ): Boolean {
        return when (direction) {
            Direction.UP -> y > 0
            Direction.DOWN -> y < rows - 1
            Direction.LEFT -> x > 0
            Direction.RIGHT -> x < cols - 1
        }
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

        // Блоки без теней.
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

        var startX = cx
        var startY = cy
        var endX = cx
        var endY = cy

        when (block.direction) {
            Direction.UP -> {
                startY = cy + len
                endY = cy - len
            }

            Direction.DOWN -> {
                startY = cy - len
                endY = cy + len
            }

            Direction.LEFT -> {
                startX = cx + len
                endX = cx - len
            }

            Direction.RIGHT -> {
                startX = cx - len
                endX = cx + len
            }
        }

        paint.clearShadowLayer()
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (cellSize * 0.055f).coerceAtLeast(2f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        canvas.drawLine(
            startX,
            startY,
            endX,
            endY,
            paint
        )

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
            blocks.filter { it.id != block.id }
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
