package com.example.colorpuzzle

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity :
AppCompatActivity(),
ColorPuzzleView.OnGameEventListener {

private var lives = 3

private lateinit var tvLives: TextView
private lateinit var puzzleView: ColorPuzzleView

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    tvLives = findViewById(R.id.tvLives)
    puzzleView = findViewById(R.id.puzzleView)
    puzzleView.onGameEventListener = this

    findViewById<Button>(R.id.btnRestart).setOnClickListener {
        restartGame()
    }

    updateLivesUI()
}

override fun onWrongMove() {
    lives--
    updateLivesUI()

    if (lives <= 0) {
        Toast.makeText(
            this,
            "Игра окончена!",
            Toast.LENGTH_LONG
        ).show()

        restartGame()
    } else {
        Toast.makeText(
            this,
            "Путь заблокирован! Осталось жизней: $lives",
            Toast.LENGTH_SHORT
        ).show()
    }
}

override fun onLevelCompleted() {
    Toast.makeText(
        this,
        "Уровень пройден!",
        Toast.LENGTH_LONG
    ).show()

    restartGame()
}

private fun restartGame() {
    lives = 3
    updateLivesUI()
    puzzleView.loadLevel1()
}

private fun updateLivesUI() {
    tvLives.text = "Жизни: " + "❤️".repeat(lives)
}

}
