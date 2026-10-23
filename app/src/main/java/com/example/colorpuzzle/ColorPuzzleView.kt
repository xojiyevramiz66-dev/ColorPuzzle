package com.example.colorpuzzle

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class ColorPuzzleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var cols = 4
    private var rows = 4
    private var cellSize = 0f
    private var offsetX = 0f
    private var offsetY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Список блоков на поле
    private val blocks = mutableListOf<Cell>()
    
    // Слушатель событий для связи с Activity
    var onGameEventListener: OnGameEventListener? = null

    interface OnGameEventListener {
        fun onWrongMove()
        fun onLevelCompleted()
    }

    init {
        loadLevel1()
    }

    fun loadLevel1() {
        blocks.clear()
        // Пример начальных блоков: id, x, y, направление, цвет
        blocks.add(Cell(1, 1, 1, Direction.RIGHT, Color.parseColor("#FF5252"))) // Красный
        blocks.add(Cell(2, 2, 2, Direction.UP, Color.parseColor("#448AFF")))    // Синий
        blocks.add(Cell(3, 0, 2, Direction.DOWN, Color.parseColor("#4CAF50")))  // Зеленый
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val availableSize = min(w, h) * 0.8f
        cellSize = availableSize / maxOf(cols, rows)
        offsetX = (w - cellSize * cols) / 2f
        offsetY = (h - cellSize * rows) / 2f

        for (block in blocks) {
            block.currentX = offsetX + block.gridX * cellSize
            block.currentY = offsetY + block.gridY * cellSize
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Рисуем сетку фона
        paint.style = Paint.Style.STROKE
        paint.color = Color.LTGRAY
        paint.strokeWidth = 2f
        for (r in 0..rows) {
            canvas.drawLine(offsetX, offsetY + r * cellSize, offsetX + cols * cellSize, offsetY + r * cellSize, paint)
        }
        for (c in 0..cols) {
            canvas.drawLine(offsetX + c * cellSize, offsetY, offsetX + c * cellSize, offsetY + rows * cellSize, paint)
        }

        // 2. Рисуем блоки со стрелками
        for (block in blocks) {
            paint.style = Paint.Style.FILL
            paint.color = block.colorResId

            val rect = RectF(
                block.currentX + 10f,
                block.currentY + 10f,
                block.currentX + cellSize - 10f,
                block.currentY + cellSize - 10f
            )
            canvas.drawRoundRect(rect, 20f, 20f, paint)

            drawArrow(canvas, block)
        }
    }

    private fun drawArrow(canvas: Canvas, block: Cell) {
        paint.color = Color.WHITE
        paint.strokeWidth = 8f
        val cx = block.currentX + cellSize / 2f
        val cy = block.currentY + cellSize / 2f
        val len = cellSize * 0.25f

        when (block.direction) {
            Direction.UP -> canvas.drawLine(cx, cy + len, cx, cy - len, paint)
            Direction.DOWN -> canvas.drawLine(cx, cy - len, cx, cy + len, paint)
            Direction.LEFT -> canvas.drawLine(cx + len, cy, cx - len, cy, paint)
            Direction.RIGHT -> canvas.drawLine(cx - len, cy, cx + len, cy, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val touchX = event.x
            val touchY = event.y

            val clickedBlock = blocks.find { block ->
                touchX >= block.currentX && touchX <= block.currentX + cellSize &&
                touchY >= block.currentY && touchY <= block.currentY + cellSize
            }

            clickedBlock?.let { evaluateMove(it) }
        }
        return true
    }

    private fun evaluateMove(block: Cell) {
        var targetX = block.gridX
        var targetY = block.gridY

        when (block.direction) {
            Direction.UP -> targetY = 0
            Direction.DOWN -> targetY = rows - 1
            Direction.LEFT -> targetX = 0
            Direction.RIGHT -> targetX = cols - 1
        }

        val pathBlocked = blocks.any { other ->
            other.id != block.id && when (block.direction) {
                Direction.UP -> other.gridX == block.gridX && other.gridY < block.gridY && other.gridY >= targetY
                Direction.DOWN -> other.gridX == block.gridX && other.gridY > block.gridY && other.gridY <= targetY
                Direction.LEFT -> other.gridY == block.gridY && other.gridX < block.gridX && other.gridX >= targetX
                Direction.RIGHT -> other.gridY == block.gridY && other.gridX > block.gridX && other.gridX <= targetX
            }
        }

        if (!pathBlocked) {
            blocks.remove(block)
            invalidate()

            if (blocks.isEmpty()) {
                onGameEventListener?.onLevelCompleted()
            }
        } else {
            onGameEventListener?.onWrongMove()
        }
    }
}
