package com.example.colorpuzzle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import org.json.JSONArray
import org.json.JSONObject
import java.util.Stack
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class ColorPuzzleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Direction(val dx: Int, val dy: Int) {
        UP(0, -1),
        DOWN(0, 1),
        LEFT(-1, 0),
        RIGHT(1, 0),
        CORNER_TOP_LEFT(-1, -1),
        CORNER_TOP_RIGHT(1, -1),
        CORNER_BOTTOM_LEFT(-1, 1),
        CORNER_BOTTOM_RIGHT(1, 1),
        CURVE_RIGHT_DOWN(1, 1),
        CURVE_DOWN_LEFT(-1, 1),
        CURVE_LEFT_UP(-1, -1),
        CURVE_UP_RIGHT(1, -1)
    }

    private data class Cell(
        val id: Int,
        val gridX: Int,
        val gridY: Int,
        val directions: List<Direction>,
        val colorResId: Int,
        var currentX: Float = 0f,
        var currentY: Float = 0f,
        var scale: Float = 1f,
        var alpha: Int = 255,
        var shakeOffset: Float = 0f
    )

    private data class DifficultyMetrics(
        val availableMoves: Int,
        val dependencyDepth: Int,
        val solutionLength: Int
    )

    private var cols = 4
    private var rows = 4
    private var cellSize = 0f
    private var offsetX = 0f
    private var offsetY = 0f

    private var currentLevel = 1
    private var animatingBlockId: Int? = null

    private val historyStack = Stack<String>()

    private var lastDifficultyMetrics = DifficultyMetrics(0, 0, 0)

    private val paint = Paint(
        Paint.ANTI_ALIAS_FLAG or
            Paint.FILTER_BITMAP_FLAG or
            Paint.DITHER_FLAG
    )

    private val blockPaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private val arrowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private val arrowPath = Path()
    private val blocks = mutableListOf<Cell>()

    private var runningAnimator: ValueAnimator? = null

    var onGameEventListener: OnGameEventListener? = null

    interface OnGameEventListener {
        fun onWrongMove()
        fun onMoveMade()
        fun onLevelCompleted()
    }

    private val colors = intArrayOf(
        Color.rgb(255, 107, 107),
        Color.rgb(52, 152, 219),
        Color.rgb(46, 204, 113),
        Color.rgb(241, 196, 15),
        Color.rgb(155, 89, 182),
        Color.rgb(26, 188, 156),
        Color.rgb(230, 126, 34),
        Color.rgb(52, 73, 94)
    )

    private val arrowColor = Color.WHITE

    init {
        isHapticFeedbackEnabled = true

        try {
            loadLevel(1)
        } catch (_: Exception) {
        }
    }

    private fun movementVector(
        direction: Direction
    ): Pair<Int, Int> {
        return when (direction) {
            Direction.CURVE_RIGHT_DOWN -> 0 to 1
            Direction.CURVE_DOWN_LEFT -> -1 to 0
            Direction.CURVE_LEFT_UP -> 0 to -1
            Direction.CURVE_UP_RIGHT -> 1 to 0
            else -> direction.dx to direction.dy
        }
    }

    fun loadLevel(level: Int) {
        val requestedLevel = level.coerceIn(
            1,
            PrebuiltLevelCatalog.MAX_LEVEL
        )

        val prebuilt = try {
            PrebuiltLevelCatalog.load(
                context.applicationContext,
                requestedLevel
            )
        } catch (e: Exception) {
            throw IllegalStateException(
                "Не удалось прочитать готовый уровень $requestedLevel",
                e
            )
        } ?: throw IllegalStateException(
            "Готовый уровень $requestedLevel не найден."
        )

        val newBlocks = mutableListOf<Cell>()

        prebuilt.grid.forEachIndexed { index, symbol ->
            val directions = directionsForSymbol(symbol)

            if (directions.isNotEmpty()) {
                val x = index % prebuilt.cols
                val y = index / prebuilt.cols

                val colorIndex = (
                    requestedLevel.toLong() * 31L +
                        x * 17L +
                        y * 13L
                    ).rem(colors.size.toLong()).toInt()

                newBlocks.add(
                    Cell(
                        id = newBlocks.size + 1,
                        gridX = x,
                        gridY = y,
                        directions = directions,
                        colorResId = colors[colorIndex]
                    )
                )
            }
        }

        runningAnimator?.cancel()
        runningAnimator = null

        currentLevel = requestedLevel
        animatingBlockId = null
        historyStack.clear()

        cols = prebuilt.cols
        rows = prebuilt.rows

        blocks.clear()
        blocks.addAll(newBlocks)

        lastDifficultyMetrics = measureDifficulty(blocks)

        updateBoardGeometry(width, height)
        positionBlocks()
        invalidate()
    }

    private fun directionsForSymbol(
        symbol: Char
    ): List<Direction> {
        return when (symbol.uppercaseChar()) {
            'U' -> listOf(Direction.UP)
            'D' -> listOf(Direction.DOWN)
            'L' -> listOf(Direction.LEFT)
            'R' -> listOf(Direction.RIGHT)
            'V' -> listOf(Direction.UP, Direction.DOWN)
            'B' -> listOf(Direction.LEFT, Direction.RIGHT)
            'Q' -> listOf(Direction.CURVE_RIGHT_DOWN)
            'W' -> listOf(Direction.CURVE_DOWN_LEFT)
            'E' -> listOf(Direction.CURVE_LEFT_UP)
            'A' -> listOf(Direction.CURVE_UP_RIGHT)
            else -> emptyList()
        }
    }

    fun exportState(): String {
        val root = JSONObject().apply {
            put("level", currentLevel)
            put("cols", cols)
            put("rows", rows)
            put("blocks", JSONArray().apply {
                blocks.forEach { block ->
                    put(
                        JSONObject().apply {
                            put("id", block.id)
                            put("x", block.gridX)
                            put("y", block.gridY)
                            put(
                                "directions",
                                block.directions.joinToString(",") { it.name }
                            )
                            put("color", block.colorResId)
                        }
                    )
                }
            })
        }
        return root.toString()
    }

    fun restoreState(state: String): Boolean {
        return try {
            val root = JSONObject(state)

            if (root.getInt("level") != currentLevel) return false
            if (root.getInt("cols") != cols || root.getInt("rows") != rows) return false

            val array = root.getJSONArray("blocks")
            val restored = mutableListOf<Cell>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                restored.add(
                    Cell(
                        id = item.getInt("id"),
                        gridX = item.getInt("x"),
                        gridY = item.getInt("y"),
                        directions = item.getString("directions")
                            .split(",")
                            .map { Direction.valueOf(it) },
                        colorResId = item.getInt("color")
                    )
                )
            }

            runningAnimator?.cancel()
            runningAnimator = null
            animatingBlockId = null

            blocks.clear()
            blocks.addAll(restored)

            lastDifficultyMetrics = measureDifficulty(blocks)
            updateBoardGeometry(width, height)
            positionBlocks()
            invalidate()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun undoMove(): Boolean {
        if (historyStack.isEmpty() || animatingBlockId != null) return false

        val previousState = historyStack.pop()
        if (!restoreState(previousState)) {
            historyStack.push(previousState)
            return false
        }
        return true
    }

    private fun isRayBlocked(
        candidateX: Int,
        candidateY: Int,
        dir: Direction,
        others: List<Cell>
    ): Boolean {
        val (moveDx, moveDy) = movementVector(dir)

        return others.any { other ->
            val dx = other.gridX - candidateX
            val dy = other.gridY - candidateY

            when {
                moveDx == 0 && moveDy < 0 -> dx == 0 && dy < 0
                moveDx == 0 && moveDy > 0 -> dx == 0 && dy > 0
                moveDx < 0 && moveDy == 0 -> dy == 0 && dx < 0
                moveDx > 0 && moveDy == 0 -> dy == 0 && dx > 0
                else -> false
            }
        }
    }

    private fun isAvailable(
        block: Cell,
        source: List<Cell>
    ): Boolean {
        val others = source.filter { it.id != block.id }
        return block.directions.any { !isRayBlocked(block.gridX, block.gridY, it, others) }
    }

    private fun countAvailableMoves(source: List<Cell>): Int {
        return source.count { isAvailable(it, source) }
    }

    private fun measureDifficulty(source: List<Cell>): DifficultyMetrics {
        return DifficultyMetrics(
            availableMoves = countAvailableMoves(source),
            dependencyDepth = 1,
            solutionLength = source.size
        )
    }

    private fun updateBoardGeometry(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || cols <= 0 || rows <= 0) return

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBoardGeometry(w, h)
        positionBlocks()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (cellSize <= 0f) return

        // Отрисовка подложки доски
        val board = RectF(
            offsetX,
            offsetY,
            offsetX + cols * cellSize,
            offsetY + rows * cellSize
        )

        paint.shader = null
        paint.setShadowLayer(
            cellSize * 0.22f,
            0f,
            cellSize * 0.10f,
            Color.argb(50, 0, 0, 0)
        )

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(24, 32, 50)

        canvas.drawRoundRect(
            board,
            cellSize * 0.18f,
            cellSize * 0.18f,
            paint
        )

        // Тонкая сетка доски
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.argb(35, 255, 255, 255)

        for (col in 0..cols) {
            val x = offsetX + col * cellSize
            canvas.drawLine(x, offsetY, x, offsetY + rows * cellSize, paint)
        }

        for (row in 0..rows) {
            val y = offsetY + row * cellSize
            canvas.drawLine(offsetX, y, offsetX + cols * cellSize, y, paint)
        }

        // Отрисовка блоков
        for (block in blocks) {
            val inset = cellSize * 0.08f

            val baseLeft = block.currentX + inset + block.shakeOffset
            val baseTop = block.currentY + inset
            val baseRight = block.currentX + cellSize - inset + block.shakeOffset
            val baseBottom = block.currentY + cellSize - inset

            val currentWidth = (baseRight - baseLeft) * block.scale
            val currentHeight = (baseBottom - baseTop) * block.scale

            val centerX = (baseLeft + baseRight) / 2f
            val centerY = (baseTop + baseBottom) / 2f

            val rect = RectF(
                centerX - currentWidth / 2f,
                centerY - currentHeight / 2f,
                centerX + currentWidth / 2f,
                centerY + currentHeight / 2f
            )

            val shadowAlpha = (70 * (block.alpha / 255f)).toInt()

            blockPaint.setShadowLayer(
                cellSize * 0.16f * block.scale,
                0f,
                cellSize * 0.08f * block.scale,
                Color.argb(shadowAlpha, 0, 0, 0)
            )

            // Эффект красивого градиента плитки
            val baseColor = applyAlpha(block.colorResId, block.alpha)
            val highlightColor = applyAlpha(getLighterColor(block.colorResId), block.alpha)

            blockPaint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(highlightColor, baseColor),
                null,
                Shader.TileMode.CLAMP
            )

            blockPaint.style = Paint.Style.FILL
            canvas.drawRoundRect(
                rect,
                cellSize * 0.16f * block.scale,
                cellSize * 0.16f * block.scale,
                blockPaint
            )

            blockPaint.shader = null
            blockPaint.clearShadowLayer()

            if (block.alpha > 30) {
                drawArrow(
                    canvas,
                    centerX,
                    centerY,
                    cellSize * 0.22f * block.scale,
                    block.directions,
                    block.alpha
                )
            }
        }
    }

    private fun getLighterColor(color: Int): Int {
        val r = (Color.red(color) + 45).coerceAtMost(255)
        val g = (Color.green(color) + 45).coerceAtMost(255)
        val b = (Color.blue(color) + 45).coerceAtMost(255)
        return Color.rgb(r, g, b)
    }

    private fun applyAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun drawArrowHead(
        canvas: Canvas,
        tipX: Float,
        tipY: Float,
        dirX: Float,
        dirY: Float,
        headSize: Float
    ) {
        val perpX = -dirY
        val perpY = dirX

        val baseX = tipX - dirX * headSize
        val baseY = tipY - dirY * headSize

        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(
            baseX + perpX * headSize * 0.55f,
            baseY + perpY * headSize * 0.55f
        )
        arrowPath.lineTo(
            baseX - perpX * headSize * 0.55f,
            baseY - perpY * headSize * 0.55f
        )
        arrowPath.close()

        arrowPaint.style = Paint.Style.FILL
        canvas.drawPath(arrowPath, arrowPaint)
    }

    private fun drawArrow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        length: Float,
        directions: List<Direction>,
        alpha: Int
    ) {
        if (directions.isEmpty()) return

        arrowPaint.shader = null
        arrowPaint.color = applyAlpha(arrowColor, alpha)
        arrowPaint.strokeWidth = (length * 0.30f).coerceAtLeast(3f)
        arrowPaint.strokeCap = Paint.Cap.ROUND
        arrowPaint.strokeJoin = Paint.Join.ROUND

        val headSize = length * 0.65f

        directions.forEach { dir ->
            val (dx, dy) = movementVector(dir)
            val tipX = cx + dx * length * 1.05f
            val tipY = cy + dy * length * 1.05f

            arrowPaint.style = Paint.Style.STROKE
            canvas.drawLine(
                cx - dx * length * 0.5f,
                cy - dy * length * 0.5f,
                tipX - dx * headSize * 0.35f,
                tipY - dy * headSize * 0.35f,
                arrowPaint
            )

            drawArrowHead(
                canvas,
                tipX,
                tipY,
                dx.toFloat(),
                dy.toFloat(),
                headSize
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (cellSize <= 0f || animatingBlockId != null) return true

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
        val otherBlocks = blocks.filter { it.id != block.id }
        val freeDirs = block.directions.filter { !isRayBlocked(block.gridX, block.gridY, it, otherBlocks) }

        if (freeDirs.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
            triggerShakeAnimation(block)
            onGameEventListener?.onWrongMove()
            return
        }

        historyStack.push(exportState())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }

        animatingBlockId = block.id

        val flightDir = freeDirs.random()
        val (moveDx, moveDy) = movementVector(flightDir)

        val startX = block.currentX
        val startY = block.currentY

        val distance = maxOf(width, height).toFloat() +
            cellSize * maxOf(cols, rows)

        val targetX = startX + moveDx * distance
        val targetY = startY + moveDy * distance

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float

                if (fraction < 0.15f) {
                    block.scale = 1f - (fraction / 0.15f) * 0.1f
                } else {
                    val progress = ((fraction - 0.15f) / 0.85f).coerceIn(0f, 1f)
                    block.scale = 0.9f + progress * 0.1f

                    block.currentX = startX + (targetX - startX) * progress
                    block.currentY = startY + (targetY - startY) * progress
                    block.alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
                }

                invalidate()
            }

            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (runningAnimator !== animation) return
                        runningAnimator = null

                        if (blocks.remove(block)) {
                            animatingBlockId = null
                            invalidate()

                            if (blocks.isEmpty()) {
                                onGameEventListener?.onLevelCompleted()
                            } else {
                                onGameEventListener?.onMoveMade()
                            }
                        }
                    }
                }
            )
        }

        runningAnimator = animator
        animator.start()
    }

    private fun triggerShakeAnimation(block: Cell) {
        val shakeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                block.shakeOffset = sin(v * Math.PI.toFloat() * 4f) * (cellSize * 0.04f)
                invalidate()
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        block.shakeOffset = 0f
                        invalidate()
                    }
                }
            )
        }
        shakeAnim.start()
    }
}
