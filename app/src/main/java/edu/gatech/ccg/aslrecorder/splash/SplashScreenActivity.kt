package edu.gatech.ccg.aslrecorder.splash

import android.Manifest
import android.accounts.AccountManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import edu.gatech.ccg.aslrecorder.*
import edu.gatech.ccg.aslrecorder.databinding.ActivitySplashRevisedBinding
import edu.gatech.ccg.aslrecorder.recording.RecordingActivity
import edu.gatech.ccg.aslrecorder.recording.WORDS_PER_SESSION
import edu.gatech.ccg.aslrecorder.splash.SplashScreenActivity.SplashScreenActivity.MAX_SESSIONS
import edu.gatech.ccg.aslrecorder.splash.SplashScreenActivity.SplashScreenActivity.NUM_RECORDINGS
import kotlin.math.min


class SplashScreenActivity: ComponentActivity() {

    object SplashScreenActivity {
        const val NUM_RECORDINGS = 20
        const val MAX_SESSIONS = 20
    }

    var uid = ""
    lateinit var uidBox: TextView

    lateinit var words: ArrayList<String>

    lateinit var statsWordList: TextView
    lateinit var statsWordCounts: TextView

    lateinit var recordingCount: TextView

    lateinit var nextSessionWords: TextView

    lateinit var randomizeButton: Button
    lateinit var startRecordingButton: Button

    lateinit var totalMap: HashMap<String, Int>

    var hasRequestedPermission: Boolean = false

    lateinit var globalPrefs: SharedPreferences
    lateinit var localPrefs: SharedPreferences

    lateinit var wordList: ArrayList<String>
    lateinit var weights: ArrayList<Float>

    lateinit var recordingCounts: ArrayList<Int>

    private lateinit var handleRecordingResult: ActivityResultLauncher<Intent>

    var totalRecordings = 0
    var totalSessions = 0

    val requestUsernamePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
            map ->
        if (map[Manifest.permission.GET_ACCOUNTS] == true && map[Manifest.permission.READ_CONTACTS] == true) {
            // Permission is granted.
            // You can use the API that requires the permission.
        } else {
            // Permission is not granted.
            val text = "Cannot assign UID since permissions not granted"
            val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
            toast.show()
        }
    }

    val requestAllPermissions =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            map ->
            if (map[Manifest.permission.CAMERA] == true && map[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) {
                // Permission is granted.
                // You can use the API that requires the permission.
                var countMap = HashMap<String, Int>()
                for (word in words) {
                    countMap[word] = totalMap.getOrDefault(word, 0)
                }

                val intent = Intent(this, RecordingActivity::class.java).apply {
                    putStringArrayListExtra("WORDS", words)
                    putExtra("UID", uid)
                    putExtra("MAP", countMap)
                    putExtra("TOTAL_RECORDINGS", totalRecordings)
                    putExtra("ALL_WORDS", wordList)
                }

                handleRecordingResult.launch(intent)
            } else {
                // Permission is not granted.
                val text = "Cannot begin recording since permissions not granted"
                val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
                toast.show()
            }
        }

    fun updateCounts() {
        recordingCounts = ArrayList<Int>()
        val statsShowableWords = ArrayList<Pair<Int, String>>()
        totalRecordings = 0

        for (word in wordList) {
            val count = localPrefs.getInt("RECORDING_COUNT_$word", 0)
            recordingCounts.add(count)
            totalRecordings += count

            if (totalMap.isEmpty()) {
                totalMap[word] = count
            }

            if (count > 0) {
                statsShowableWords.add(Pair(count, word))
            }
        }

        statsShowableWords.sortWith(
            compareByDescending<Pair<Int, String>> { it.first }.thenBy { it.second }
        )

        if (statsShowableWords.size > 0 && statsShowableWords[statsShowableWords.lastIndex].first >= NUM_RECORDINGS) {
            val dialog = this.let {
                val builder = AlertDialog.Builder(it)
                builder.setTitle("\uD83C\uDF89 Congratulations, you've finished recording!")
                builder.setMessage("If you'd like to record more phrases, click the button below.")

                val input = EditText(builder.context)
                builder.setView(input)

                builder.setPositiveButton("I'd like to keep recording") {
                        dialog, _ ->

                    dialog.dismiss()
                }

                builder.create()
            }

            dialog.show()
        }

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

        recordingCount = findViewById(R.id.recordingCount)

        if (wlText.isNotEmpty()) {
            statsWordList.text = wlText.substring(1)
            statsWordCounts.text = wcText.substring(1)
        } else {
            statsWordList.text = "No recordings yet!"
            statsWordCounts.text = ""
        }

        recordingCount.text = "$totalRecordings total recordings"

        weights = ArrayList()
        for (count in recordingCounts) {
//            weights.add(max(1.0f, totalRecordings.toFloat()) / max(1.0f, count.toFloat()))
            weights.add(min(1.0e-3f, (NUM_RECORDINGS - count).toFloat() / (NUM_RECORDINGS*wordList.size - totalRecordings).toFloat()))
        }
    }

    fun setupUI() {
        updateCounts()

        getRandomWords(wordList, recordingCounts)
        randomizeButton = findViewById(R.id.rerollButton)
        randomizeButton.setOnClickListener {
            getRandomWords(wordList, recordingCounts)
        }

        startRecordingButton = findViewById(R.id.startButton)
        startRecordingButton.setOnClickListener {
            Log.d("Camera allowed", (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED).toString())
            Log.d("Storage allowed", (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED).toString())
            Log.d("Camera won't show again", shouldShowRequestPermissionRationale(Manifest.permission.CAMERA).toString())
            Log.d("Storage won't show again", shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE).toString())
            // check permissions here
            when {
                (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                 ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) -> {
                    // You can use the API that requires the permission.
                    var countMap = HashMap<String, Int>()
                    for (word in words) {
                        countMap[word] = totalMap.getOrDefault(word, 0)
                    }

                    val intent = Intent(this, RecordingActivity::class.java).apply {
                        putStringArrayListExtra("WORDS", words)
                        putExtra("UID", uid)
                        putExtra("MAP", countMap)
                        putExtra("TOTAL_RECORDINGS", totalRecordings)
                        putExtra("ALL_WORDS", wordList)
                    }

                    handleRecordingResult.launch(intent)
                }
//                !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) &&
//                        !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) -> {
//
//                }
                hasRequestedPermission && ((!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) && (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)) ||
                        !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) && (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) -> {

                        val text = "Please enable camera and storage access in Settings"
                        val toast = Toast.makeText(this, text, Toast.LENGTH_LONG)
                        toast.show()
                }
                hasRequestedPermission && ((shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) ||
                        shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) -> {
                        val dialog = this.let {
                            val builder = AlertDialog.Builder(it)
                            builder.setTitle("Permissions are required to use the app")
                            builder.setMessage("In order to record your data, we will need access to the camera and write functionality.")

                            builder.setPositiveButton("OK") { dialog, _ ->
                                requestAllPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                                dialog.dismiss()
                            }

                            builder.create()
                        }
                        dialog.setCanceledOnTouchOutside(true);
                        dialog.setOnCancelListener {
                            // dialog dismisses
                            // Do your function here
                            requestAllPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                        }
                        dialog.show()

                }
                else -> {
                    if (!hasRequestedPermission) {
                        hasRequestedPermission = true
                        with(globalPrefs.edit()) {
                            putBoolean("hasRequestedPermission", true)
                            apply()
                        }
                    }
                    requestAllPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        handleRecordingResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            run {
                when (result.resultCode) {
                    RESULT_NO_ERROR -> {
                        totalSessions += 1
                    }
                    RESULT_CLOSE_ALL -> {
                        setResult(RESULT_CLOSE_ALL)
                        finish()
                    }
                    RESULT_CAMERA_DIED -> {
                        val text = "Because the camera was disconnected while recording, the recording session was ended."
                        val toast = Toast.makeText(this, text, Toast.LENGTH_LONG)
                        toast.show()
                    }
                    RESULT_RECORDING_DIED -> {
                        val text = "Because the recording stopped unexpectedly, the recording session was ended."
                        val toast = Toast.makeText(this, text, Toast.LENGTH_LONG)
                        toast.show()
                    }
                }
            }
        }

        super.onCreate(savedInstanceState)
        val binding = ActivitySplashRevisedBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        hasRequestedPermission = false

        globalPrefs = getPreferences(MODE_PRIVATE)
        localPrefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        hasRequestedPermission = globalPrefs.getBoolean("hasRequestedPermission", false)

        totalMap = HashMap()

        uidBox = findViewById(R.id.uidBox)

        if (globalPrefs.getString("UID", "")!!.isNotEmpty()) {
            this.uid = globalPrefs.getString("UID", "")!!
        } else {
            requestUsernamePermissions.launch(arrayOf(Manifest.permission.GET_ACCOUNTS, Manifest.permission.READ_CONTACTS))
            val manager = AccountManager.get(this)
            val accountList = manager.getAccountsByType("com.google")
            Log.d("Account List", accountList.toString())
            if (accountList.isNotEmpty()) {
                this.uid = accountList[0].name.split("@")[0]
                with(globalPrefs.edit()) {
                    putString("UID", uid)
                    apply()
                }
            } else {
                this.uid = "Permissions not accepted"
                Log.d("Account not found", "womp womp")
            }
        }

        uidBox.text = this.uid

        wordList = ArrayList()
        for (category in WordDefinitions.values()) {
            for (word in resources.getStringArray(category.resourceId)) {
                wordList.add(word)
            }
        }

        setupUI()
    }

    override fun onResume() {
        super.onResume()
        Log.d("totalSessions", "Total number of sessions: $totalSessions")
        if (totalSessions >= MAX_SESSIONS) {
            setContentView(R.layout.end_of_sitting_message)
            return
        }
        setupUI()
    }

    fun getRandomWords(wordList: ArrayList<String>, recordingCounts: ArrayList<Int>) {
        words = lowestCountRandomChoice(wordList, recordingCounts, WORDS_PER_SESSION)

        nextSessionWords = findViewById(R.id.recordingListColumn1)
        nextSessionWords.text = words.subList(0, 10).joinToString("\n")

        nextSessionWords = findViewById(R.id.recordingListColumn2)
        nextSessionWords.text = words.subList(10, 20).joinToString("\n")
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

}