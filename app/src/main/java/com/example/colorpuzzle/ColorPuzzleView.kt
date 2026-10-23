package com.example.colorpuzzle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
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

    // --- Краски ---
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val blocks = mutableListOf<Cell>()

    // Кэш доступных блоков — пересчитываем только при изменении состояния
    private var availableIds: Set<Int> = emptySet()

    // Пульс-анимация доступных блоков
    private var pulseAnimator: ValueAnimator? = null
    private var pulseFraction = 0f

    // Текущий аниматор хода — для корректной отмены при loadLevel/restoreState
    private var runningAnimator: ValueAnimator? = null

    private val blockInsetFactor = 0.075f
    private val blockRadiusFactor = 0.16f

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
        // НЕ ставим LAYER_TYPE_SOFTWARE — графика работает в hardware
        isHapticFeedbackEnabled = true
        loadLevel(1)
    }

    // =====================================================================
    // Жизненный цикл и пульс
    // =====================================================================

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPulse()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        runningAnimator?.cancel()
        runningAnimator = null
        super.onDetachedFromWindow()
    }

    private fun startPulse() {
        if (pulseAnimator != null) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulseFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseFraction = 0f
    }

    // =====================================================================
    // Геометрия доски и уровни
    // =====================================================================

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
            ((level.coerceAtLeast(1) - 1) / 120f)
                .coerceIn(0f, 1f)

        return 0.48f + progress * 0.36f
    }

    fun loadLevel(level: Int) {
        runningAnimator?.cancel()
        runningAnimator = null

        currentLevel = level.coerceAtLeast(1)
        animatingBlockId = null
        historyStack.clear()

        val (newCols, newRows) =
            calculateBoardSize(currentLevel)

        cols = newCols
        rows = newRows

        val totalCells = cols * rows

        val requestedCount = minOf(
            totalCells - 1,
            (totalCells * calculateDensity(currentLevel)).toInt()
        ).coerceAtLeast(1)

        blocks.clear()
        blocks.addAll(
            createHardLevel(requestedCount, currentLevel)
        )

        lastDifficultyMetrics = measureDifficulty(blocks)

        updateBoardGeometry(width, height)
        positionBlocks()
        refreshAvailable()
        invalidate()
    }

    fun loadLevel1() {
        loadLevel(1)
    }

    // =====================================================================
    // Сериализация
    // =====================================================================

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

            if (
                savedCols != expectedSize.first ||
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
                val direction = Direction.valueOf(item.getString("direction"))
                val color = item.getInt("color")

                if (id < 1 || x !in 0 until savedCols || y !in 0 until savedRows) {
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
                        direction = direction,
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
            refreshAvailable()
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

    // =====================================================================
    // Генерация уровня
    // =====================================================================

    private fun createCell(
        id: Int,
        x: Int,
        y: Int,
        direction: Direction
    ) = Cell(
        id = id,
        gridX = x,
        gridY = y,
        direction = direction,
        colorResId = colors[Random.nextInt(colors.size)],
        scale = 1f,
        alpha = 255
    )

    private fun blocksOnRay(block: Cell, other: Cell): Boolean {
        return when (block.direction) {
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

    private fun isPathBlocked(
        candidate: Cell,
        others: List<Cell>
    ): Boolean {
        return others.any { other -> blocksOnRay(candidate, other) }
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

    private fun countAvailableMoves(
        source: List<Cell>
    ): Int {
        return source.count {
            isAvailable(it, source)
        }
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

    private fun createHardLevel(
        requestedCount: Int,
        level: Int
    ): MutableList<Cell> {
        val target = requestedCount.coerceIn(
            1,
            cols * rows - 1
        )

        val desiredMoves = when {
            level < 6 -> 3
            level < 20 -> 2
            level < 50 -> 2
            else -> 1
        }

        val desiredDepth = when {
            level < 6 -> 2
            level < 20 -> target / 4
            level < 50 -> target / 3
            else -> target / 2
        }.coerceAtLeast(1)

        var bestLevel: MutableList<Cell>? = null
        var bestScore = Int.MAX_VALUE

        val attempts = if (target >= 60) 180 else 260

        for (attempt in 0 until attempts) {
            val candidate = createRandomBranchingAttempt(target)

            if (candidate.size != target) continue
            if (!isSolvable(candidate)) continue

            val metrics = measureDifficulty(candidate)

            val movePenalty =
                (metrics.availableMoves - desiredMoves).coerceAtLeast(0) * 12

            val depthPenalty =
                abs(metrics.dependencyDepth - desiredDepth) * 3

            val score = movePenalty + depthPenalty + Random.nextInt(0, 8)

            if (score < bestScore) {
                bestScore = score
                bestLevel = candidate
            }

            if (
                metrics.availableMoves <= desiredMoves &&
                metrics.dependencyDepth >= desiredDepth
            ) {
                bestLevel = candidate
                break
            }
        }

        val selected = bestLevel

        if (
            selected != null &&
            selected.size == target &&
            isSolvable(selected)
        ) {
            return selected
        }

        return createGuaranteedFallback(target)
    }

    private fun createRandomBranchingAttempt(
        targetCount: Int
    ): MutableList<Cell> {
        val result = mutableListOf<Cell>()

        val positions = (0 until rows)
            .flatMap { y ->
                (0 until cols).map { x -> x to y }
            }
            .shuffled()

        var id = 1

        for ((x, y) in positions) {
            if (result.size >= targetCount) break

            val directions =
                Direction.values().toList().shuffled()

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

    private fun createGuaranteedFallback(
        targetCount: Int
    ): MutableList<Cell> {
        val result = mutableListOf<Cell>()
        val rowOrder = (0 until rows).shuffled()

        var id = 1

        for (y in rowOrder) {
            val leftToRight = Random.nextBoolean()

            val xOrder = if (leftToRight) {
                (0 until cols).toList()
            } else {
                (cols - 1 downTo 0).toList()
            }

            val direction = if (leftToRight) {
                Direction.RIGHT
            } else {
                Direction.LEFT
            }

            for (x in xOrder) {
                if (result.size >= targetCount) {
                    return result
                }

                result.add(createCell(id++, x, y, direction))
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
                other.id > block.id && blocksOnRay(block, other)
            }

            depthById[block.id] =
                if (blockers.isEmpty()) {
                    1
                } else {
                    1 + (blockers.maxOfOrNull { depthById[it.id] ?: 1 } ?: 0)
                }
        }

        val depth = depthById.values.maxOrNull() ?: 0

        return DifficultyMetrics(
            availableMoves = initialMoves,
            dependencyDepth = depth,
            solutionLength = source.size
        )
    }

    // =====================================================================
    // Геометрия
    // =====================================================================

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

    private fun refreshAvailable() {
        availableIds = blocks
            .filter { isAvailable(it, blocks) }
            .mapTo(HashSet()) { it.id }
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
        refreshAvailable()
        invalidate()
    }

    // =====================================================================
    // Отрисовка
    // =====================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cellSize <= 0f) return

        drawBoardBackground(canvas)
        drawGrid(canvas)

        // нижние блоки первыми — корректное перекрытие теней
        for (block in blocks.sortedBy { it.gridY }) {
            drawBlock(canvas, block)
        }

        // сброс состояния красок
        paint.shader = null
        blockPaint.shader = null
        gradientPaint.shader = null
        glowPaint.shader = null
        paint.style = Paint.Style.FILL
        blockPaint.style = Paint.Style.FILL
        gradientPaint.style = Paint.Style.FILL
    }

    private fun drawBoardBackground(canvas: Canvas) {
        val s = cellSize
        val board = RectF(
            offsetX, offsetY,
            offsetX + cols * s,
            offsetY + rows * s
        )
        val radius = s * 0.18f

        // тёмная подложка с диагональным градиентом
        gradientPaint.shader = LinearGradient(
            board.left, board.top, board.right, board.bottom,
            intArrayOf(Color.rgb(30, 40, 62), Color.rgb(16, 22, 36)),
            null,
            Shader.TileMode.CLAMP
        )
        gradientPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(board, radius, radius, gradientPaint)
        gradientPaint.shader = null

        // тонкая внутренняя рамка
        blockPaint.shader = null
        blockPaint.style = Paint.Style.STROKE
        blockPaint.strokeWidth = (s * 0.018f).coerceAtLeast(1.5f)
        blockPaint.color = Color.argb(55, 255, 255, 255)
        val half = blockPaint.strokeWidth / 2f
        canvas.drawRoundRect(
            board.left + half, board.top + half,
            board.right - half, board.bottom - half,
            radius, radius, blockPaint
        )

        // лёгкая «атмосферная» подсветка сверху
        blockPaint.style = Paint.Style.FILL
        blockPaint.color = Color.argb(18, 120, 180, 255)
        canvas.drawRoundRect(
            board.left + s * 0.04f,
            board.top + s * 0.04f,
            board.right - s * 0.04f,
            board.top + s * 0.55f,
            radius * 0.8f, radius * 0.8f, blockPaint
        )
        blockPaint.style = Paint.Style.FILL
    }

    private fun drawGrid(canvas: Canvas) {
        val s = cellSize
        val x0 = offsetX
        val y0 = offsetY
        val x1 = offsetX + cols * s
        val y1 = offsetY + rows * s

        // батч-линии
        val lines = FloatArray((cols + rows + 2) * 4)
        var i = 0
        for (col in 0..cols) {
            val x = x0 + col * s
            lines[i++] = x; lines[i++] = y0; lines[i++] = x; lines[i++] = y1
        }
        for (row in 0..rows) {
            val y = y0 + row * s
            lines[i++] = x0; lines[i++] = y; lines[i++] = x1; lines[i++] = y
        }

        blockPaint.shader = null
        blockPaint.style = Paint.Style.STROKE
        blockPaint.strokeWidth = 1.2f
        blockPaint.color = Color.argb(38, 160, 190, 230)
        canvas.drawLines(lines, blockPaint)
        blockPaint.style = Paint.Style.FILL
    }

    private fun drawBlock(canvas: Canvas, block: Cell) {
        val s = cellSize
        val inset = s * blockInsetFactor
        val left = block.currentX + inset
        val top = block.currentY + inset
        val right = block.currentX + s - inset
        val bottom = block.currentY + s - inset

        // масштаб применяем относительно центра
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val hw = (right - left) / 2f * block.scale
        val hh = (bottom - top) / 2f * block.scale
        val l = cx - hw
        val t = cy - hh
        val r = cx + hw
        val b = cy + hh

        val radius = s * blockRadiusFactor * block.scale
        val alphaF = block.alpha / 255f

        // --- пульс доступных блоков ---
        if (availableIds.contains(block.id)) {
            val glowAlpha = ((28 + 40 * pulseFraction) * alphaF).toInt().coerceIn(0, 255)
            val grow = s * 0.035f * pulseFraction
            glowPaint.color = Color.argb(glowAlpha, 200, 230, 255)
            glowPaint.style = Paint.Style.FILL
            canvas.drawRoundRect(
                l - grow, t - grow, r + grow, b + grow,
                radius + grow, radius + grow, glowPaint
            )
        }

        // --- ambient тень (мягкая, смещённая вниз) ---
        blockPaint.shader = null
        blockPaint.style = Paint.Style.FILL
        blockPaint.color = Color.argb((34 * alphaF).toInt(), 0, 0, 0)
        canvas.drawRoundRect(
            l - s * 0.015f, t + s * 0.06f,
            r + s * 0.015f, b + s * 0.10f,
            radius * 1.1f, radius * 1.1f, blockPaint
        )

        // --- key тень (резкая, ближе) ---
        blockPaint.color = Color.argb((72 * alphaF).toInt(), 0, 0, 0)
        canvas.drawRoundRect(
            l, t + s * 0.03f,
            r, b + s * 0.05f,
            radius, radius, blockPaint
        )

        // --- основной градиент (lighter → base → darker) ---
        val base = applyAlpha(block.colorResId, block.alpha)
        val lighter = applyAlpha(lighten(block.colorResId, 0.24f), block.alpha)
        val darker  = applyAlpha(darken(block.colorResId, 0.22f), block.alpha)

        gradientPaint.shader = LinearGradient(
            l, t, l, b,
            intArrayOf(lighter, base, darker),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        gradientPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(l, t, r, b, radius, radius, gradientPaint)
        gradientPaint.shader = null

        // --- глянцевый блик сверху ---
        val glossBottom = t + (b - t) * 0.42f
        gradientPaint.shader = LinearGradient(
            0f, t, 0f, glossBottom,
            Color.argb((85 * alphaF).toInt(), 255, 255, 255),
            Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(
            l + s * 0.06f, t + s * 0.05f,
            r - s * 0.06f, glossBottom,
            radius * 0.85f, radius * 0.85f, gradientPaint
        )
        gradientPaint.shader = null

        // --- тонкий внутренний контур ---
        blockPaint.style = Paint.Style.STROKE
        blockPaint.strokeWidth = (s * 0.012f).coerceAtLeast(1f)
        blockPaint.color = Color.argb((100 * alphaF).toInt(), 255, 255, 255)
        val hs = blockPaint.strokeWidth / 2f
        canvas.drawRoundRect(
            l + hs, t + hs, r - hs, b - hs,
            radius, radius, blockPaint
        )
        blockPaint.style = Paint.Style.FILL

        // --- стрелка ---
        if (block.alpha > 30) {
            drawFlatArrow(
                canvas, cx, cy,
                s * 0.23f * block.scale,
                block.direction, block.alpha
            )
        }
    }

    private fun drawFlatArrow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        length: Float,
        direction: Direction,
        alpha: Int
    ) {
        var dx = 0f
        var dy = 0f
        when (direction) {
            Direction.UP    -> dy = -1f
            Direction.DOWN  -> dy =  1f
            Direction.LEFT  -> dx = -1f
            Direction.RIGHT -> dx =  1f
        }

        val startX = cx - dx * length
        val startY = cy - dy * length
        val endX   = cx + dx * length
        val endY   = cy + dy * length
        val head = length * 0.48f

        blockPaint.shader = null
        blockPaint.style = Paint.Style.STROKE
        blockPaint.strokeCap = Paint.Cap.ROUND
        blockPaint.strokeJoin = Paint.Join.ROUND

        // glow-подложка
        blockPaint.strokeWidth = length * 1.5f
        blockPaint.color = Color.argb((45 * alpha / 255f).toInt(), 255, 255, 255)
        canvas.drawLine(startX, startY, endX, endY, blockPaint)

        // основная линия
        blockPaint.strokeWidth = (length * 0.28f).coerceAtLeast(2.5f)
        blockPaint.color = applyAlpha(arrowColor, alpha)
        canvas.drawLine(startX, startY, endX, endY, blockPaint)

        // наконечник
        canvas.drawLine(
            endX, endY,
            endX - dx * head - dy * head,
            endY - dy * head + dx * head,
            blockPaint
        )
        canvas.drawLine(
            endX, endY,
            endX - dx * head + dy * head,
            endY - dy * head - dx * head,
            blockPaint
        )

        // круглая точка на хвосте
        blockPaint.style = Paint.Style.FILL
        blockPaint.color = applyAlpha(arrowColor, alpha)
        canvas.drawCircle(startX, startY, length * 0.20f, blockPaint)

        // сброс
        blockPaint.strokeCap = Paint.Cap.BUTT
        blockPaint.strokeJoin = Paint.Join.MITER
    }

    // =====================================================================
    // Цветовые хелперы
    // =====================================================================

    private fun applyAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun lighten(color: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        val r = (Color.red(color)   + (255 - Color.red(color))   * f).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * f).toInt().coerceIn(0, 255)
        val b = (Color.blue(color)  + (255 - Color.blue(color))  * f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun darken(color: Int, factor: Float): Int {
        val f = 1f - factor.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color)   * f).toInt(),
            (Color.green(color) * f).toInt(),
            (Color.blue(color)  * f).toInt()
        )
    }

    // =====================================================================
    // Ввод
    // =====================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true

            MotionEvent.ACTION_CANCEL -> return true

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
        if (
            isPathBlocked(
                block,
                blocks.filter { it.id != block.id }
            )
        ) {
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
                    // Игнорируем, если аниматор уже отменён/заменён
                    if (runningAnimator !== animation) return
                    runningAnimator = null

                    if (blocks.remove(block)) {
                        animatingBlockId = null
                        refreshAvailable()
                        invalidate()

                        if (blocks.isEmpty()) {
                            onGameEventListener?.onLevelCompleted()
                        } else {
                            onGameEventListener?.onMoveMade()
                        }
                    }
                }
            })
        }
        runningAnimator = animator
        animator.start()
    }
}
