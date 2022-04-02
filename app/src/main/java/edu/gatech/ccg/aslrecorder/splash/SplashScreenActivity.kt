package edu.gatech.ccg.aslrecorder.splash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import edu.gatech.ccg.aslrecorder.R
import edu.gatech.ccg.aslrecorder.databinding.ActivitySplashRevisedBinding
import edu.gatech.ccg.aslrecorder.recording.RecordingActivity
import edu.gatech.ccg.aslrecorder.recording.WORDS_PER_SESSION
import edu.gatech.ccg.aslrecorder.weightedRandomChoice
import kotlin.math.max
import kotlin.math.min

class SplashScreenActivity: AppCompatActivity() {

    var uid = ""
    lateinit var uidBox: TextView

    lateinit var words: ArrayList<String>

    lateinit var statsWordList: TextView
    lateinit var statsWordCounts: TextView

    lateinit var nextSessionWords: TextView

    lateinit var randomizeButton: Button
    lateinit var startRecordingButton: Button

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun setUid() {
        val prefs = getPreferences(MODE_PRIVATE)

        if (prefs.getString("UID", "")!!.isNotEmpty()) {
            this.uid = prefs.getString("UID", "")!!
            uidBox.text = this.uid
        } else {
            val dialog = this.let {
                val builder = AlertDialog.Builder(it)
                builder.setTitle("Set UID")
                builder.setMessage("Please enter the UID that you were assigned.")

                val input = EditText(builder.context)
                builder.setView(input)

                builder.setPositiveButton("OK") {
                        dialog, _ ->
                    this.uid = input.text.toString()
                    uidBox.text = this.uid
                    with (prefs.edit()) {
                        putString("UID", uid)
                        apply()
                    }

                    dialog.dismiss()
                }

                builder.create()
            }

            dialog.setCancelable(false)
            dialog.show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySplashRevisedBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        uidBox = findViewById(R.id.uidBox)
        setUid()

        val prefs = getPreferences(MODE_PRIVATE)

        val wordList = ArrayList<String>()
        for (category in WordDefinitions.values()) {
            for (word in resources.getStringArray(category.resourceId)) {
                wordList.add(word)
            }
        }

        val recordingCounts = ArrayList<Int>()
        val statsShowableWords = ArrayList<Pair<Int, String>>()
        var totalRecordings = 0
        for (word in wordList) {
            val count = prefs.getInt("RECORDING_COUNT_$word", 0)
            recordingCounts.add(count)
            totalRecordings += count

            if (count > 0) {
                statsShowableWords.add(Pair(count, word))
            }
        }

        statsShowableWords.sortWith(
            compareByDescending<Pair<Int, String>> { it.first }.thenBy { it.second }
        )

        val statsWordCount = min(statsShowableWords.size, 5)
        var wcText = ""
        var wlText = ""
        for (i in 0 until statsWordCount) {
            val pair = statsShowableWords[i]
            wlText += "\n" + pair.second
            wcText += "\n" + pair.first + (if (pair.first == 1) " time" else " times")
        }

        statsWordList = findViewById(R.id.statsWordList)
        statsWordCounts = findViewById(R.id.statsWordCounts)

        if (wlText.isNotEmpty()) {
            statsWordList.text = wlText.substring(1)
            statsWordCounts.text = wcText.substring(1)
        } else {
            statsWordList.text = "No recordings yet!"
            statsWordCounts.text = ""
        }

        val weights = ArrayList<Float>()
        for (count in recordingCounts) {
            weights.add(max(1.0f, totalRecordings.toFloat()) / max(1.0f, count.toFloat()))
        }

        getRandomWords(wordList, weights)
        randomizeButton = findViewById(R.id.rerollButton)
        randomizeButton.setOnClickListener {
            getRandomWords(wordList, weights)
        }

        startRecordingButton = findViewById(R.id.startButton)
        startRecordingButton.setOnClickListener {
            val intent = Intent(this, RecordingActivity::class.java).apply {
                putStringArrayListExtra("WORDS", words)
                putExtra("UID", uid)
            }

            startActivity(intent)
        }
    }

    fun getRandomWords(wordList: ArrayList<String>, weights: ArrayList<Float>) {
        words = weightedRandomChoice(wordList, weights, WORDS_PER_SESSION)

        nextSessionWords = findViewById(R.id.recordingList)
        nextSessionWords.text = words.joinToString("\n")
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

}