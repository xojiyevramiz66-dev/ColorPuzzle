
package com.example.colorpuzzle

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity :
    AppCompatActivity(),
    ColorPuzzleView.OnGameEventListener {

    private var level = 1
    private var lives = 3

    private lateinit var tvLevel: TextView
    private lateinit var tvLives: TextView
    private lateinit var puzzleView: ColorPuzzleView

    private val prefs by lazy {
        getSharedPreferences("color_puzzle_progress", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        level = prefs.getInt("level", 1).coerceAtLeast(1)

        tvLevel = findViewById(R.id.tvLevel)
        tvLives = findViewById(R.id.tvLives)
        puzzleView = findViewById(R.id.puzzleView)

        puzzleView.onGameEventListener = this
        puzzleView.loadLevel(level)

        findViewById<Button>(R.id.btnRestart).setOnClickListener {
            restartCurrentLevel()
        }

        updateUI()
    }

    override fun onWrongMove() {
        lives--
        updateUI()

        if (lives <= 0) {
            Toast.makeText(
                this,
                "Жизни закончились. Повтори уровень!",
                Toast.LENGTH_SHORT
            ).show()

            restartCurrentLevel()
        } else {
            Toast.makeText(
                this,
                "Путь заблокирован! Жизни: $lives",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onLevelCompleted() {
        level++
        lives = 3

        prefs.edit().putInt("level", level).apply()

        puzzleView.loadLevel(level)
        updateUI()

        Toast.makeText(
            this,
            "Уровень пройден! Уровень $level",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun restartCurrentLevel() {
        lives = 3
        puzzleView.loadLevel(level)
        updateUI()
    }

    private fun updateUI() {
        tvLevel.text = "Уровень $level"
        tvLives.text = "Жизни: " + "❤️".repeat(lives)
    }
}
