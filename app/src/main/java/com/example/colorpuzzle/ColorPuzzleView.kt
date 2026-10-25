package com.example.colorpuzzle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import org.json.JSONArray
import org.json.JSONObject
import java.util.Stack
import kotlin.math.min
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

        // Изогнутые стрелки. Движение определяется в movementVector().
        CURVE_RIGHT_DOWN(0, 0), // ↴ движется вниз
        CURVE_DOWN_LEFT(0, 0),  // ↵ движется влево
        CURVE_LEFT_UP(0, 0),    // Изогнутая стрелка вверх
        CURVE_UP_RIGHT(0, 0)    // Изогнутая стрелка вправо
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
        var alpha: Int = 255
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

    private var lastDifficultyMetrics =
        DifficultyMetrics(0, 0, 0)

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
        Color.rgb(255, 92, 92),
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
        // Безопасный вызов загрузки уровня при инициализации
        try {
            loadLevel(1)
        } catch (_: Exception) {
            // Предотвращаем краш при старте
        }
    }

    private fun movementVector(direction: Direction): Pair<Int, Int> {
        return when (direction) {
            Direction.CURVE_RIGHT_DOWN -> 0 to 1
            Direction.CURVE_DOWN_LEFT -> -1 to 0
            Direction.CURVE_LEFT_UP -> 0 to -1
            Direction.CURVE_UP_RIGHT -> 1 to 0
            else -> direction.dx to direction.dy
        }
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

    private fun calculateDensity(level: Int): Float {
        val progress = (
            (level.coerceAtLeast(1) - 1) / 80f
        ).coerceIn(0f, 1f)

        return 0.45f + progress * 0.45f
    }

    fun loadLevel(level: Int) {
        runningAnimator?.cancel()
        runningAnimator = null

        currentLevel = level.coerceIn(
            1,
            PrebuiltLevelCatalog.MAX_LEVEL
        )

        animatingBlockId = null
        historyStack.clear()
        blocks.clear()

        val prebuilt = try {
            PrebuiltLevelCatalog.load(context, currentLevel)
        } catch (_: Exception) {
            null
        }

        val dimensions = prebuilt?.let {
            if (
                it.cols in 4..10 &&
                it.rows in 4..10 &&
                it.grid.length == it.cols * it.rows
            ) {
                it.cols to it.rows
            } else {
                null
            }
        } ?: calculateBoardSize(currentLevel)

        cols = dimensions.first
        rows = dimensions.second

        val totalCells = cols * rows

        val requestedCount = minOf(
            totalCells - 1,
            (totalCells * calculateDensity(currentLevel)).toInt()
        ).coerceAtLeast(1)

        if (
            prebuilt != null &&
            prebuilt.cols == cols &&
            prebuilt.rows == rows &&
            prebuilt.grid.length == totalCells
        ) {
            prebuilt.grid.forEachIndexed { index, symbol ->
                val directions = directionsForSymbol(symbol)

                if (directions.isNotEmpty()) {
                    val x = index % cols
                    val y = index / cols

                    val colorIndex = (
                        currentLevel.toLong() * 31L +
                            x * 17L +
                            y * 13L
                    ).rem(colors.size.toLong()).toInt()

                    blocks.add(
                        Cell(
                            id = blocks.size + 1,
                            gridX = x,
                            gridY = y,
                            directions = directions,
                            colorResId = colors[colorIndex]
                        )
                    )
                }
            }
        }

        if (blocks.isEmpty()) {
            blocks.clear()
            blocks.addAll(
                createHardLevel(requestedCount, currentLevel)
            )
        }

        lastDifficultyMetrics = measureDifficulty(blocks)

        updateBoardGeometry(width, height)
        positionBlocks()
        invalidate()
    }

    private fun directionsForSymbol(symbol: Char): List<Direction> {
        return when (symbol.uppercaseChar()) {
            'U' -> listOf(Direction.UP)
            'D' -> listOf(Direction.DOWN)
            'L' -> listOf(Direction.LEFT)
            'R' -> listOf(Direction.RIGHT)

            'V' -> listOf(
                Direction.UP,
                Direction.DOWN
            )

            'B' -> listOf(
                Direction.LEFT,
                Direction.RIGHT
            )

            'Q' -> listOf(Direction.CURVE_RIGHT_DOWN)
            'W' -> listOf(Direction.CURVE_DOWN_LEFT)
            'E' -> listOf(Direction.CURVE_LEFT_UP)
            'A' -> listOf(Direction.CURVE_UP_RIGHT)

            else -> emptyList()
        }
    }

    fun loadLevel1() {
        loadLevel(1)
    }

    fun exportState(): String {
        val root = JSONObject()

        root.put("level", currentLevel)
        root.put("cols", cols)
        root.put("rows", rows)

        val array = JSONArray()

        blocks.forEach { block ->
            array.put(
                JSONObject().apply {
                    put("id", block.id)
                    put("x", block.gridX)
                    put("y", block.gridY)
                    put(
                        "directions",
                        block.directions.joinToString(",") {
                            it.name
                        }
                    )
                    put("color", block.colorResId)
                }
            )
        }

        root.put("blocks", array)
        return root.toString()
    }

    fun restoreState(state: String): Boolean {
        return try {
            val root = JSONObject(state)

            val savedLevel = root.getInt("level")
            val savedCols = root.getInt("cols")
            val savedRows = root.getInt("rows")

            if (savedLevel != currentLevel) return false
            if (savedCols != cols || savedRows != rows) return false
            if (savedCols !in 1..10 || savedRows !in 1..10) return false

            val array = root.getJSONArray("blocks")
            val restored = mutableListOf<Cell>()
            val ids = mutableSetOf<Int>()
            val positions = mutableSetOf<Pair<Int, Int>>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                val id = item.getInt("id")
                val x = item.getInt("x")
                val y = item.getInt("y")
                val directionText = item.getString("directions")
                val color = item.getInt("color")

                val directions = directionText
                    .split(",")
                    .map { Direction.valueOf(it) }

                if (
                    id < 1 ||
                    x !in 0 until savedCols ||
                    y !in 0 until savedRows ||
                    directions.isEmpty() ||
                    directions.size > Direction.values().size ||
                    color == Color.TRANSPARENT
                ) {
                    return false
                }

                if (!ids.add(id) || !positions.add(x to y)) {
                    return false
                }

                restored.add(
                    Cell(
                        id = id,
                        gridX = x,
                        gridY = y,
                        directions = directions,
                        colorResId = color,
                        scale = 1f,
                        alpha = 255
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
        if (historyStack.isEmpty() || animatingBlockId != null) {
            return false
        }

        val previousState = historyStack.pop()

        if (!restoreState(previousState)) {
            historyStack.push(previousState)
            return false
        }

        return true
    }

    private fun createCell(
        id: Int,
        x: Int,
        y: Int,
        dirs: List<Direction>
    ) = Cell(
        id = id,
        gridX = x,
        gridY = y,
        directions = dirs,
        colorResId = colors[Random.nextInt(colors.size)],
        scale = 1f,
        alpha = 255
    )

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
                moveDx == 0 && moveDy < 0 ->
                    dx == 0 && dy < 0

                moveDx == 0 && moveDy > 0 ->
                    dx == 0 && dy > 0

                moveDx < 0 && moveDy == 0 ->
                    dy == 0 && dx < 0

                moveDx > 0 && moveDy == 0 ->
                    dy == 0 && dx > 0

                dir == Direction.CORNER_TOP_LEFT ->
                    dx < 0 && dy < 0 && -dx == -dy

                dir == Direction.CORNER_TOP_RIGHT ->
                    dx > 0 && dy < 0 && dx == -dy

                dir == Direction.CORNER_BOTTOM_LEFT ->
                    dx < 0 && dy > 0 && -dx == dy

                dir == Direction.CORNER_BOTTOM_RIGHT ->
                    dx > 0 && dy > 0 && dx == dy

                else -> false
            }
        }
    }

    private fun isPathBlocked(
        candidate: Cell,
        others: List<Cell>
    ): Boolean {
        return candidate.directions.all { direction ->
            isRayBlocked(
                candidate.gridX,
                candidate.gridY,
                direction,
                others
            )
        }
    }

    private fun isAvailable(
        block: Cell,
        source: List<Cell>
    ): Boolean {
        return !isPathBlocked(
            block,
            source.filter { it.id != block.id }
        )
    }

    private fun countAvailableMoves(source: List<Cell>): Int {
        return source.count { isAvailable(it, source) }
    }

    private fun createHardLevel(
        requestedCount: Int,
        level: Int
    ): MutableList<Cell> {
        val target = requestedCount.coerceIn(
            1,
            (cols * rows - 1).coerceAtLeast(1)
        )

        val result = mutableListOf<Cell>()
        val rng = Random(level)

        for (x in 0 until cols) {
            if (result.size >= target) break

            val direction = if (rng.nextBoolean()) {
                Direction.UP
            } else {
                Direction.DOWN
            }

            val columnRows = (0 until rows).shuffled(rng)

            for (y in columnRows) {
                if (result.size >= target) break

                result.add(
                    createCell(
                        id = result.size + 1,
                        x = x,
                        y = y,
                        dirs = listOf(direction)
                    )
                )
            }
        }

        return result
    }

    private fun measureDifficulty(
        source: List<Cell>
    ): DifficultyMetrics {
        if (source.isEmpty()) {
            return DifficultyMetrics(0, 0, 0)
        }

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

        if (cellSize <= 0f) return

        val board = RectF(
            offsetX,
            offsetY,
            offsetX + cols * cellSize,
            offsetY + rows * cellSize
        )

        paint.shader = null
        paint.setShadowLayer(
            cellSize * 0.20f,
            0f,
            cellSize * 0.10f,
            Color.argb(100, 0, 0, 0)
        )

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(22, 30, 46)

        canvas.drawRoundRect(
            board,
            cellSize * 0.16f,
            cellSize * 0.16f,
            paint
        )

        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.color = Color.rgb(55, 71, 103)

        for (col in 0..cols) {
            val x = offsetX + col * cellSize

            canvas.drawLine(
                x,
                offsetY,
                x,
                offsetY + rows * cellSize,
                paint
            )
        }

        for (row in 0..rows) {
            val y = offsetY + row * cellSize

            canvas.drawLine(
                offsetX,
                y,
                offsetX + cols * cellSize,
                y,
                paint
            )
        }

        for (block in blocks) {
            val inset = cellSize * 0.08f

            val baseLeft = block.currentX + inset
            val baseTop = block.currentY + inset
            val baseRight = block.currentX + cellSize - inset
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

            val shadowAlpha = (60 * (block.alpha / 255f)).toInt()

            blockPaint.setShadowLayer(
                cellSize * 0.14f * block.scale,
                0f,
                cellSize * 0.07f * block.scale,
                Color.argb(shadowAlpha, 0, 0, 0)
            )

            blockPaint.style = Paint.Style.FILL
            blockPaint.color = applyAlpha(
                block.colorResId,
                block.alpha
            )

            canvas.drawRoundRect(
                rect,
                cellSize * 0.14f * block.scale,
                cellSize * 0.14f * block.scale,
                blockPaint
            )

            blockPaint.clearShadowLayer()

            if (block.alpha > 30) {
                drawArrow(
                    canvas,
                    centerX,
                    centerY,
                    cellSize * 0.23f * block.scale,
                    block.directions,
                    block.alpha
                )
            }
        }

        paint.shader = null
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
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
            baseX + perpX * headSize * 0.65f,
            baseY + perpY * headSize * 0.65f
        )

        arrowPath.lineTo(
            baseX - perpX * headSize * 0.65f,
            baseY - perpY * headSize * 0.65f
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
        arrowPaint.strokeWidth = (length * 0.32f).coerceAtLeast(3f)
        arrowPaint.strokeCap = Paint.Cap.ROUND
        arrowPaint.strokeJoin = Paint.Join.ROUND

        arrowPaint.setShadowLayer(
            length * 0.15f,
            length * 0.05f,
            length * 0.08f,
            Color.argb(
                (alpha * 0.4f).toInt(),
                0,
                0,
                0
            )
        )

        val curvedRotation = when {
            directions == listOf(Direction.CURVE_RIGHT_DOWN) -> 0f
            directions == listOf(Direction.CURVE_DOWN_LEFT) -> 90f
            directions == listOf(Direction.CURVE_LEFT_UP) -> 180f
            directions == listOf(Direction.CURVE_UP_RIGHT) -> 270f
            else -> null
        }

        if (curvedRotation != null) {
            arrowPaint.style = Paint.Style.STROKE

            canvas.save()
            canvas.rotate(curvedRotation, cx, cy)

            arrowPath.reset()
            arrowPath.moveTo(
                cx - length * 0.65f,
                cy - length * 0.45f
            )
            arrowPath.lineTo(
                cx + length * 0.30f,
                cy - length * 0.45f
            )
            arrowPath.quadTo(
                cx + length * 0.65f,
                cy - length * 0.45f,
                cx + length * 0.65f,
                cy - length * 0.10f
            )
            arrowPath.lineTo(
                cx + length * 0.65f,
                cy + length * 0.58f
            )

            canvas.drawPath(arrowPath, arrowPaint)

            drawArrowHead(
                canvas,
                cx + length * 0.65f,
                cy + length * 0.58f,
                0f,
                1f,
                length * 0.7f
            )

            canvas.restore()

            arrowPaint.clearShadowLayer()
            arrowPaint.style = Paint.Style.FILL
            arrowPaint.textAlign = Paint.Align.LEFT
            arrowPaint.strokeCap = Paint.Cap.BUTT
            arrowPaint.strokeJoin = Paint.Join.MITER
            return
        }

        val headSize = length * 0.7f

        directions.forEach { dir ->
            val (dx, dy) = movementVector(dir)

            val tipX = cx + dx * length
            val tipY = cy + dy * length

            arrowPaint.style = Paint.Style.STROKE

            canvas.drawLine(
                cx - dx * length * 0.6f,
                cy - dy * length * 0.6f,
                tipX - dx * headSize * 0.4f,
                tipY - dy * headSize * 0.4f,
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

        arrowPaint.clearShadowLayer()
        arrowPaint.style = Paint.Style.FILL
        arrowPaint.strokeCap = Paint.Cap.BUTT
        arrowPaint.strokeJoin = Paint.Join.MITER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_CANCEL -> return true

            MotionEvent.ACTION_UP -> {
                if (cellSize <= 0f || animatingBlockId != null) {
                    return true
                }

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

        val freeDirs = block.directions.filter { dir ->
            !isRayBlocked(
                block.gridX,
                block.gridY,
                dir,
                otherBlocks
            )
        }

        if (freeDirs.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                performHapticFeedback(HapticFeedbackConstants.REJECT)
            }

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
            duration = 360
            interpolator = AccelerateInterpolator()

            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float

                if (fraction < 0.2f) {
                    block.scale = 1f - (fraction / 0.2f) * 0.12f
                } else {
                    val progress = (
                        (fraction - 0.2f) / 0.8f
                    ).coerceIn(0f, 1f)

                    block.scale = 0.88f + progress * 0.12f

                    block.currentX =
                        startX + (targetX - startX) * progress

                    block.currentY =
                        startY + (targetY - startY) * progress

                    block.alpha = (
                        (1f - progress) * 255
                    ).toInt().coerceIn(0, 255)
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
}
