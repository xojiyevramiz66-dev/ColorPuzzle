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
import kotlin.math.abs
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
        CORNER_BOTTOM_RIGHT(1, 1)
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
    private var lastDifficultyMetrics = DifficultyMetrics(0, 0, 0)

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
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
        loadLevel(1)
    }

    // ================================================================
    // Размер доски и уровни
    // ================================================================

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
        val progress =
            ((level.coerceAtLeast(1) - 1) / 80f).coerceIn(0f, 1f)

        return 0.45f + progress * 0.45f
    }

    fun loadLevel(level: Int) {
        runningAnimator?.cancel()
        runningAnimator = null

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
        blocks.addAll(createHardLevel(requestedCount, currentLevel))

        lastDifficultyMetrics = measureDifficulty(blocks)

        updateBoardGeometry(width, height)
        positionBlocks()
        invalidate()
    }

    fun loadLevel1() {
        loadLevel(1)
    }

    // ================================================================
    // Сохранение и восстановление
    // ================================================================

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
                        block.directions.joinToString(",") { it.name }
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

            val expectedSize = calculateBoardSize(savedLevel)

            if (savedCols != expectedSize.first ||
                savedRows != expectedSize.second
            ) {
                return false
            }

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
                val directions = directionText.split(",").map {
                    Direction.valueOf(it)
                }
                val color = item.getInt("color")

                if (id < 1 ||
                    x !in 0 until savedCols ||
                    y !in 0 until savedRows ||
                    directions.isEmpty()
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

    // ================================================================
    // Логика направления и препятствий
    // ================================================================

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

    private fun isStraightRayBlocked(
        candidateX: Int,
        candidateY: Int,
        dir: Direction,
        others: List<Cell>
    ): Boolean {
        return when (dir) {
            Direction.UP ->
                others.any {
                    it.gridX == candidateX && it.gridY < candidateY
                }

            Direction.DOWN ->
                others.any {
                    it.gridX == candidateX && it.gridY > candidateY
                }

            Direction.LEFT ->
                others.any {
                    it.gridY == candidateY && it.gridX < candidateX
                }

            Direction.RIGHT ->
                others.any {
                    it.gridY == candidateY && it.gridX > candidateX
                }

            else -> false
        }
    }

    private fun isRayBlocked(
        candidateX: Int,
        candidateY: Int,
        dir: Direction,
        others: List<Cell>
    ): Boolean {
        return when (dir) {
            Direction.UP,
            Direction.DOWN,
            Direction.LEFT,
            Direction.RIGHT -> {
                isStraightRayBlocked(candidateX, candidateY, dir, others)
            }

            Direction.CORNER_TOP_LEFT -> {
                isStraightRayBlocked(candidateX, candidateY, Direction.LEFT, others) ||
                    isStraightRayBlocked(candidateX, candidateY, Direction.UP, others)
            }

            Direction.CORNER_TOP_RIGHT -> {
                isStraightRayBlocked(candidateX, candidateY, Direction.RIGHT, others) ||
                    isStraightRayBlocked(candidateX, candidateY, Direction.UP, others)
            }

            Direction.CORNER_BOTTOM_LEFT -> {
                isStraightRayBlocked(candidateX, candidateY, Direction.LEFT, others) ||
                    isStraightRayBlocked(candidateX, candidateY, Direction.DOWN, others)
            }

            Direction.CORNER_BOTTOM_RIGHT -> {
                isStraightRayBlocked(candidateX, candidateY, Direction.RIGHT, others) ||
                    isStraightRayBlocked(candidateX, candidateY, Direction.DOWN, others)
            }
        }
    }

    private fun isPathBlocked(
        candidate: Cell,
        others: List<Cell>
    ): Boolean {
        if (candidate.directions.isEmpty()) return true

        val blockers = others.filter { it.id != candidate.id }

        // Стрелка с двумя направлениями может выйти по любому
        // свободному направлению. Поэтому блокируется, только
        // если заблокированы все её направления.
        return candidate.directions.all { direction ->
            isRayBlocked(
                candidate.gridX,
                candidate.gridY,
                direction,
                blockers
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

    private fun isSolvable(source: List<Cell>): Boolean {
        val remaining = source.toMutableList()

        while (remaining.isNotEmpty()) {
            val removable = remaining.firstOrNull {
                isAvailable(it, remaining)
            } ?: return false

            remaining.remove(removable)
        }

        return true
    }

    // ================================================================
    // Генерация уровней
    // ================================================================

    private fun createHardLevel(
        requestedCount: Int,
        level: Int
    ): MutableList<Cell> {
        val target = requestedCount.coerceIn(1, cols * rows - 1)

        val desiredMoves = when {
            level <= 3 -> 3
            else -> 2
        }

        val desiredDepth = when {
            level <= 3 -> 2
            level <= 10 -> target / 3
            else -> target / 2
        }.coerceAtLeast(1)

        var bestLevel: MutableList<Cell>? = null
        var bestScore = Int.MAX_VALUE

        val attempts = if (target >= 50) 300 else 250

        repeat(attempts) {
            val candidate = createRandomBranchingAttempt(target, level)

            if (candidate.size != target) return@repeat
            if (!isSolvable(candidate)) return@repeat

            val metrics = measureDifficulty(candidate)
            val movePenalty =
                (metrics.availableMoves - desiredMoves).coerceAtLeast(0) * 15
            val depthPenalty =
                abs(metrics.dependencyDepth - desiredDepth) * 5
            val score = movePenalty + depthPenalty + Random.nextInt(0, 5)

            if (score < bestScore) {
                bestScore = score
                bestLevel = candidate
            }

            if (metrics.availableMoves <= desiredMoves &&
                metrics.dependencyDepth >= desiredDepth
            ) {
                bestLevel = candidate
                return@repeat
            }
        }

        val selected = bestLevel

        if (selected != null &&
            selected.size == target &&
            isSolvable(selected)
        ) {
            return selected
        }

        return createGuaranteedFallback(target)
    }

    private fun createRandomBranchingAttempt(
        targetCount: Int,
        level: Int
    ): MutableList<Cell> {
        val result = mutableListOf<Cell>()

        val positions = (0 until rows)
            .flatMap { y -> (0 until cols).map { x -> x to y } }
            .shuffled()

        var id = 1

        for ((x, y) in positions) {
            if (result.size >= targetCount) break

            val doubleArrowChance = when {
                level < 50 -> 0f
                level < 70 -> 0.15f
                level < 90 -> 0.25f
                else -> 0.35f
            }

            val cornerArrowChance = when {
                level < 100 -> 0f
                level < 120 -> 0.15f
                level < 150 -> 0.30f
                else -> 0.45f
            }

            val rand = Random.nextFloat()
            val possibleDirs = mutableListOf<List<Direction>>()

            if (rand < cornerArrowChance) {
                possibleDirs.add(listOf(Direction.CORNER_TOP_LEFT))
                possibleDirs.add(listOf(Direction.CORNER_TOP_RIGHT))
                possibleDirs.add(listOf(Direction.CORNER_BOTTOM_LEFT))
                possibleDirs.add(listOf(Direction.CORNER_BOTTOM_RIGHT))
            } else if (rand < cornerArrowChance + doubleArrowChance) {
                possibleDirs.add(listOf(Direction.LEFT, Direction.RIGHT))
                possibleDirs.add(listOf(Direction.UP, Direction.DOWN))
            } else {
                possibleDirs.add(listOf(Direction.UP))
                possibleDirs.add(listOf(Direction.DOWN))
                possibleDirs.add(listOf(Direction.LEFT))
                possibleDirs.add(listOf(Direction.RIGHT))
            }

            val availableDirections = possibleDirs.filter { dirs ->
                val candidate = createCell(id, x, y, dirs)
                !isPathBlocked(candidate, result)
            }.shuffled()

            if (availableDirections.isNotEmpty()) {
                result.add(createCell(id, x, y, availableDirections.first()))
                id++
            }
        }

        return result
    }

    private fun createGuaranteedFallback(
        targetCount: Int
    ): MutableList<Cell> {
        val result = mutableListOf<Cell>()
        var id = 1

        // Расставляем блоки так, чтобы у каждого был путь наружу.
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (result.size >= targetCount) return result

                val direction = when {
                    x == 0 -> listOf(Direction.LEFT)
                    x == cols - 1 -> listOf(Direction.RIGHT)
                    y == 0 -> listOf(Direction.UP)
                    y == rows - 1 -> listOf(Direction.DOWN)
                    x % 2 == 0 -> listOf(Direction.LEFT)
                    else -> listOf(Direction.RIGHT)
                }

                val candidate = createCell(id, x, y, direction)

                if (!isPathBlocked(candidate, result)) {
                    result.add(candidate)
                    id++
                }
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

        val initialMoves = countAvailableMoves(source)
        val depthById = mutableMapOf<Int, Int>()

        for (block in source.sortedByDescending { it.id }) {
            val blockers = source.filter { other ->
                other.id > block.id &&
                    isRayBlocked(
                        block.gridX,
                        block.gridY,
                        block.directions.first(),
                        listOf(other)
                    )
            }

            depthById[block.id] =
                if (blockers.isEmpty()) {
                    1
                } else {
                    1 + (blockers.maxOfOrNull {
                        depthById[it.id] ?: 1
                    } ?: 0)
                }
        }

        val depth = depthById.values.maxOrNull() ?: 0

        return DifficultyMetrics(initialMoves, depth, source.size)
    }

    // ================================================================
    // Геометрия доски
    // ================================================================

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

    // ================================================================
    // Отрисовка
    // ================================================================

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
                x, offsetY,
                x, offsetY + rows * cellSize,
                paint
            )
        }

        for (row in 0..rows) {
            val y = offsetY + row * cellSize
            canvas.drawLine(
                offsetX, y,
                offsetX + cols * cellSize, y,
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
            blockPaint.color = applyAlpha(block.colorResId, block.alpha)

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
            alpha,
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

        val wing = headSize * 0.65f

        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(
            baseX + perpX * wing,
            baseY + perpY * wing
        )
        arrowPath.lineTo(
            baseX - perpX * wing,
            baseY - perpY * wing
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
        arrowPaint.shader = null
        arrowPaint.color = applyAlpha(arrowColor, alpha)
        arrowPaint.strokeWidth = (length * 0.32f).coerceAtLeast(3f)
        arrowPaint.strokeCap = Paint.Cap.ROUND
        arrowPaint.strokeJoin = Paint.Join.ROUND

        arrowPaint.setShadowLayer(
            length * 0.15f,
            length * 0.05f,
            length * 0.08f,
            Color.argb((alpha * 0.4f).toInt(), 0, 0, 0)
        )

        val headSize = length * 0.7f

        if (directions.size == 2) {
            val first = directions[0]
            val second = directions[1]

            arrowPaint.style = Paint.Style.STROKE

            val startX = cx + first.dx * (length - headSize * 0.4f)
            val startY = cy + first.dy * (length - headSize * 0.4f)
            val endX = cx + second.dx * (length - headSize * 0.4f)
            val endY = cy + second.dy * (length - headSize * 0.4f)

            canvas.drawLine(startX, startY, endX, endY, arrowPaint)

            drawArrowHead(
                canvas,
                cx + first.dx * length,
                cy + first.dy * length,
                first.dx.toFloat(),
                first.dy.toFloat(),
                headSize
            )

            drawArrowHead(
                canvas,
                cx + second.dx * length,
                cy + second.dy * length,
                second.dx.toFloat(),
                second.dy.toFloat(),
                headSize
            )
        } else if (directions.size == 1) {
            val direction = directions.first()

            if (direction.name.startsWith("CORNER")) {
                val isRight =
                    direction == Direction.CORNER_TOP_RIGHT ||
                    direction == Direction.CORNER_BOTTOM_RIGHT

                val isBottom =
                    direction == Direction.CORNER_BOTTOM_LEFT ||
                    direction == Direction.CORNER_BOTTOM_RIGHT

                val r = length * 0.7f

                val tailX = if (isRight) cx - r else cx + r
                val tailY = if (isBottom) cy - r else cy + r

                val tipX = if (isRight) cx + r else cx - r
                val tipY = if (isBottom) cy + r else cy - r

                val cornerX = tipX
                val cornerY = if (isBottom) cy - r else cy + r

                val dirX = if (isRight) 1f else -1f
                val dirY = if (isBottom) 1f else -1f

                val lineEndX = tipX - dirX * headSize * 0.4f
                val lineEndY = tipY - dirY * headSize * 0.4f

                // Настоящая Г-образная линия: два прямых сегмента,
                // а не диагональная линия.
                arrowPath.reset()
                arrowPath.moveTo(tailX, tailY)
                arrowPath.lineTo(cornerX, cornerY)
                arrowPath.lineTo(lineEndX, lineEndY)

                arrowPaint.style = Paint.Style.STROKE
                canvas.drawPath(arrowPath, arrowPaint)

                drawArrowHead(
                    canvas,
                    tipX,
                    tipY,
                    dirX,
                    dirY,
                    headSize
                )
            } else {
                arrowPaint.style = Paint.Style.STROKE

                val startX = cx - direction.dx * (length - headSize * 0.4f)
                val startY = cy - direction.dy * (length - headSize * 0.4f)
                val endX = cx + direction.dx * (length - headSize * 0.4f)
                val endY = cy + direction.dy * (length - headSize * 0.4f)

                canvas.drawLine(startX, startY, endX, endY, arrowPaint)

                drawArrowHead(
                    canvas,
                    cx + direction.dx * length,
                    cy + direction.dy * length,
                    direction.dx.toFloat(),
                    direction.dy.toFloat(),
                    headSize
                )
            }
        }

        arrowPaint.clearShadowLayer()
        arrowPaint.style = Paint.Style.FILL
        arrowPaint.strokeCap = Paint.Cap.BUTT
        arrowPaint.strokeJoin = Paint.Join.MITER
    }

    // ================================================================
    // Обработка нажатий
    // ================================================================

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

    // ================================================================
    // Удаление блока и анимация
    // ================================================================

    private fun evaluateMove(block: Cell) {
        if (animatingBlockId != null) return

        val others = blocks.filter { it.id != block.id }

        val freeDirs = block.directions.filter { direction ->
            !isRayBlocked(
                block.gridX,
                block.gridY,
                direction,
                others
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

        val distance = maxOf(width, height).toFloat()
        val flightDir = freeDirs.random()

        val startX = block.currentX
        val startY = block.currentY

        // Для угловой стрелки движение идёт по двум этапам,
        // чтобы блок не перемещался по диагонали.
        val corner = flightDir.name.startsWith("CORNER")

        val horizontalDirection = when (flightDir) {
            Direction.CORNER_TOP_LEFT,
            Direction.CORNER_BOTTOM_LEFT -> -1

            Direction.CORNER_TOP_RIGHT,
            Direction.CORNER_BOTTOM_RIGHT -> 1

            Direction.LEFT -> -1
            Direction.RIGHT -> 1
            else -> 0
        }

        val verticalDirection = when (flightDir) {
            Direction.CORNER_TOP_LEFT,
            Direction.CORNER_TOP_RIGHT -> -1

            Direction.CORNER_BOTTOM_LEFT,
            Direction.CORNER_BOTTOM_RIGHT -> 1

            Direction.UP -> -1
            Direction.DOWN -> 1
            else -> 0
        }

        val targetX: Float
        val targetY: Float

        if (corner) {
            targetX = startX + horizontalDirection * distance
            targetY = startY + verticalDirection * distance
        } else {
            targetX = startX + horizontalDirection * distance
            targetY = startY + verticalDirection * distance
        }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            interpolator = AccelerateInterpolator()

            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float

                if (corner) {
                    // Первый этап — горизонтальный.
                    // Второй этап — вертикальный.
                    if (fraction < 0.5f) {
                        val progress = fraction / 0.5f
                        block.currentX =
                            startX + (targetX - startX) * progress
                        block.currentY = startY
                    } else {
                        val progress = (fraction - 0.5f) / 0.5f
                        block.currentX = targetX
                        block.currentY =
                            startY + (targetY - startY) * progress
                    }
                } else {
                    block.currentX =
                        startX + (targetX - startX) * fraction
                    block.currentY =
                        startY + (targetY - startY) * fraction
                }

                block.scale = 1f - 0.12f * fraction
                block.alpha =
                    ((1f - fraction) * 255).toInt().coerceIn(0, 255)

                invalidate()
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (runningAnimator !== animation) return

                    runningAnimator = null
                    blocks.remove(block)
                    animatingBlockId = null

                    invalidate()

                    if (blocks.isEmpty()) {
                        onGameEventListener?.onLevelCompleted()
                    } else {
                        onGameEventListener?.onMoveMade()
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (runningAnimator === animation) {
                        runningAnimator = null
                        animatingBlockId = null
                    }
                }
            })
        }

        runningAnimator = animator
        animator.start()
    }
}
