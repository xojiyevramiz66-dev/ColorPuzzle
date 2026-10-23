package com.example.colorpuzzle

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), ColorPuzzleView.OnGameEventListener {

    private var lives = 3
    private lateinit var tvLives: TextView
    private lateinit var puzzleView: ColorPuzzleView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLives = findViewById(R.id.tvLives)
        puzzleView = findViewById(R.id.puzzleView)
        puzzleView.onGameEventListener = this

        val btnRestart = findViewById<Button>(R.id.btnRestart)
        btnRestart.setOnClickListener {
            lives = 3
            updateLivesUI()
            puzzleView.loadLevel1()
        }
    }

    override fun onWrongMove() {
        lives--
        updateLivesUI()
        Toast.makeText(this, "Блок уперся в препятствие!", Toast.LENGTH_SHORT).show()
        if (lives <= 0) {
            Toast.makeText(this, "Игра окончена!", Toast.LENGTH_LONG).show()
            puzzleView.loadLevel1()
            lives = 3
            updateLivesUI()
        }
    }

    override fun onLevelCompleted() {
        Toast.makeText(this, "Уровень пройден! Выдающийся результат!", Toast.LENGTH_LONG).show()
        puzzleView.loadLevel1()
    }

    private fun updateLivesUI() {
        val hearts = "❤️".repeat(maxOf(0, lives))
        tvLives.text = "Жизни: $hearts"
    }
}
