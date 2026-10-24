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
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class ColorPuzzleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Direction(val dx: Int, val dy: Int) {
        UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0),
        CORNER_TOP_LEFT(-1, -1),
        CORNER_TOP_RIGHT(1, -1),
        CORNER_BOTTOM_LEFT(-1, 1),
        CORNER_BOTTOM_RIGHT(1, 1);

        val isCorner: Boolean get() = this.ordinal >= CORNER_TOP_LEFT.ordinal
    }

    private data class Cell(
        val id: Int,
        val gridX: Int,
        val gridY: Int,
        val directions: List<Direction>,
        val colorResId: Int,
        val isFragile: Boolean = false,
        val colorGroupId: Int = -1,          // -1 = без группы
        var currentX: Float = 0f,
        var currentY: Float = 0f,
        var scale: Float = 1f,
        var alpha: Int = 255
    )

    private data class Obstacle(
        val gridX: Int,
        val gridY: Int
    )

    private data class DifficultyMetrics(
        val availableMoves: Int,
        val dependencyDepth: Int,
        val solutionLength: Int
    )

    private data class LevelConfig(
        val desiredMoves: Int,
        val desiredDepth: Int,
        val movesSlack: Int,
        val fragileChance: Float,
        val doubleArrowChance: Float,
        val cornerArrowChance: Float,
        val obstacleCount: Int,
        val colorGroupCount: Int
    )

    private var cols = 4
    private var rows = 4
    private var cellSize = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var currentLevel = 1
    private var animatingBlockId: Int? = null

    // Бюджет ходов
    private var movesLeft = 0
    private var initialMovesBudget = 0

    // Активная цветовая группа (-1 = свободный режим)
    private var activeColorGroup = -1

    private val historyStack = Stack<String>()
    private var lastDifficultyMetrics = DifficultyMetrics(0, 0, 0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val fragilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val groupPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private val arrowPath = Path()
    private val tmpRect = RectF()

    private val blocks = mutableListOf<Cell>()
    private val obstacles = mutableListOf<Obstacle>()
    private var runningAnimator: ValueAnimator? = null

    var onGameEventListener: OnGameEventListener? = null

    interface OnGameEventListener {
        fun onWrongMove()
        fun onMoveMade()
        fun onLevelCompleted()
        fun onOutOfMoves()
        fun onMovesChanged(left: Int, total: Int)
        fun onColorGroupChanged(groupId: Int)
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

    private val groupColors = intArrayOf(
        Color.rgb(255, 215, 0),
        Color.rgb(0, 255, 255),
        Color.rgb(255, 105, 180),
        Color.rgb(124, 252, 0),
        Color.rgb(255, 165, 0),
        Color.rgb(138, 43, 226)
    )

    private val arrowColor = Color.WHITE
    private val fragileMarkColor = Color.WHITE
    private val wallColor = Color.rgb(70, 80, 100)
    private val wallCrossColor = Color.rgb(50, 58, 75)

    init {
        isHapticFeedbackEnabled = true
        loadLevel(1)
    }

    // =====================================================================
    // Фактор сложности
    // =====================================================================

    private fun difficultyFactor(level: Int): Float {
        return sqrt(level.coerceAtLeast(1).toFloat())
    }

    private fun computeLevelConfig(level: Int): LevelConfig {
        val factor = difficultyFactor(level)
        val total = cols * rows

        val desiredMoves = when {
            level <= 3 -> 3
            level <= 150 -> 2
            else -> 1
        }

        val desiredDepth = when {
            level <= 3 -> 2
            level <= 10 -> total / 3
            else -> ((total * 0.5f) + factor * 1.5f).toInt()
                .coerceAtMost(total - 1)
                .coerceAtLeast(1)
        }

        val movesSlack = when {
            level <= 10 -> 5
            level <= 50 -> 3
            level <= 150 -> 1
            else -> 0
        }

        val fragileChance = (factor * 0.02f).coerceAtMost(0.25f)

        val doubleArrowChance = if (level < 50) 0f else when {
            level < 70 -> 0.15f
            level < 90 -> 0.25f
            else -> (0.35f + factor * 0.01f).coerceAtMost(0.45f)
        }

        val cornerArrowChance = if (level < 100) 0f else when {
            level < 120 -> 0.15f
            level < 150 -> 0.30f
            else -> (0.45f + factor * 0.01f).coerceAtMost(0.55f)
        }

        val obstacleCount = if (level < 40) 0 else {
            (total * (factor * 0.01f))
                .toInt()
                .coerceAtMost(total / 5)
        }

        val colorGroupCount = if (level < 60) 0 else {
            ((factor * 0.3f).toInt() + 1).coerceAtMost(5)
        }

        return LevelConfig(
            desiredMoves = desiredMoves,
            desiredDepth = desiredDepth,
            movesSlack = movesSlack,
            fragileChance = fragileChance,
            doubleArrowChance = doubleArrowChance,
            cornerArrowChance = cornerArrowChance,
            obstacleCount = obstacleCount,
            colorGroupCount = colorGroupCount
        )
    }

    // =====================================================================
    // Геометрия доски
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
        val progress = ((level.coerceAtLeast(1) - 1) / 80f).coerceIn(0f, 1f)
        return 0.45f + progress * 0.45f
    }

    fun loadLevel(level: Int) {
        runningAnimator?.cancel()
        runningAnimator = null

        currentLevel = level.coerceAtLeast(1)
        animatingBlockId = null
        activeColorGroup = -1
        historyStack.clear()

        val (newCols, newRows) = calculateBoardSize(currentLevel)
        cols = newCols
        rows = newRows

        obstacles.clear()
        blocks.clear()

        val config = computeLevelConfig(currentLevel)

        // 1. Генерируем стены
        generateObstacles(config.obstacleCount)

        // 2. Считаем, сколько блоков поместится (за вычетом стен)
        val freeCells = cols * rows - obstacles.size
        val requestedCount = minOf(
            freeCells - 1,
            (freeCells * calculateDensity(currentLevel)).toInt()
        ).coerceAtLeast(1)

        // 3. Генерируем блоки
        blocks.addAll(createHardLevel(requestedCount, config))

        // 4. Назначаем цветовые группы (с защитой от тупиков)
        assignColorGroups(config.colorGroupCount)

        // 5. Активируем первую группу (если есть)
        activeColorGroup = findNextRequiredGroup()

        lastDifficultyMetrics = measureDifficulty(blocks)

        initialMovesBudget = blocks.size + config.movesSlack
        movesLeft = initialMovesBudget
        onGameEventListener?.onMovesChanged(movesLeft, initialMovesBudget)
        onGameEventListener?.onColorGroupChanged(activeColorGroup)

        updateBoardGeometry(width, height)
        positionBlocks()
        invalidate()
    }

    fun loadLevel1() {
        loadLevel(1)
    }

    fun getMovesLeft(): Int = movesLeft
    fun getInitialMovesBudget(): Int = initialMovesBudget
    fun getActiveColorGroup(): Int = activeColorGroup
    fun getCurrentLevel(): Int = currentLevel

    private fun generateObstacles(count: Int) {
        if (count <= 0) return
        val allPositions = (0 until rows).flatMap { y -> (0 until cols).map { x -> x to y } }
            .shuffled()
        for ((x, y) in allPositions) {
            if (obstacles.size >= count) break
            obstacles.add(Obstacle(x, y))
        }
    }

    private fun assignColorGroups(groupCount: Int) {
        if (groupCount <= 0 || blocks.isEmpty()) return

        for (i in blocks.indices) {
            blocks[i] = blocks[i].copy(colorGroupId = -1)
        }

        var attempts = 0
        while (attempts < 20) {
            attempts++
            val shuffled = blocks.shuffled()
            val groupSize = (shuffled.size / (groupCount + 1)).coerceAtLeast(1)
            var remaining = shuffled.toMutableList()

            for (g in 0 until groupCount) {
                if (remaining.isEmpty()) break
                val take = minOf(groupSize, remaining.size)
                val group = remaining.take(take)
                remaining = remaining.drop(take).toMutableList()

                for (cell in group) {
                    val idx = blocks.indexOfFirst { it.id == cell.id }
                    if (idx >= 0) blocks[idx] = blocks[idx].copy(colorGroupId = g)
                }
            }

            if (isSolvableWithGroups(blocks)) {
                return
            }
        }

        // Если не удалось найти безопасную расстановку — убираем группы вообще
        for (i in blocks.indices) {
            blocks[i] = blocks[i].copy(colorGroupId = -1)
        }
    }

    private fun isSolvableWithGroups(source: List<Cell>): Boolean {
        val remaining = source.toMutableList()
        var currentGroup = remaining.filter { it.colorGroupId >= 0 }.map { it.colorGroupId }.minOrNull() ?: -1
        
        while (remaining.isNotEmpty()) {
            val removable = remaining.firstOrNull { block ->
                val groupMatch = currentGroup == -1 || block.colorGroupId == currentGroup || block.colorGroupId == -1
                val blockedByWall = block.directions.any { dir -> 
                    isRayBlocked(block.gridX, block.gridY, dir, listOf()) 
                }
                groupMatch && !blockedByWall && isAvailable(block, remaining)
            }
            
            if (removable == null) return false 
            remaining.remove(removable)
            
            if (currentGroup != -1 && remaining.none { it.colorGroupId == currentGroup }) {
                currentGroup = remaining.filter { it.colorGroupId > currentGroup }.map { it.colorGroupId }.minOrNull() ?: -1
            }
        }
        return true
    }

    private fun findNextRequiredGroup(): Int {
        val groups = blocks.map { it.colorGroupId }.filter { it >= 0 }
        return groups.minOrNull() ?: -1
    }

    // =====================================================================
    // Сериализация
    // =====================================================================

    fun exportState(): String {
        val root = JSONObject()
        root.put("level", currentLevel)
        root.put("cols", cols)
        root.put("rows", rows)
        root.put("movesLeft", movesLeft)
        root.put("initialMovesBudget", initialMovesBudget)
        root.put("activeColorGroup", activeColorGroup)

        val blocksArray = JSONArray()
        blocks.forEach { block ->
            blocksArray.put(JSONObject().apply {
                put("id", block.id)
                put("x", block.gridX)
                put("y", block.gridY)
                put("directions", block.directions.joinToString(",") { it.name })
                put("color", block.colorResId)
                put("fragile", block.isFragile)
                put("group", block.colorGroupId)
            })
        }
        root.put("blocks", blocksArray)

        val obstaclesArray = JSONArray()
        obstacles.forEach { o ->
            obstaclesArray.put(JSONObject().apply {
                put("x", o.gridX)
                put("y", o.gridY)
            })
        }
        root.put("obstacles", obstaclesArray)

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

            val obsArray = root.optJSONArray("obstacles") ?: JSONArray()
            val newObstacles = mutableListOf<Obstacle>()
            val obsPositions = mutableSetOf<Pair<Int, Int>>()
            for (i in 0 until obsArray.length()) {
                val item = obsArray.getJSONObject(i)
                val x = item.getInt("x")
                val y = item.getInt("y")
                if (x !in 0 until savedCols || y !in 0 until savedRows) return false
                if (!obsPositions.add(x to y)) return false
                newObstacles.add(Obstacle(x, y))
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

                val dirString = item.getString("directions")
                val dirNames = dirString.split(",").filter { it.isNotBlank() }
                if (dirNames.isEmpty() || dirNames.size > 2) return false
                val directions = dirNames.map { Direction.valueOf(it) }
                val color = item.getInt("color")
                val fragile = item.optBoolean("fragile", false)
                val group = item.optInt("group", -1)

                if (id < 1 || x !in 0 until savedCols || y !in 0 until savedRows) return false
                if (!ids.add(id) || !positions.add(x to y)) return false
                if (!colors.contains(color)) return false
                if (obsPositions.contains(x to y)) return false
                if (group < -1) return false

                restored.add(
                    Cell(
                        id = id,
                        gridX = x,
                        gridY = y,
                        directions = directions,
                        colorResId = color,
                        isFragile = fragile,
                        colorGroupId = group,
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
            movesLeft = root.optInt("movesLeft", restored.size)
            initialMovesBudget = root.optInt("initialMovesBudget", restored.size)
            activeColorGroup = root.optInt("activeColorGroup", -1)

            obstacles.clear()
            obstacles.addAll(newObstacles)

            blocks.clear()
            blocks.addAll(restored)
            lastDifficultyMetrics = measureDifficulty(blocks)

            onGameEventListener?.onMovesChanged(movesLeft, initialMovesBudget)
            onGameEventListener?.onColorGroupChanged(activeColorGroup)

            updateBoardGeometry(width, height)
            positionBlocks()
            invalidate()

            true
        } catch (_: Exception) {
            false
        }
    }

    fun undoMove(): Boolean {
        while (historyStack.isNotEmpty()) {
            val previousState = historyStack.pop()
            if (restoreState(previousState)) return true
        }
        return false
    }

    // =====================================================================
    // Генерация уровня
    // =====================================================================

    private fun createCell(
        id: Int,
        x: Int,
        y: Int,
        dirs: List<Direction>,
        fragile: Boolean = false
    ) = Cell(
        id = id,
        gridX = x,
        gridY = y,
        directions = dirs,
        colorResId = colors[Random.nextInt(colors.size)],
        isFragile = fragile,
        colorGroupId = -1,
        scale = 1f,
        alpha = 255
    )

    private fun isRayBlocked(
        candidateX: Int,
        candidateY: Int,
        dir: Direction,
        others: List<Cell>
    ): Boolean {
        if (dir.isCorner) {
            var cx = candidateX + dir.dx
            var cy = candidateY + dir.dy
            while (cx in 0 until cols && cy in 0 until rows) {
                if (obstacles.any { it.gridX == cx && it.gridY == cy }) return true
                if (others.any { it.gridX == cx && it.gridY == cy }) return true
                cx += dir.dx
                cy += dir.dy
            }
            return false
        }
        return others.any { other ->
            val blocked = when (dir) {
                Direction.UP -> other.gridX == candidateX && other.gridY < candidateY
                Direction.DOWN -> other.gridX == candidateX && other.gridY > candidateY
                Direction.LEFT -> other.gridY == candidateY && other.gridX < candidateX
                Direction.RIGHT -> other.gridY == candidateY && other.gridX > candidateX
                else -> false
            }
            if (blocked) return@any true
            obstacles.any { o ->
                when (dir) {
                    Direction.UP -> o.gridX == candidateX && o.gridY < candidateY
                    Direction.DOWN -> o.gridX == candidateX && o.gridY > candidateY
                    Direction.LEFT -> o.gridY == candidateY && o.gridX < candidateX
                    Direction.RIGHT -> o.gridY == candidateY && o.gridX > candidateX
                    else -> false
                }
            }
        }
    }

    private fun isPathBlocked(candidate: Cell, others: List<Cell>): Boolean {
        return candidate.directions.all { dir ->
            isRayBlocked(candidate.gridX, candidate.gridY, dir, others)
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
            val removable = remaining.firstOrNull { block ->
                val blockedByWall = block.directions.any { dir -> 
                    isRayBlocked(block.gridX, block.gridY, dir, listOf())
                }
                !blockedByWall && isAvailable(block, remaining)
            } ?: return false
            remaining.remove(removable)
        }
        return true
    }

    private fun createHardLevel(requestedCount: Int, config: LevelConfig): MutableList<Cell> {
        val target = requestedCount.coerceIn(1, cols * rows - obstacles.size - 1)

        var bestLevel: MutableList<Cell>? = null
        var bestScore = Int.MAX_VALUE

        val attempts = if (target >= 50) 300 else 250

        for (attempt in 0 until attempts) {
            val candidate = createRandomBranchingAttempt(target, config)
            if (candidate.size != target) continue
            if (!isSolvable(candidate)) continue

            val metrics = measureDifficulty(candidate)
            val movePenalty = (metrics.availableMoves - config.desiredMoves).coerceAtLeast(0) * 15
            val depthPenalty = abs(metrics.dependencyDepth - config.desiredDepth) * 5
            val score = movePenalty + depthPenalty + Random.nextInt(0, 5)

            if (score < bestScore) {
                bestScore = score
                bestLevel = candidate
            }

            if (metrics.availableMoves <= config.desiredMoves &&
                metrics.dependencyDepth >= config.desiredDepth
            ) {
                bestLevel = candidate
                break
            }
        }

        val selected = bestLevel
        if (selected != null && selected.size == target && isSolvable(selected)) {
            return selected
        }

        return createGuaranteedFallback(target)
    }

    private fun createRandomBranchingAttempt(
        targetCount: Int,
        config: LevelConfig
    ): MutableList<Cell> {
        val result = mutableListOf<Cell>()
        val positions = (0 until rows)
            .flatMap { y -> (0 until cols).map { x -> x to y } }
            .filter { (x, y) -> !obstacles.any { it.gridX == x && it.gridY == y } }
            .shuffled()
        var id = 1

        for ((x, y) in positions) {
            if (result.size >= targetCount) break

            val rand = Random.nextFloat()
            val possibleDirs = mutableListOf<List<Direction>>()

            if (rand < config.cornerArrowChance) {
                possibleDirs.add(listOf(Direction.CORNER_TOP_LEFT))
                possibleDirs.add(listOf(Direction.CORNER_TOP_RIGHT))
                possibleDirs.add(listOf(Direction.CORNER_BOTTOM_LEFT))
                possibleDirs.add(listOf(Direction.CORNER_BOTTOM_RIGHT))
            } else if (rand < config.cornerArrowChance + config.doubleArrowChance) {
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
                !isPathBlocked(candidate, result) // Убрали 30% шанс спавна тупика
            }.shuffled()

            if (availableDirections.isNotEmpty()) {
                val dirs = availableDirections.first()
                val fragile = Random.nextFloat() < config.fragileChance
                result.add(createCell(id, x, y, dirs, fragile))
                id++
            }
        }
        return result
    }

    private fun createGuaranteedFallback(targetCount: Int): MutableList<Cell> {
        val result = mutableListOf<Cell>()
        var id = 1

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (result.size >= targetCount) return result
                if (obstacles.any { it.gridX == x && it.gridY == y }) continue

                val direction = when {
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

    private fun measureDifficulty(source: List<Cell>): DifficultyMetrics {
        if (source.isEmpty()) return DifficultyMetrics(0, 0, 0)

        val initialMoves = countAvailableMoves(source)
        val depthById = mutableMapOf<Int, Int>()

        for (block in source.sortedByDescending { it.id }) {
            val blockers = source.filter { other ->
                other.id > block.id && block.directions.any { dir ->
                    isRayBlocked(block.gridX, block.gridY, dir, listOf(other))
                }
            }
            depthById[block.id] = if (blockers.isEmpty()) 1
            else 1 + (blockers.maxOfOrNull { depthById[it.id] ?: 1 } ?: 0)
        }

        val depth = depthById.values.maxOrNull() ?: 0

        return DifficultyMetrics(initialMoves, depth, source.size)
    }

    // =====================================================================
    // Геометрия и отрисовка
    // =====================================================================

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
        if (cellSize <= 0f) return

        tmpRect.set(offsetX, offsetY, offsetX + cols * cellSize, offsetY + rows * cellSize)

        paint.shader = null
        paint.setShadowLayer(cellSize * 0.20f, 0f, cellSize * 0.10f, Color.argb(100, 0, 0, 0))
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(22, 30, 46)
        canvas.drawRoundRect(tmpRect, cellSize * 0.16f, cellSize * 0.16f, paint)
        paint.clearShadowLayer()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.color = Color.rgb(55, 71, 103)

        for (col in 0..cols) {
            val x = offsetX + col * cellSize
            canvas.drawLine(x, offsetY, x, offsetY + rows * cellSize, paint)
        }
        for (row in 0..rows) {
            val y = offsetY + row * cellSize
            canvas.drawLine(offsetX, y, offsetX + cols * cellSize, y, paint)
        }

        for (obstacle in obstacles) {
            val left = offsetX + obstacle.gridX * cellSize
            val top = offsetY + obstacle.gridY * cellSize
            val inset = cellSize * 0.06f
            tmpRect.set(left + inset, top + inset, left + cellSize - inset, top + cellSize - inset)

            wallPaint.style = Paint.Style.FILL
            wallPaint.color = wallColor
            canvas.drawRoundRect(tmpRect, cellSize * 0.12f, cellSize * 0.12f, wallPaint)

            wallPaint.style = Paint.Style.STROKE
            wallPaint.strokeWidth = (cellSize * 0.06f).coerceAtLeast(2f)
            wallPaint.strokeCap = Paint.Cap.ROUND
            wallPaint.color = wallCrossColor

            val crossInset = cellSize * 0.22f
            canvas.drawLine(left + crossInset, top + crossInset, left + cellSize - crossInset, top + cellSize - crossInset, wallPaint)
            canvas.drawLine(left + cellSize - crossInset, top + crossInset, left + crossInset, top + cellSize - crossInset, wallPaint)
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

            tmpRect.set(
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
                tmpRect,
                cellSize * 0.14f * block.scale,
                cellSize * 0.14f * block.scale,
                blockPaint
            )
            blockPaint.clearShadowLayer()

            if (block.isFragile && block.alpha > 30) {
                fragilePaint.style = Paint.Style.STROKE
                fragilePaint.strokeWidth = (cellSize * 0.025f * block.scale).coerceAtLeast(1.5f)
                fragilePaint.color = applyAlpha(fragileMarkColor, (block.alpha * 0.85f).toInt())
                canvas.drawRoundRect(
                    tmpRect,
                    cellSize * 0.14f * block.scale,
                    cellSize * 0.14f * block.scale,
                    fragilePaint
                )
            }

            if (block.colorGroupId >= 0 && block.colorGroupId == activeColorGroup && block.alpha > 30) {
                val groupColor = groupColors[block.colorGroupId % groupColors.size]
                groupPaint.style = Paint.Style.STROKE
                groupPaint.strokeWidth = (cellSize * 0.035f * block.scale).coerceAtLeast(2f)
                groupPaint.color = applyAlpha(groupColor, (block.alpha * 0.9f).toInt())
                canvas.drawRoundRect(
                    tmpRect,
                    cellSize * 0.14f * block.scale,
                    cellSize * 0.14f * block.scale,
                    groupPaint
                )
            }

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
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun drawArrowHead(
        canvas: Canvas,
        tipX: Float,
        tipY: Float,
        dirX: Float,
        dirY: Float,
        headSize: Float
    ) {
        val len = hypot(dirX, dirY).coerceAtLeast(0.0001f)
        val nx = dirX / len
        val ny = dirY / len

        val perpX = -ny
        val perpY = nx

        val baseX = tipX - nx * headSize
        val baseY = tipY - ny * headSize

        val wing = headSize * 0.65f

        val p1x = baseX + perpX * wing
        val p1y = baseY + perpY * wing
        val p2x = baseX - perpX * wing
        val p2y = baseY - perpY * wing

        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(p1x, p1y)
        arrowPath.lineTo(p2x, p2y)
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
            val dir1 = directions[0]
            val dir2 = directions[1]

            arrowPaint.style = Paint.Style.STROKE
            val startX = cx + dir1.dx * (length - headSize * 0.4f)
            val startY = cy + dir1.dy * (length - headSize * 0.4f)
            val endX = cx + dir2.dx * (length - headSize * 0.4f)
            val endY = cy + dir2.dy * (length - headSize * 0.4f)
            canvas.drawLine(startX, startY, endX, endY, arrowPaint)

            drawArrowHead(canvas, cx + dir1.dx * length, cy + dir1.dy * length, dir1.dx.toFloat(), dir1.dy.toFloat(), headSize)
            drawArrowHead(canvas, cx + dir2.dx * length, cy + dir2.dy * length, dir2.dx.toFloat(), dir2.dy.toFloat(), headSize)
        } else if (directions.size == 1) {
            val dir = directions.first()

            if (dir.isCorner) {
                val isRight = dir == Direction.CORNER_TOP_RIGHT || dir == Direction.CORNER_BOTTOM_RIGHT
                val isBottom = dir == Direction.CORNER_BOTTOM_LEFT || dir == Direction.CORNER_BOTTOM_RIGHT

                val signX = if (isRight) 1 else -1
                val signY = if (isBottom) 1 else -1

                val r = length * 0.7f

                val tipX = cx + signX * r
                val tipY = cy + signY * r
                val cornerX = cx + signX * r
                val cornerY = cy - signY * r
                val tailX = cx - signX * r
                val tailY = cy - signY * r

                val shrink = headSize * 0.45f
                val lineEndX = tipX
                val lineEndY = tipY - signY * shrink

                arrowPath.reset()
                arrowPath.moveTo(tailX, tailY)
                arrowPath.lineTo(cornerX, cornerY)
                arrowPath.lineTo(lineEndX, lineEndY)

                arrowPaint.style = Paint.Style.STROKE
                canvas.drawPath(arrowPath, arrowPaint)

                drawArrowHead(canvas, tipX, tipY, 0f, signY.toFloat(), headSize)
            } else {
                arrowPaint.style = Paint.Style.STROKE
                val startX = cx - dir.dx * (length - headSize * 0.4f)
                val startY = cy - dir.dy * (length - headSize * 0.4f)
                val endX = cx + dir.dx * (length - headSize * 0.4f)
                val endY = cy + dir.dy * (length - headSize * 0.4f)

                canvas.drawLine(startX, startY, endX, endY, arrowPaint)

                drawArrowHead(canvas, cx + dir.dx * length, cy + dir.dy * length, dir.dx.toFloat(), dir.dy.toFloat(), headSize)
            }
        }

        arrowPaint.clearShadowLayer()
        arrowPaint.style = Paint.Style.FILL
        arrowPaint.strokeCap = Paint.Cap.BUTT
        arrowPaint.strokeJoin = Paint.Join.MITER
    }

    // =====================================================================
    // Ввод и анимация
    // =====================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_CANCEL -> return true
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

    private fun isFragileViolated(block: Cell): Boolean {
        if (!block.isFragile) return false
        return block.directions.all { dir ->
            isRayBlocked(block.gridX, block.gridY, dir, blocks.filter { it.id != block.id })
        }
    }

    private fun evaluateMove(block: Cell) {
        if (activeColorGroup >= 0 && block.colorGroupId != activeColorGroup) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
            onGameEventListener?.onWrongMove()
            return
        }

        if (block.isFragile && isFragileViolated(block)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
            onGameEventListener?.onWrongMove()
            return
        }

        val freeDirs = block.directions.filter { dir ->
            !isRayBlocked(block.gridX, block.gridY, dir, blocks.filter { it.id != block.id })
        }

        if (freeDirs.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
            onGameEventListener?.onWrongMove()
            return
        }

        if (movesLeft <= 0) {
            onGameEventListener?.onOutOfMoves()
            return
        }

        historyStack.push(exportState())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }

        movesLeft--
        onGameEventListener?.onMovesChanged(movesLeft, initialMovesBudget)

        animatingBlockId = block.id

        val distance = maxOf(width, height).toFloat()
        val flightDir = freeDirs.random()

        var targetX = block.currentX
        var targetY = block.currentY
        targetX += flightDir.dx * distance
        targetY += flightDir.dy * distance

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
                    if (runningAnimator !== animation) return
                    runningAnimator = null

                    if (blocks.remove(block)) {
                        animatingBlockId = null

                        val newActive = findNextRequiredGroup()
                        if (newActive != activeColorGroup) {
                            activeColorGroup = newActive
                            onGameEventListener?.onColorGroupChanged(activeColorGroup)
                        }

                        invalidate()
                        if (blocks.isEmpty()) {
                            onGameEventListener?.onLevelCompleted()
                        } else {
                            onGameEventListener?.onMoveMade()
                            if (movesLeft <= 0) {
                                onGameEventListener?.onOutOfMoves()
                            }
                        }
                    }
                }
            })
        }

        runningAnimator = animator
        animator.start()
    }
}
