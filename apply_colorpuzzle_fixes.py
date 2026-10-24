#!/usr/bin/env python3
# Apply targeted ColorPuzzle fixes from the repository root:
#   python apply_colorpuzzle_fixes.py
import re
import sys
from pathlib import Path

ROOT = Path.cwd()
VIEW = ROOT / "app/src/main/java/com/example/colorpuzzle/ColorPuzzleView.kt"
TEST = ROOT / "app/src/test/java/com/example/colorpuzzle/PuzzleRulesTest.kt"
ANDROID_TEST = ROOT / "app/src/androidTest/java/com/example/colorpuzzle/ColorPuzzleLevelSmokeTest.kt"
RULES = ROOT / "app/src/main/java/com/example/colorpuzzle/PuzzleRules.kt"

def fail(message):
    print("ERROR:", message, file=sys.stderr)
    raise SystemExit(2)

if not VIEW.exists():
    fail("Run this script from the ColorPuzzle repository root; ColorPuzzleView.kt was not found.")
if RULES.exists() or TEST.exists() or ANDROID_TEST.exists():
    fail("One or more target files already exist. Back them up/remove them before applying.")

source = VIEW.read_text(encoding="utf-8")
original = source
pattern = re.compile(
    r"    private enum class Direction\(val dx: Int, val dy: Int\) \{.*?\n    \}\n\n"
    r"    private data class Cell\(.*?\n    \)\n\n"
    r"    private data class DifficultyMetrics", re.S)
source, count = pattern.subn("    private data class DifficultyMetrics", source, count=1)
if count != 1:
    fail("Could not locate nested Direction/Cell declarations.")

source = re.sub(r"\bDirection\b", "BoardDirection", source)
source = re.sub(r"\bCell\b", "BoardCell", source)

def replace_one(pattern, replacement, text, label):
    result, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        fail("Could not replace " + label)
    return result

source = replace_one(
    r"    private fun calculateBoardSize\(level: Int\): Pair<Int, Int> \{.*?\n    \}",
    "    private fun calculateBoardSize(level: Int): Pair<Int, Int> =\n"
    "        PuzzleRules.boardSize(level)\n",
    source, "calculateBoardSize()")
source = replace_one(
    r"    private fun isRayBlocked\(candidateX: Int, candidateY: Int, dir: BoardDirection, others: List<BoardCell>\): Boolean \{.*?\n    \}",
    "    private fun isRayBlocked(candidateX: Int, candidateY: Int, dir: BoardDirection, others: List<BoardCell>): Boolean =\n"
    "        PuzzleRules.isRayBlocked(candidateX, candidateY, dir, others)\n",
    source, "isRayBlocked()")
source = replace_one(
    r"    private fun isPathBlocked\(candidate: BoardCell, others: List<BoardCell>\): Boolean \{.*?\n    \}",
    "    private fun isPathBlocked(candidate: BoardCell, others: List<BoardCell>): Boolean =\n"
    "        PuzzleRules.isPathBlocked(candidate, others)\n",
    source, "isPathBlocked()")
source = replace_one(
    r"    private fun isAvailable\(block: BoardCell, source: List<BoardCell>\): Boolean \{.*?\n    \}",
    "    private fun isAvailable(block: BoardCell, source: List<BoardCell>): Boolean =\n"
    "        PuzzleRules.isAvailable(block, source)\n",
    source, "isAvailable()")
source = replace_one(
    r"    private fun isSolvable\(source: List<BoardCell>\): Boolean \{.*?\n    \}",
    "    private fun isSolvable(source: List<BoardCell>): Boolean =\n"
    "        PuzzleRules.isSolvable(source)\n",
    source, "isSolvable()")

source = source.replace(
    "        val attempts = if (target >= 50) 300 else 250",
    "        val attempts = if (target >= 50) 60 else 100")
source = replace_one(
    r"    private fun createGuaranteedFallback\(targetCount: Int\): MutableList<BoardCell> \{.*?\n    \}",
    "    private fun createGuaranteedFallback(targetCount: Int): MutableList<BoardCell> {\n"
    "        return PuzzleRules.createGuaranteedFallback(cols, rows, targetCount).map { item ->\n"
    "            createCell(item.id, item.x, item.y, listOf(item.direction))\n"
    "        }.toMutableList()\n"
    "    }",
    source, "createGuaranteedFallback()")

needle = "            runningAnimator?.cancel()\n            runningAnimator = null\n\n            cols = savedCols"
validation = (
    "            if (!PuzzleRules.isValidBlockSet(savedCols, savedRows, restored, colors)) return false\n\n"
    "            runningAnimator?.cancel()\n"
    "            runningAnimator = null\n\n"
    "            cols = savedCols")
if needle not in source:
    fail("Could not locate restoreState() validation point.")
source = source.replace(needle, validation, 1)

pattern = r'            if \(dir\.name\.startsWith\("CORNER"\)\) \{.*?\n            \} else \{'
replacement = (
    '            if (dir.name.startsWith("CORNER")) {\n'
    '                arrowPaint.style = Paint.Style.STROKE\n'
    '                val startX = cx - dir.dx * (length - headSize * 0.4f)\n'
    '                val startY = cy - dir.dy * (length - headSize * 0.4f)\n'
    '                val endX = cx + dir.dx * (length - headSize * 0.4f)\n'
    '                val endY = cy + dir.dy * (length - headSize * 0.4f)\n'
    '                canvas.drawLine(startX, startY, endX, endY, arrowPaint)\n'
    '                drawArrowHead(canvas, cx + dir.dx * length, cy + dir.dy * length,\n'
    '                    dir.dx.toFloat(), dir.dy.toFloat(), headSize)\n'
    '            } else {')
source = replace_one(pattern, replacement, source, "corner-arrow drawing")
source = replace_one(
    r"        when \(flightDir\) \{.*?\n        \}",
    "        targetX += flightDir.dx * distance\n"
    "        targetY += flightDir.dy * distance",
    source, "flight direction")

if source == original:
    fail("No source changes generated.")
if "private enum class Direction" in source or "private data class Cell" in source:
    fail("Direction/Cell refactor did not complete.")

rules = r"""package com.example.colorpuzzle

import kotlin.math.abs

internal enum class BoardDirection(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0),
    CORNER_TOP_LEFT(-1, -1), CORNER_TOP_RIGHT(1, -1),
    CORNER_BOTTOM_LEFT(-1, 1), CORNER_BOTTOM_RIGHT(1, 1)
}

internal data class BoardCell(
    val id: Int,
    val gridX: Int,
    val gridY: Int,
    val directions: List<BoardDirection>,
    val colorResId: Int,
    var currentX: Float = 0f,
    var currentY: Float = 0f,
    var scale: Float = 1f,
    var alpha: Int = 255
)

internal data class FallbackBlock(
    val id: Int,
    val x: Int,
    val y: Int,
    val direction: BoardDirection
)

internal object PuzzleRules {
    fun boardSize(level: Int): Pair<Int, Int> {
        var stage = (level.coerceAtLeast(1) - 1) / 5
        for (first in 4..10) {
            val stagesInRow = 11 - first
            if (stage < stagesInRow) return first to (first + stage)
            stage -= stagesInRow
        }
        return 10 to 10
    }

    fun isRayBlocked(candidateX: Int, candidateY: Int, dir: BoardDirection, others: List<BoardCell>): Boolean =
        others.any { other ->
            val dx = other.gridX - candidateX
            val dy = other.gridY - candidateY
            when {
                dir.dx == 0 -> dx == 0 && dy * dir.dy > 0
                dir.dy == 0 -> dy == 0 && dx * dir.dx > 0
                else -> abs(dx) == abs(dy) && dx * dir.dx > 0 && dy * dir.dy > 0
            }
        }

    fun isPathBlocked(candidate: BoardCell, others: List<BoardCell>): Boolean =
        candidate.directions.isEmpty() || candidate.directions.all { direction ->
            isRayBlocked(candidate.gridX, candidate.gridY, direction, others)
        }

    fun isAvailable(block: BoardCell, source: List<BoardCell>): Boolean =
        !isPathBlocked(block, source.filter { it.id != block.id })

    fun isSolvable(source: List<BoardCell>): Boolean {
        val remaining = source.toMutableList()
        while (remaining.isNotEmpty()) {
            val removable = remaining.firstOrNull { isAvailable(it, remaining) } ?: return false
            remaining.remove(removable)
        }
        return true
    }

    fun createGuaranteedFallback(cols: Int, rows: Int, targetCount: Int): List<FallbackBlock> {
        require(cols > 0 && rows > 0)
        val count = targetCount.coerceIn(1, cols * rows)
        val result = ArrayList<FallbackBlock>(count)
        var id = 1
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (result.size == count) return result
                result.add(FallbackBlock(id++, x, y, BoardDirection.RIGHT))
            }
        }
        return result
    }

    fun isValidBlockSet(cols: Int, rows: Int, blocks: List<BoardCell>, validColors: IntArray): Boolean {
        if (cols <= 0 || rows <= 0) return false
        val capacity = cols * rows
        if (blocks.isEmpty() || blocks.size > capacity) return false
        val ids = HashSet<Int>()
        val positions = HashSet<Pair<Int, Int>>()
        val colors = validColors.toSet()
        for (block in blocks) {
            if (block.id !in 1..capacity) return false
            if (block.gridX !in 0 until cols || block.gridY !in 0 until rows) return false
            if (!ids.add(block.id) || !positions.add(block.gridX to block.gridY)) return false
            if (block.directions.isEmpty() || block.directions.size > 2) return false
            if (block.directions.distinct().size != block.directions.size) return false
            if (block.directions.size == 2) {
                val dirs = block.directions.toSet()
                val verticalPair = dirs == setOf(BoardDirection.UP, BoardDirection.DOWN)
                val horizontalPair = dirs == setOf(BoardDirection.LEFT, BoardDirection.RIGHT)
                if (!verticalPair && !horizontalPair) return false
            }
            if (block.colorResId !in colors) return false
        }
        return true
    }
}
"""
unit_tests = r"""package com.example.colorpuzzle

import org.junit.Assert.*
import org.junit.Test

class PuzzleRulesTest {
    private fun block(id: Int, x: Int, y: Int, direction: BoardDirection, color: Int = 1) =
        BoardCell(id, x, y, listOf(direction), color)

    @Test fun diagonalRayOnlyBlocksCellsOnItsDiagonal() {
        val diagonal = block(2, 2, 2, BoardDirection.UP)
        assertTrue(PuzzleRules.isRayBlocked(1, 1, BoardDirection.CORNER_BOTTOM_RIGHT, listOf(diagonal)))
        val sameRowOnly = block(3, 2, 1, BoardDirection.UP)
        assertFalse(PuzzleRules.isRayBlocked(1, 1, BoardDirection.CORNER_BOTTOM_RIGHT, listOf(sameRowOnly)))
    }

    @Test fun rayOnlyBlocksInTheArrowDirection() {
        assertTrue(PuzzleRules.isRayBlocked(1, 1, BoardDirection.RIGHT, listOf(block(2, 3, 1, BoardDirection.UP))))
        assertFalse(PuzzleRules.isRayBlocked(1, 1, BoardDirection.RIGHT, listOf(block(2, 0, 1, BoardDirection.UP))))
        assertFalse(PuzzleRules.isRayBlocked(1, 1, BoardDirection.RIGHT, listOf(block(2, 3, 2, BoardDirection.UP))))
    }

    @Test fun blockIsAvailableWhenAtLeastOneDirectionIsFree() {
        val multi = BoardCell(1, 1, 1, listOf(BoardDirection.UP, BoardDirection.RIGHT), 1)
        val blocker = block(2, 1, 0, BoardDirection.DOWN)
        assertTrue(PuzzleRules.isAvailable(multi, listOf(multi, blocker)))
    }

    @Test fun fallbackHasExactCountAndIsSolvableForLevels1To200() {
        for (level in 1..200) {
            val (cols, rows) = PuzzleRules.boardSize(level)
            val count = (cols * rows * 0.8f).toInt().coerceIn(1, cols * rows)
            val fallback = PuzzleRules.createGuaranteedFallback(cols, rows, count)
            assertEquals("wrong fallback count at level $level", count, fallback.size)
            val cells = fallback.map { BoardCell(it.id, it.x, it.y, listOf(it.direction), 1) }
            assertTrue("unsolvable fallback at level $level", PuzzleRules.isSolvable(cells))
        }
    }

    @Test fun boardSizesStayWithinBoundsForLevels1To200() {
        for (level in 1..200) {
            val (cols, rows) = PuzzleRules.boardSize(level)
            assertTrue("invalid columns at level $level", cols in 4..10)
            assertTrue("invalid rows at level $level", rows in 4..10)
        }
    }

    @Test fun invalidSavedBlockSetsAreRejected() {
        val validColors = intArrayOf(1, 2, 3)
        val good = listOf(block(1, 0, 0, BoardDirection.RIGHT))
        assertTrue(PuzzleRules.isValidBlockSet(4, 4, good, validColors))
        assertFalse(PuzzleRules.isValidBlockSet(4, 4, listOf(
            block(1, 0, 0, BoardDirection.RIGHT), block(1, 1, 0, BoardDirection.LEFT)
        ), validColors))
        assertFalse(PuzzleRules.isValidBlockSet(4, 4, listOf(
            block(1, 0, 0, BoardDirection.RIGHT), block(2, 0, 0, BoardDirection.LEFT)
        ), validColors))
        assertFalse(PuzzleRules.isValidBlockSet(4, 4, listOf(block(17, 0, 0, BoardDirection.RIGHT)), validColors))
        assertFalse(PuzzleRules.isValidBlockSet(4, 4, listOf(block(1, 0, 0, BoardDirection.RIGHT, 99)), validColors))
    }
}
"""
smoke_test = r"""package com.example.colorpuzzle

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColorPuzzleLevelSmokeTest {
    @Test(timeout = 60000)
    fun levels1To200GenerateNonEmptyBoardsWithinOneMinute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = ColorPuzzleView(context)
        val start = SystemClock.elapsedRealtime()
        for (level in 1..200) {
            view.loadLevel(level)
            val state = JSONObject(view.exportState())
            assertEquals("saved level mismatch", level, state.getInt("level"))
            val blocks = state.getJSONArray("blocks")
            assertTrue("empty board at level $level", blocks.length() > 0)
            val (cols, rows) = PuzzleRules.boardSize(level)
            assertTrue("too many blocks at level $level", blocks.length() <= cols * rows)
        }
        assertTrue("generation took too long", SystemClock.elapsedRealtime() - start < 60000L)
    }
}
"""

for marker in ("private fun evaluateMove", "private fun restoreState", "private fun createGuaranteedFallback"):
    if marker not in original:
        fail("Expected source marker missing: " + marker)

VIEW.write_text(source, encoding="utf-8")
RULES.parent.mkdir(parents=True, exist_ok=True)
RULES.write_text(rules, encoding="utf-8")
TEST.parent.mkdir(parents=True, exist_ok=True)
TEST.write_text(unit_tests, encoding="utf-8")
ANDROID_TEST.parent.mkdir(parents=True, exist_ok=True)
ANDROID_TEST.write_text(smoke_test, encoding="utf-8")

print("Applied changes to:")
for path in (VIEW, RULES, TEST, ANDROID_TEST):
    print(" -", path.relative_to(ROOT))
print("\nRun:")
print("  ./gradlew testDebugUnitTest")
print("  ./gradlew assembleDebug")
print("  ./gradlew connectedDebugAndroidTest  # requires connected emulator/device")
