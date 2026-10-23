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

private val cols = 4
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

fun loadLevel(level: Int) {
    blocks.clear()

    val safeLevel = level.coerceAtLeast(1)

    // Увеличиваем количество строк каждые 5 уровней:
    // 1–5: 4x4, 6–10: 4x5, 11–15: 4x6 и так далее.
    rows = 4 + (safeLevel - 1) / 5

    // Количество блоков постепенно увеличивается.
    // Оставляем как минимум одну свободную клетку.
    val count = minOf(
        cols * rows - 1,
        3 + (safeLevel - 1) / 2
    )

    fun createBlock(
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

    fun isPathBlocked(
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

    // Не допускаем стрелки, направленные навстречу друг другу.
    fun arrowsFaceEachOther(
        candidate: Cell,
        others: List<Cell>
    ): Boolean {
        return others.any { other ->
            val sameRow = other.gridY == candidate.gridY
            val sameColumn = other.gridX == candidate.gridX

            val horizontalConflict =
                sameRow &&
                    (
                        (
                            candidate.gridX < other.gridX &&
                                candidate.direction == Direction.RIGHT &&
                                other.direction == Direction.LEFT
                        ) ||
                        (
                            other.gridX < candidate.gridX &&
                                other.direction == Direction.RIGHT &&
                                candidate.direction == Direction.LEFT
                        )
                    )

            val verticalConflict =
                sameColumn &&
                    (
                        (
                            candidate.gridY < other.gridY &&
                                candidate.direction == Direction.DOWN &&
                                other.direction == Direction.UP
                        ) ||
                        (
                            other.gridY < candidate.gridY &&
                                other.direction == Direction.DOWN &&
                                candidate.direction == Direction.UP
                        )
                    )

            horizontalConflict || verticalConflict
        }
    }

    // Проверяем, можно ли удалить все блоки по очереди.
    fun isSolvable(source: List<Cell>): Boolean {
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

    var generated = false

    // Генерируем случайный уровень и проверяем его проходимость.
    repeat(1500) {
        if (generated) return@repeat

        val positions = (0 until rows)
            .flatMap { y ->
                (0 until cols).map { x -> x to y }
            }
            .shuffled()

        val candidateBlocks = mutableListOf<Cell>()

        for ((x, y) in positions) {
            if (candidateBlocks.size >= count) break

            val directions = Direction.values().toList().shuffled()

            val chosenDirection = directions.firstOrNull { direction ->
                val candidate = createBlock(
                    candidateBlocks.size + 1,
                    x,
                    y,
                    direction
                )

                !arrowsFaceEachOther(candidate, candidateBlocks)
            } ?: continue

            candidateBlocks.add(
                createBlock(
                    candidateBlocks.size + 1,
                    x,
                    y,
                    chosenDirection
                )
            )
        }

        if (
            candidateBlocks.size == count &&
            isSolvable(candidateBlocks)
        ) {
            blocks.clear()
            blocks.addAll(candidateBlocks)
            generated = true
        }
    }

    // Запасной гарантированно проходимый вариант.
    // Слева стрелки смотрят влево, справа — вправо.
    if (!generated) {
        blocks.clear()

        val positions = (0 until rows)
            .flatMap { y ->
                (0 until cols).map { x -> x to y }
            }
            .shuffled()
            .take(count)

        positions.forEachIndexed { index, (x, y) ->
            val direction = if (x < cols / 2) {
                Direction.LEFT
            } else {
                Direction.RIGHT
            }

            blocks.add(
                createBlock(
                    index + 1,
                    x,
                    y,
                    direction
                )
            )
        }
    }

    updateBoardGeometry(width, height)
    positionBlocks()
    invalidate()
}

fun loadLevel1() {
    loadLevel(1)
}

private fun updateBoardGeometry(w: Int, h: Int) {
    if (w <= 0 || h <= 0) return

    // Поле остаётся по центру и помещается в доступную область.
    val availableWidth = w * 0.92f
    val availableHeight = h * 0.84f

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
}

override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)

    if (cellSize <= 0f) return

    canvas.drawColor(Color.rgb(245, 245, 245))

    // Рисуем сетку с динамическим количеством строк.
    paint.style = Paint.Style.STROKE
    paint.color = Color.LTGRAY
    paint.strokeWidth = 2f

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

    // Рисуем цветные блоки без тени.
    for (block in blocks) {
        paint.style = Paint.Style.FILL
        paint.color = block.colorResId
        paint.clearShadowLayer()

        val inset = cellSize * 0.09f

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

    paint.color = Color.WHITE
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = (cellSize * 0.055f).coerceAtLeast(3f)
    paint.strokeCap = Paint.Cap.ROUND

    val endX: Float
    val endY: Float

    when (block.direction) {
        Direction.UP -> {
            endX = cx
            endY = cy - len
            canvas.drawLine(cx, cy + len, endX, endY, paint)
        }

        Direction.DOWN -> {
            endX = cx
            endY = cy + len
            canvas.drawLine(cx, cy - len, endX, endY, paint)
        }

        Direction.LEFT -> {
            endX = cx - len
            endY = cy
            canvas.drawLine(cx + len, cy, endX, endY, paint)
        }

        Direction.RIGHT -> {
            endX = cx + len
            endY = cy
            canvas.drawLine(cx - len, cy, endX, endY, paint)
        }
    }

    val head = cellSize * 0.11f

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
            val block = blocks.find {
                event.x >= it.currentX &&
                    event.x <= it.currentX + cellSize &&
                    event.y >= it.currentY &&
                    event.y <= it.currentY + cellSize
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
    val isBlocked = blocks.any { other ->
        if (other.id == block.id) {
            return@any false
        }

        when (block.direction) {
            Direction.UP ->
                other.gridX == block.gridX &&
                    other.gridY < block.gridY

            Direction.DOWN ->
                other.gridX == block.gridX &&
                    other.gridY > block.gridY

            Direction.LEFT ->
                other.gridY == block.gridY &&
                    other.gridX < block.gridX

            Direction.RIGHT ->
                other.gridY == block.gridY &&
                    other.gridX > block.gridX
        }
    }

    if (isBlocked) {
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
