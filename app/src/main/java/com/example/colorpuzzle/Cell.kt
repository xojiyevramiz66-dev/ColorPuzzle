package com.example.colorpuzzle

data class Cell(
    val id: Int,
    var gridX: Int,         // Координата X на сетке
    var gridY: Int,         // Координата Y на сетке
    val direction: Direction, // Направление стрелки (UP, DOWN, LEFT, RIGHT)
    val colorResId: Int,    // Цвет блока (например, Color.RED)
    var isMoving: Boolean = false,
    var currentX: Float = 0f, // Текущие пиксельные координаты для анимации
    var currentY: Float = 0f
)

enum class Direction {
    UP, DOWN, LEFT, RIGHT
}
