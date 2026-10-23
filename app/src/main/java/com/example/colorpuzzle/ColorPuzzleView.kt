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
    private val rows = 4

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

    init {
        loadLevel(1)
    }

    fun loadLevel(level: Int) {
        blocks.clear()

        val safeLevel = level.coerceAtLeast(1)
        val count = minOf(12, 3 + (safeLevel - 1) / 2)

        val colors = intArrayOf(
            Color.rgb(255, 82, 82),
            Color.rgb(68, 138, 255),
            Color.rgb(76, 175, 80),
            Color.rgb(255, 179, 0),
            Color.rgb(171, 71, 188),
            Color.rgb(38, 166, 154),
            Color.rgb(255, 112, 67),
            Color.rgb(92, 107, 192)
        )

        // Все доступные позиции на игровом поле.
        val availableCells = (0 until rows)
            .flatMap { y ->
                (0 until cols).map { x -> x to y }
            }
            .toMutableList()

        availableCells.shuffle()

        // Проверяем, свободен ли путь в выбранном направлении.
        // Учитываются все блоки, которые уже добавлены на поле.
        fun pathIsClear(
            x: Int,
            y: Int,
            direction: Direction
        ): Boolean {
            return blocks.none { other ->
                when (direction) {
                    Direction.UP ->
                        other.gridX == x &&
                            other.gridY < y

                    Direction.DOWN ->
                        other.gridX == x &&
                            other.gridY > y

                    Direction.LEFT ->
                        other.gridY == y &&
                            other.gridX < x

                    Direction.RIGHT ->
                        other.gridY == y &&
                            other.gridX > x
                }
            }
        }

        // Создаём каждый следующий блок так, чтобы его путь
        // был свободен от ранее созданных блоков.
        for (i in 0 until count) {
            val candidates = availableCells.flatMap { (x, y) ->
                Direction.values()
                    .filter { direction ->
                        pathIsClear(x, y, direction)
                    }
                    .map { direction ->
                        Triple(x, y, direction)
                    }
            }

            // Для текущего поля 4x4 всегда можно найти
            // свободную граничную позицию с выходом наружу.
            if (candidates.isEmpty()) {
                break
            }

            val (x, y, direction) =
                candidates[Random.nextInt(candidates.size)]

            availableCells.remove(x to y)

            blocks.add(
                Cell(
                    id = i + 1,
                    gridX = x,
                    gridY = y,
                    direction = direction,
                    colorResId = colors[
                        Random.nextInt(colors.size)
                    ]
                )
            )
        }

        positionBlocks()
        invalidate()
    }

    fun loadLevel1() = loadLevel(1)

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

        cellSize = min(w, h) * 0.8f / maxOf(cols, rows)

        offsetX = (w - cellSize * cols) / 2f
        offsetY = (h - cellSize * rows) / 2f

        positionBlocks()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (cellSize <= 0f) return

        canvas.drawColor(Color.rgb(245, 245, 245))

        // Рисуем сетку игрового поля.
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

        // Рисуем цветные блоки.
        for (block in blocks) {
            paint.style = Paint.Style.FILL
            paint.color = block.colorResId

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
        paint.strokeWidth =
            (cellSize * 0.055f).coerceAtLeast(3f)
        paint.strokeCap = Paint.Cap.ROUND

        val endX: Float
        val endY: Float

        when (block.direction) {
            Direction.UP -> {
                endX = cx
                endY = cy - len
                canvas.drawLine(
                    cx, cy + len,
                    endX, endY,
                    paint
                )
            }

            Direction.DOWN -> {
                endX = cx
                endY = cy + len
                canvas.drawLine(
                    cx, cy - len,
                    endX, endY,
                    paint
                )
            }

            Direction.LEFT -> {
                endX = cx - len
                endY = cy
                canvas.drawLine(
                    cx + len, cy,
                    endX, endY,
                    paint
                )
            }

            Direction.RIGHT -> {
                endX = cx + len
                endY = cy
                canvas.drawLine(
                    cx - len, cy,
                    endX, endY,
                    paint
                )
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
        // Проверяем, не мешает ли другой блок движению.
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

        // Удаляем блок, если его путь свободен.
        blocks.remove(block)
        invalidate()

        // Сообщаем о завершении уровня.
        if (blocks.isEmpty()) {
            onGameEventListener?.onLevelCompleted()
        }
    }
}
