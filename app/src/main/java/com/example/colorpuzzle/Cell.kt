package com.example.colorpuzzle

data class Cell(
val id: Int,
val gridX: Int,
val gridY: Int,
val direction: Direction,
val colorResId: Int,
var currentX: Float = 0f,
var currentY: Float = 0f
)

enum class Direction {
UP,
DOWN,
LEFT,
RIGHT
}
