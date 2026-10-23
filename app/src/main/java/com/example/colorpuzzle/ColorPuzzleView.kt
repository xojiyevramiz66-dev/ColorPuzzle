
package com.example.colorpuzzle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
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
        var currentY: Float = 0f
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

    private var lastDifficultyMetrics =
        DifficultyMetrics(0, 0, 0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blocks = mutableListOf<Cell>()

    var onGameEventListener: OnGameEventListener? = null

    interface OnGameEventListener {
        fun onWrongMove()
        fun onMoveMade()
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
        val progress =
            ((level.coerceAtLeast(1) - 1) / 120f)
                .coerceIn(0f, 1f)

        return 0.48f + progress * 0.36f
    }

    fun loadLevel(level: Int) {
        currentLevel = level.coerceAtLeast(1)

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
        invalidate()
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
                    put("arrowColor", arrowColor)
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

            if (array.length() > savedCols * savedRows) {
                return false
            }

            val restored = mutableListOf<Cell>()
            val ids = mutableSetOf<Int>()
            val positions = mutableSetOf<Pair<Int, Int>>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                val id = item.getInt("id")
                val x = item.getInt("x")
                val y = item.getInt("y")

                val direction = Direction.valueOf(
                    item.getString("direction")
                )

                val color = item.getInt("color")

                if (
                    id < 1 ||
                    x !in 0 until savedCols ||
                    y !in 0 until savedRows ||
                    color !in colors
                ) {
                    return false
                }

                if (
                    !ids.add(id) ||
                    !positions.add(x to y)
                ) {
                    return false
                }

                restored.add(
                    Cell(
                        id = id,
                        gridX = x,
                        gridY = y,
                        direction = direction,
                        colorResId = color
                    )
                )
            }

            cols = savedCols
            rows = savedRows
            currentLevel = savedLevel

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
        colorResId = colors[Random.nextInt(colors.size)]
    )

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
            val candidate =
                createRandomBranchingAttempt(target)

            if (candidate.size != target) continue
            if (!isSolvable(candidate)) continue

            val metrics = measureDifficulty(candidate)

            val movePenalty =
                (metrics.availableMoves - desiredMoves)
                    .coerceAtLeast(0) * 12

            val depthPenalty =
                abs(metrics.dependencyDepth - desiredDepth) * 3

            val score =
                movePenalty +
                    depthPenalty +
                    Random.nextInt(0, 8)

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

        val fallback = createGuaranteedFallback(target)

        check(fallback.size == target) {
            "Неверное количество блоков"
        }

        check(isSolvable(fallback)) {
            "Резервный уровень не прошёл проверку"
        }

        return fallback
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
                val candidate = createCell(
                    id,
                    x,
                    y,
                    direction
                )

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

                result.add(
                    createCell(id++, x, y, direction)
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
                    1 + (
                        blockers.maxOfOrNull {
                            depthById[it.id] ?: 1
                        } ?: 0
                    )
                }
        }

        val depth = depthById.values.maxOrNull() ?: 0

        return DifficultyMetrics(
            availableMoves = initialMoves,
            dependencyDepth = depth,
            solutionLength = source.size
        )
    }

    private fun blocksOnRay(
        block: Cell,
        other: Cell
    ): Boolean {
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
            block.currentX =
                offsetX + block.gridX * cellSize

            block.currentY =
                offsetY + block.gridY * cellSize
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

    // Псевдо-3D игровое поле.
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(5, 8, 19))

        if (cellSize <= 0f) return

        val depth = cellSize * 0.13f
        val corner = cellSize * 0.12f

        val board = RectF(
            offsetX - depth,
            offsetY - depth,
            offsetX + cols * cellSize + depth,
            offsetY + rows * cellSize + depth
        )

        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            board.left,
            board.top,
            board.right,
            board.bottom,
            Color.rgb(35, 49, 78),
            Color.rgb(8, 13, 28),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(
            board,
            cellSize * 0.17f,
            cellSize * 0.17f,
            paint
        )
        paint.shader = null

        // Металлическая окантовка поля.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (cellSize * 0.018f).coerceAtLeast(1f)
        paint.color = Color.rgb(70, 91, 126)
        canvas.drawRoundRect(
            board,
            cellSize * 0.17f,
            cellSize * 0.17f,
            paint
        )
        paint.style = Paint.Style.FILL

        // Утопленные ячейки.
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val left = offsetX + col * cellSize
                val top = offsetY + row * cellSize

                val cell = RectF(
                    left + cellSize * 0.035f,
                    top + cellSize * 0.035f,
                    left + cellSize * 0.965f,
                    top + cellSize * 0.965f
                )

                paint.color = Color.rgb(3, 7, 17)
                canvas.drawRoundRect(
                    cell,
                    corner,
                    corner,
                    paint
                )

                val inner = RectF(
                    cell.left + cellSize * 0.025f,
                    cell.top + cellSize * 0.025f,
                    cell.right - cellSize * 0.025f,
                    cell.bottom - cellSize * 0.025f
                )

                paint.shader = LinearGradient(
                    inner.left,
                    inner.top,
                    inner.right,
                    inner.bottom,
                    Color.rgb(22, 32, 53),
                    Color.rgb(12, 18, 33),
                    Shader.TileMode.CLAMP
                )

                canvas.drawRoundRect(
                    inner,
                    corner * 0.8f,
                    corner * 0.8f,
                    paint
                )
                paint.shader = null

                paint.style = Paint.Style.STROKE
                paint.strokeWidth =
                    (cellSize * 0.007f).coerceAtLeast(1f)
                paint.color = Color.rgb(40, 55, 82)

                canvas.drawRoundRect(
                    inner,
                    corner * 0.8f,
                    corner * 0.8f,
                    paint
                )
                paint.style = Paint.Style.FILL
            }
        }

        // Объёмные блоки.
        for (block in blocks) {
            val inset = cellSize * 0.085f

            val left = block.currentX + inset
            val top = block.currentY + inset
            val right = block.currentX + cellSize - inset
            val bottom = block.currentY + cellSize - inset
            val base = block.colorResId

            // Тень под блоком.
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(145, 0, 0, 0)
            paint.setShadowLayer(
                cellSize * 0.075f,
                0f,
                depth * 0.55f,
                Color.argb(180, 0, 0, 0)
            )

            canvas.drawRoundRect(
                RectF(left, top, right, bottom),
                corner,
                corner,
                paint
            )
            paint.clearShadowLayer()

            // Передняя грань.
            drawPolygon(
                canvas,
                floatArrayOf(
                    left, bottom - depth,
                    right, bottom - depth,
                    right, bottom,
                    left, bottom
                ),
                shadeColor(base, 0.42f)
            )

            // Правая боковая грань.
            drawPolygon(
                canvas,
                floatArrayOf(
                    right - depth * 0.45f, top - depth,
                    right, top - depth * 0.55f,
                    right, bottom,
                    right - depth * 0.45f, bottom - depth
                ),
                shadeColor(base, 0.55f)
            )

            // Верхняя грань.
            val topFace = RectF(
                left,
                top - depth,
                right - depth * 0.45f,
                bottom - depth
            )

            paint.shader = LinearGradient(
                topFace.left,
                topFace.top,
                topFace.right,
                topFace.bottom,
                shadeColor(base, 1.38f),
                shadeColor(base, 0.88f),
                Shader.TileMode.CLAMP
            )

            canvas.drawRoundRect(
                topFace,
                corner,
                corner,
                paint
            )
            paint.shader = null

            // Светлая фаска.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth =
                (cellSize * 0.014f).coerceAtLeast(1f)
            paint.color = Color.argb(200, 255, 255, 255)

            canvas.drawRoundRect(
                topFace,
                corner,
                corner,
                paint
            )
            paint.style = Paint.Style.FILL

            // Глянцевый блик.
            val highlight = RectF(
                topFace.left + cellSize * 0.07f,
                topFace.top + cellSize * 0.035f,
                topFace.right - cellSize * 0.11f,
                topFace.top + cellSize * 0.105f
            )

            paint.color = Color.argb(75, 255, 255, 255)
            canvas.drawRoundRect(
                highlight,
                cellSize * 0.04f,
                cellSize * 0.04f,
                paint
            )

            // Цветная светящаяся кромка.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth =
                (cellSize * 0.012f).coerceAtLeast(1f)
            paint.color = shadeColor(base, 1.55f)

            canvas.drawRoundRect(
                topFace,
                corner,
                corner,
                paint
            )
            paint.style = Paint.Style.FILL

            // Стрелка на верхней поверхности.
            drawArrow3D(
                canvas,
                block.currentX + cellSize / 2f - depth * 0.2f,
                block.currentY + cellSize / 2f - depth,
                cellSize * 0.23f,
                block.direction
            )
        }

        paint.shader = null
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun shadeColor(
        color: Int,
        factor: Float
    ): Int {
        return Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun drawPolygon(
        canvas: Canvas,
        points: FloatArray,
        color: Int
    ) {
        val path = android.graphics.Path().apply {
            moveTo(points[0], points[1])

            var i = 2
            while (i < points.size) {
                lineTo(points[i], points[i + 1])
                i += 2
            }

            close()
        }

        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.clearShadowLayer()

        canvas.drawPath(path, paint)
    }

    private fun drawArrow3D(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        length: Float,
        direction: Direction
    ) {
        val head = length * 0.43f

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

        val sideX = -dy
        val sideY = dx

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        // Тёмная подложка стрелки.
        paint.strokeWidth =
            (length * 0.34f).coerceAtLeast(2f)
        paint.color = Color.argb(180, 0, 0, 0)

        canvas.drawLine(
            startX + 1f,
            startY + 2f,
            endX + 1f,
            endY + 2f,
            paint
        )

        canvas.drawLine(
            endX,
            endY,
            endX - dx * head + sideX * head,
            endY - dy * head + sideY * head,
            paint
        )

        canvas.drawLine(
            endX,
            endY,
            endX - dx * head - sideX * head,
            endY - dy * head - sideY * head,
            paint
        )

        // Белая поверхность стрелки.
        paint.strokeWidth =
            (length * 0.23f).coerceAtLeast(2f)
        paint.color = Color.WHITE

        canvas.drawLine(
            startX,
            startY,
            endX,
            endY,
            paint
        )

        canvas.drawLine(
            endX,
            endY,
            endX - dx * head + sideX * head,
            endY - dy * head + sideY * head,
            paint
        )

        canvas.drawLine(
            endX,
            endY,
            endX - dx * head - sideX * head,
            endY - dy * head - sideY * head,
            paint
        )

        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
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
        if (
            isPathBlocked(
                block,
                blocks.filter { it.id != block.id }
            )
        ) {
            onGameEventListener?.onWrongMove()
            return
        }

        blocks.remove(block)
        invalidate()

        if (blocks.isEmpty()) {
            onGameEventListener?.onLevelCompleted()
        } else {
            onGameEventListener?.onMoveMade()
        }
    }
}
