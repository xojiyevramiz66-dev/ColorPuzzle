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
    loadLevel1()
}

fun loadLevel1() {
    blocks.clear()

    // Красный блок: вправо, но путь перекрыт синим.
    blocks.add(
        Cell(
            1, 0, 2,
            Direction.RIGHT,
            Color.parseColor("#FF5252")
        )
    )

    // Синий блок: вверх, но путь перекрыт зелёным.
    blocks.add(
        Cell(
            2, 2, 2,
            Direction.UP,
            Color.parseColor("#448AFF")
        )
    )

    // Зелёный блок можно убрать первым.
    blocks.add(
        Cell(
            3, 2, 0,
            Direction.UP,
            Color.parseColor("#4CAF50")
        )
    )

    positionBlocks()
    invalidate()
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

    val availableSize = min(w, h) * 0.8f
    cellSize = availableSize / maxOf(cols, rows)

    offsetX = (w - cellSize * cols) / 2f
    offsetY = (h - cellSize * rows) / 2f

    positionBlocks()
}

override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)

    if (cellSize <= 0f) return

    // Фон игрового поля.
    canvas.drawColor(Color.rgb(245, 245, 245))

    // Сетка.
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

    // Цветные блоки.
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
            canvas.drawLine(endX, endY, endX - head, endY + head, paint)
            canvas.drawLine(endX, endY, endX + head, endY + head, paint)
        }

        Direction.DOWN -> {
            canvas.drawLine(endX, endY, endX - head, endY - head, paint)
            canvas.drawLine(endX, endY, endX + head, endY - head, paint)
        }

        Direction.LEFT -> {
            canvas.drawLine(endX, endY, endX + head, endY - head, paint)
            canvas.drawLine(endX, endY, endX + head, endY + head, paint)
        }

        Direction.RIGHT -> {
            canvas.drawLine(endX, endY, endX - head, endY - head, paint)
            canvas.drawLine(endX, endY, endX - head, endY + head, paint)
        }
    }

    paint.strokeCap = Paint.Cap.BUTT
    paint.style = Paint.Style.FILL
}

override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.action) {
        MotionEvent.ACTION_DOWN -> {
            return true
        }

        MotionEvent.ACTION_UP -> {
            val touchX = event.x
            val touchY = event.y

            val clickedBlock = blocks.find { block ->
                touchX >= block.currentX &&
                touchX <= block.currentX + cellSize &&
                touchY >= block.currentY &&
                touchY <= block.currentY + cellSize
            }

            if (clickedBlock != null) {
                evaluateMove(clickedBlock)
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
    val pathBlocked = blocks.any { other ->
        if (other.id == block.id) {
            false
        } else {
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
    }

    if (pathBlocked) {
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
