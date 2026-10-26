package com.example.colorpuzzle

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity :
    AppCompatActivity(),
    ColorPuzzleView.OnGameEventListener {

    companion object {
        private const val PREFS_NAME = "color_puzzle_progress"
    }

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private var level = 1
    private var language = "ru"
    private var soundEnabled = true
    private var musicEnabled = false
    private var vibrationEnabled = true

    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var levelCaption: TextView
    private lateinit var tvLevel: TextView
    private lateinit var hintText: TextView
    private lateinit var puzzleView: ColorPuzzleView
    private lateinit var btnRestart: Button
    private lateinit var btnSettings: Button

    private var toneGenerator: ToneGenerator? = null
    private var musicPlayer: MusicPlayer? = null

    private var catalogPreparing = false
    private var gameReady = false

    private fun tr(ru: String, en: String): String =
        if (language == "ru") ru else en

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPreferences()
        setContentView(R.layout.activity_main)
        bindViews()

        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 65)
        } catch (_: Exception) {
            null
        }

        musicPlayer = try {
            MusicPlayer(this).also {
                it.setEnabled(musicEnabled)
            }
        } catch (_: Exception) {
            null
        }

        puzzleView.onGameEventListener = this

        btnRestart.setOnClickListener {
            if (gameReady) {
                restartCurrentLevel()
            } else {
                showPreparingMessage()
            }
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        updateUI()
        prepareLevelCatalog()
    }

    private fun prepareLevelCatalog() {
        if (catalogPreparing || gameReady) return

        catalogPreparing = true

        Thread {
            try {
                PrebuiltLevelCatalog.prepare(applicationContext)

                runOnUiThread {
                    catalogPreparing = false

                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }

                    try {
                        setupGameLevel()
                        gameReady = true
                        updateUI()
                    } catch (_: Exception) {
                        gameReady = false

                        Toast.makeText(
                            this,
                            tr(
                                "Не удалось загрузить уровень. Проверьте файл levels.dat.",
                                "Could not load the level. Check levels.dat."
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    catalogPreparing = false

                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }

                    Toast.makeText(
                        this,
                        tr(
                            "Ошибка подготовки уровней: проверьте assets/levels.dat",
                            "Level preparation failed: check assets/levels.dat"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun showPreparingMessage() {
        Toast.makeText(
            this,
            tr(
                "Подождите, уровни загружаются.",
                "Please wait while levels are loading."
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setupGameLevel() {
        val savedBoard = prefs.getString("board_state", null)

        val restored = try {
            !savedBoard.isNullOrBlank() &&
                puzzleView.restoreState(savedBoard)
        } catch (_: Exception) {
            false
        }

        if (!restored) {
            puzzleView.loadLevel(level)
            saveProgress()
        }
    }

    private fun loadPreferences() {
        language = prefs.getString("language", "ru") ?: "ru"

        if (language != "ru" && language != "en") {
            language = "ru"
        }

        soundEnabled = prefs.getBoolean("sound_enabled", true)
        musicEnabled = prefs.getBoolean("music_enabled", false)
        vibrationEnabled = prefs.getBoolean("vibration_enabled", true)

        level = prefs.getInt("level", 1).coerceAtLeast(1)
    }

    private fun bindViews() {
        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        levelCaption = findViewById(R.id.levelCaption)
        tvLevel = findViewById(R.id.tvLevel)
        hintText = findViewById(R.id.hintText)
        puzzleView = findViewById(R.id.puzzleView)
        btnRestart = findViewById(R.id.btnRestart)
        btnSettings = findViewById(R.id.btnSettings)
    }

    private fun updateUI() {
        titleText.text = "COLOR PUZZLE"

        subtitleText.text = tr(
            "ДУМАЙ. НАХОДИ ПУТЬ. УБИРАЙ БЛОКИ.",
            "THINK. FIND THE WAY. CLEAR THE BLOCKS."
        )

        levelCaption.text = tr(
            "ТЕКУЩИЙ УРОВЕНЬ",
            "CURRENT LEVEL"
        )

        tvLevel.text = tr(
            "Уровень $level",
            "Level $level"
        )

        hintText.text = tr(
            "УБИРАЙ СТРЕЛКИ, КОТОРЫМ НИЧТО НЕ МЕШАЕТ",
            "CLEAR ARROWS THAT HAVE A FREE PATH"
        )

        btnRestart.text = tr(
            "↻   Повторить уровень",
            "↻   Restart level"
        )

        btnSettings.text = tr(
            "⚙   Настройки",
            "⚙   Settings"
        )

        btnRestart.contentDescription = tr(
            "Повторить текущий уровень",
            "Restart current level"
        )

        btnSettings.contentDescription = tr(
            "Открыть настройки",
            "Open settings"
        )

        btnRestart.isEnabled = gameReady
    }

    private fun showSettingsDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(20)
            setPadding(padding, dp(8), padding, dp(4))
        }

        fun addLabel(text: String) {
            content.addView(TextView(this).apply {
                this.text = text
                textSize = 14f
                setTextColor(Color.rgb(55, 65, 85))
                setPadding(0, dp(10), 0, dp(4))
            })
        }

        addLabel(tr("Язык интерфейса", "Interface language"))

        val languageSpinner = Spinner(this)

        languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Русский", "English")
        )

        languageSpinner.setSelection(
            if (language == "ru") 0 else 1
        )

        content.addView(languageSpinner)

        val soundSwitch = Switch(this).apply {
            text = tr("Звуковые эффекты", "Sound effects")
            isChecked = soundEnabled
            setPadding(0, dp(12), 0, dp(8))
        }

        content.addView(soundSwitch)

        val musicSwitch = Switch(this).apply {
            text = tr("Фоновая музыка", "Background music")
            isChecked = musicEnabled
            setPadding(0, dp(8), 0, dp(8))
        }

        content.addView(musicSwitch)

        val vibrationSwitch = Switch(this).apply {
            text = tr("Вибрация", "Vibration")
            isChecked = vibrationEnabled
            setPadding(0, dp(8), 0, dp(8))
        }

        content.addView(vibrationSwitch)

        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Настройки", "Settings"))
            .setView(content)
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .setPositiveButton(tr("Сохранить", "Save"), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    language = if (
                        languageSpinner.selectedItemPosition == 0
                    ) {
                        "ru"
                    } else {
                        "en"
                    }

                    soundEnabled = soundSwitch.isChecked
                    musicEnabled = musicSwitch.isChecked
                    vibrationEnabled = vibrationSwitch.isChecked

                    prefs.edit()
                        .putString("language", language)
                        .putBoolean("sound_enabled", soundEnabled)
                        .putBoolean("music_enabled", musicEnabled)
                        .putBoolean("vibration_enabled", vibrationEnabled)
                        .apply()

                    musicPlayer?.setEnabled(musicEnabled)

                    updateUI()
                    saveProgress()

                    Toast.makeText(
                        this,
                        tr(
                            "Настройки сохранены",
                            "Settings saved"
                        ),
                        Toast.LENGTH_SHORT
                    ).show()

                    dialog.dismiss()
                }
        }

        dialog.show()
    }

    override fun onWrongMove() {
        if (!gameReady) return

        playSound(success = false)
        vibrate(120)

        Toast.makeText(
            this,
            tr(
                "Путь заблокирован! Попробуй другую стрелку.",
                "Path is blocked! Try another arrow."
            ),
            Toast.LENGTH_SHORT
        ).show()

        // Ошибка больше не отнимает жизни и не перезапускает уровень.
        saveProgress()
    }

    override fun onMoveMade() {
        if (!gameReady) return

        playSound(success = true)
        vibrate(25)
        saveProgress()
    }

    override fun onLevelCompleted() {
        if (!gameReady) return

        val completedLevel = level
        level++

        try {
            puzzleView.loadLevel(level)
        } catch (_: Exception) {
            level = completedLevel

            Toast.makeText(
                this,
                tr(
                    "Не удалось загрузить следующий уровень.",
                    "Could not load the next level."
                ),
                Toast.LENGTH_LONG
            ).show()

            return
        }

        updateUI()
        saveProgress()

        playSound(success = true)
        vibrate(80)

        Toast.makeText(
            this,
            tr(
                "Уровень $completedLevel пройден!",
                "Level $completedLevel completed!"
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun restartCurrentLevel() {
        if (!gameReady) {
            showPreparingMessage()
            return
        }

        try {
            puzzleView.loadLevel(level)
            updateUI()
            saveProgress()
        } catch (_: Exception) {
            Toast.makeText(
                this,
                tr(
                    "Не удалось перезапустить уровень.",
                    "Could not restart the level."
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveProgress() {
        if (!::puzzleView.isInitialized || !gameReady) return

        try {
            prefs.edit()
                .putInt("level", level)
                .putString("board_state", puzzleView.exportState())
                .putString("language", language)
                .putBoolean("sound_enabled", soundEnabled)
                .putBoolean("music_enabled", musicEnabled)
                .putBoolean("vibration_enabled", vibrationEnabled)
                .apply()
        } catch (_: Exception) {
            // Ошибка сохранения не должна завершать приложение.
        }
    }

    private fun playSound(success: Boolean) {
        if (!soundEnabled) return

        try {
            toneGenerator?.startTone(
                if (success) {
                    ToneGenerator.TONE_PROP_ACK
                } else {
                    ToneGenerator.TONE_PROP_NACK
                },
                if (success) 90 else 160
            )
        } catch (_: Exception) {
            // Звуковые эффекты необязательны для работы игры.
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(durationMs: Long) {
        if (!vibrationEnabled) return

        try {
            val vibrator =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(
                        VibratorManager::class.java
                    ).defaultVibrator
                } else {
                    getSystemService(
                        Context.VIBRATOR_SERVICE
                    ) as Vibrator
                }

            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        durationMs,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {
            // Вибрация может быть недоступна на устройстве.
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        saveProgress()
        musicPlayer?.setEnabled(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        if (::puzzleView.isInitialized) {
            musicPlayer?.setEnabled(musicEnabled)
        }
    }

    override fun onDestroy() {
        musicPlayer?.release()
        musicPlayer = null

        toneGenerator?.release()
        toneGenerator = null

        super.onDestroy()
    }
    }
