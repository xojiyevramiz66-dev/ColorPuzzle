package com.example.colorpuzzle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import org.json.JSONArray
import org.json.JSONObject
import java.util.Stack
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

class ColorPuzzleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Direction {
        UP, DOWN, LEFT, RIGHT
    }

    private data class Cell(
        val id: Int,
        val gridX: Int,
        val gridY: Int,
        val direction: Direction,
        val colorResId: Int,
        var currentX: Float = 0f,
        var currentY: Float = 0f,
        var scale: Float = 0f,
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
    private var lastDifficultyMetrics = DifficultyMetrics(0, 0, 0)

    // Использование флагов сглаживания для идеальной Full HD графики
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blocks = mutableListOf<Cell>()

    var onGameEventListener: OnGameEventListener? = null

    interface OnGameEventListener {
        fun onWrongMove()
        fun onMoveMade()
        fun onLevelCompleted()
    }

    private val colors = intArrayOf(
        Color.rgb(255, 95, 95),
        Color.rgb(56, 178, 172),
        Color.rgb(66, 153, 225),
        Color.rgb(246, 173, 85),
        Color.rgb(159, 122, 234),
        Color.rgb(72, 187, 120),
        Color.rgb(237, 100, 166),
        Color.rgb(113, 128, 150)
    )

    private val arrowColor = Color.WHITE

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
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

    private fun calculateDensity(level: Int): Float {
        val progress = ((level.coerceAtLeast(1) - 1) / 120f).coerceIn(0f, 1f)
        return 0.48f + progress * 0.36f
    }

    fun loadLevel(level: Int) {
        currentLevel = level.coerceAtLeast(1)
        animatingBlockId = null
        historyStack.clear()

        val (newCols, newRows) = calculateBoardSize(currentLevel)
        cols = newCols
        rows = newRows

        val totalCells = cols * rows
        val requestedCount = minOf(
            totalCells - 1,
            (totalCells * calculateDensity(currentLevel)).toInt()
        ).coerceAtLeast(1)

        blocks.clear()
        val rawBlocks = createHardLevel(requestedCount, currentLevel)
        
        rawBlocks.forEach { 
            it.scale = 0f
            it.alpha = 0
        }
        blocks.addAll(rawBlocks)

        lastDifficultyMetrics = measureDifficulty(blocks)

        updateBoardGeometry(width, height)
        positionBlocks()
        invalidate()
        animateLevelEntrance()
    }

    private fun animateLevelEntrance() {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { anim ->
                val progress = anim.animatedValue as Float
                blocks.forEach { block ->
                    block.scale = progress
                    block.alpha = (progress * 255).toInt()
                }
                invalidate()
            }
        }
        animator.start()
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
                    put("direction", block.direction.name)
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
            val expectedSize = calculateBoardSize(savedLevel)
            if (savedCols != expectedSize.first || savedRows != expectedSize.second) return false

            val array = root.getJSONArray("blocks")
            val restored = mutableListOf<Cell>()
            val ids = mutableSetOf<Int>()
            val positions = mutableSetOf<Pair<Int, Int>>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.getInt("id")
                val x = item.getInt("x")
                val y = item.getInt("y")
                val direction = Direction.valueOf(item.getString("direction"))
                val color = item.getInt("color")

                if (id < 1 || x !in 0 until savedCols || y !in 0 until savedRows) return false
                if (!ids.add(id) || !positions.add(x to y)) return false

                restored.add(
                    Cell(
                        id = id,
                        gridX = x,
                        gridY = y,
                        direction = direction,
                        colorResId = color,
                        scale = 1f,
                        alpha = 255
                    )
                )
            }

            cols = savedCols
            rows = savedRows
            currentLevel = savedLevel
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
        return restoreState(previousState)
    }

    private fun createCell(id: Int, x: Int, y: Int, direction: Direction) = Cell(
        id = id,
        gridX = x,
        gridY = y,
        direction = direction,
        colorResId = colors[Random.nextInt(colors.size)],
        scale = 1f,
        alpha = 255
    )

    private fun isPathBlocked(candidate: Cell, others: List<Cell>): Boolean {
        return others.any { other ->
            when (candidate.direction) {
                Direction.UP -> other.gridX == candidate.gridX && other.gridY < candidate.gridY
                Direction.DOWN -> other.gridX == candidate.gridX && other.gridY > candidate.gridY
                Direction.LEFT -> other.gridY == candidate.gridY && other.gridX < candidate.gridX
                Direction.RIGHT -> other.gridY == candidate.gridY && other.gridX > candidate.gridX
            }
        }
    }

    private fun isAvailable(block: Cell, source: List<Cell>): Boolean {
        return !isPathBlocked(block, source.filter { it.id != block.id })
    }

    private fun countAvailableMoves(source: List<Cell>): Int {
        return source.count { isAvailable(it, source) }
    }

    private fun isSolvable(source: List<Cell>): Boolean {
        val remaining = source.toMutableList()
        while (remaining.isNotEmpty()) {
            val removable = remaining.firstOrNull { isAvailable(it, remaining) } ?: return false
            remaining.remove(removable)
        }
        return true
    }

    private fun createHardLevel(requestedCount: Int, level: Int): MutableList<Cell> {
        val target = requestedCount.coerceIn(1, cols * rows - 1)
        val desiredMoves = if (level < 6) 3 else if (level < 20) 2 else 1
        val desiredDepth = if (level < 6) 2 else target / 3

        var bestLevel: MutableList<Cell>? = null
        var bestScore = Int.MAX_VALUE

        for (attempt in 0 until 220) {
            val candidate = createRandomBranchingAttempt(target)
            if (candidate.size != target || !isSolvable(candidate)) continue

            val metrics = measureDifficulty(candidate)
            val score = (metrics.availableMoves - desiredMoves).coerceAtLeast(0) * 12 +
                    abs(metrics.dependencyDepth - desiredDepth) * 3 + Random.nextInt(0, 8)

            if (score < bestScore) {
                bestScore = score
                bestLevel = candidate
            }
            if (metrics.availableMoves <= desiredMoves && metrics.dependencyDepth >= desiredDepth) {
                bestLevel = candidate
                break
            }
        }

        return bestLevel ?: createGuaranteedFallback(target)
    }

    private fun createRandomBranchingAttempt(targetCount: Int): MutableList<Cell> {
        val result = mutableListOf<Cell>()
        val positions = (0 until rows).flatMap { y -> (0 until cols).map { x -> x to y } }.shuffled()
        var id = 1

        for ((x, y) in positions) {
            if (result.size >= targetCount) break
            val directions = Direction.values().toList().shuffled()
            for (direction in directions) {
                val candidate = createCell(id, x, y, direction)
                if (!isPathBlocked(candidate, result)) {
                    result.add(candidate)
                    id++
                    break
                }
            }
        }
        return result
    }

    private fun createGuaranteedFallback(targetCount: Int): MutableList<Cell> {
        val result = mutableListOf<Cell>()
        var id = 1
        for (y in 0 until rows) {
            val leftToRight = Random.nextBoolean()
            val xOrder = if (leftToRight) 0 until cols else (cols - 1 downTo 0)
            val dir = if (leftToRight) Direction.RIGHT else Direction.LEFT
            for (x in xOrder) {
                if (result.size >= targetCount) return result
                result.add(createCell(id++, x, y, dir))
            }
        }
        return result
    }

    private fun measureDifficulty(source: List<Cell>): DifficultyMetrics {
        if (source.isEmpty()) return DifficultyMetrics(0, 0, 0)
        val initialMoves = countAvailableMoves(source)
        val depthById = mutableMapOf<Int, Int>()

        for (block in source.sortedByDescending { it.id }) {
            val blockers = source.filter { other -> other.id > block.id && blocksOnRay(block, other) }
            depthById[block.id] = if (blockers.isEmpty()) 1 else 1 + (blockers.maxOfOrNull { depthById[it.id] ?: 1 } ?: 0)
        }
        return DifficultyMetrics(initialMoves, depthById.values.maxOrNull() ?: 0, source.size)
    }

    private fun blocksOnRay(block: Cell, other: Cell): Boolean {
        return when (block.direction) {
            Direction.UP -> other.gridX == block.gridX && other.gridY < block.gridY
            Direction.DOWN -> other.gridX == block.gridX && other.gridY > block.gridY
            Direction.LEFT -> other.gridY == block.gridY && other.gridX < block.gridX
            Direction.RIGHT -> other.gridY == block.gridY && other.gridX > block.gridX
        }
    }

    private fun updateBoardGeometry(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val availableWidth = w * 0.94f
        val availableHeight = h * 0.90f
        cellSize = min(availableWidth / cols, availableHeight / rows)
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

        // Глубокий трендовый фон
        canvas.drawColor(Color.rgb(244, 247, 250))

        if (cellSize <= 0f) return

        // Подложка игровой доски
        val board = RectF(offsetX, offsetY, offsetX + cols * cellSize, offsetY + rows * cellSize)
        paint.shader = null
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(226, 232, 240)
        canvas.drawRoundRect(board, cellSize * 0.16f, cellSize * 0.16f, paint)

        // Сетка ячеек высокой четкости
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.rgb(203, 213, 225)

        for (col in 0..cols) {
            val x = offsetX + col * cellSize
            canvas.drawLine(x, offsetY, x, offsetY + rows * cellSize, paint)
        }
        for (row in 0..rows) {
            val y = offsetY + row * cellSize
            canvas.drawLine(offsetX, y, offsetX + cols * cellSize, y, paint)
        }

        // Рендеринг блоков с мягким объемом и тенизацией
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

            // Просчет тени с учетом текущей прозрачности
            val shadowAlpha = (50 * (block.alpha / 255f)).toInt()
            paint.setShadowLayer(cellSize * 0.14f * block.scale, 0f, cellSize * 0.07f * block.scale, Color.argb(shadowAlpha, 15, 23, 42))
            paint.style = Paint.Style.FILL
            paint.color = applyAlpha(block.colorResId, block.alpha)

            canvas.drawRoundRect(rect, cellSize * 0.14f * block.scale, cellSize * 0.14f * block.scale, paint)
            paint.clearShadowLayer()

            if (block.alpha > 40) {
                drawFlatArrow(canvas, centerX, centerY, cellSize * 0.23f * block.scale, block.direction, block.alpha)
            }
        }

        paint.shader = null
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun applyAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun drawFlatArrow(canvas: Canvas, cx: Float, cy: Float, length: Float, direction: Direction, alpha: Int) {
        var dx = 0f
        var dy = 0f
        when (direction) {
            Direction.UP -> dy = -1f
            Direction.DOWN -> dy = 1f
            Direction.LEFT -> dx = -1f
            Direction.RIGHT -> dx = 1f
        }

        val startX = cx - dx * length
        val startY = cy - dy * length
        val endX = cx + dx * length
        val endY = cy + dy * length
        val head = length * 0.48f

        paint.shader = null
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (length * 0.28f).coerceAtLeast(2.5f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = applyAlpha(arrowColor, alpha)

        canvas.drawLine(startX, startY, endX, endY, paint)
        canvas.drawLine(endX, endY, endX - dx * head - dy * head, endY - dy * head + dx * head, paint)
        canvas.drawLine(endX, endY, endX - dx * head + dy * head, endY - dy * head - dx * head, paint)

        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (cellSize <= 0f || animatingBlockId != null) return true

                val block = blocks.firstOrNull { item ->
                    event.x >= item.currentX && event.x <= item.currentX + cellSize &&
                            event.y >= item.currentY && event.y <= item.currentY + cellSize
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
        if (isPathBlocked(block, blocks.filter { it.id != block.id })) {
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
        val distance = maxOf(width, height).toFloat()
        var targetX = block.currentX
        var targetY = block.currentY

        when (block.direction) {
            Direction.UP -> targetY -= distance
            Direction.DOWN -> targetY += distance
            Direction.LEFT -> targetX -= distance
            Direction.RIGHT -> targetX += distance
        }

        val startX = block.currentX
        val startY = block.currentY

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = AccelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                if (fraction < 0.2f) {
                    block.scale = 1f - (fraction / 0.2f) * 0.12f
                } else {
                    val progress = (fraction - 0.2f) / 0.8f
                    block.scale = 0.88f + progress * 0.12f
                    block.currentX = startX + (targetX - startX) * progress
                    block.currentY = startY + (targetY - startY) * progress
                    block.alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    blocks.remove(block)
                    animatingBlockId = null
                    invalidate()

                    if (blocks.isEmpty()) {
                        onGameEventListener?.onLevelCompleted()
                    } else {
                        onGameEventListener?.onMoveMade()
                    }
                }
            })
        }
        animator.start()
    }
}
