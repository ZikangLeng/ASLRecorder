/**
 * RecordingActivity.kt
 * This file is part of ASLRecorder, licensed under the MIT license.
 *
 * Copyright (c) 2021 Sahir Shahryar <contact@sahirshahryar.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package edu.gatech.ccg.aslrecorder.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ExifInterface
import android.media.ExifInterface.TAG_IMAGE_DESCRIPTION
import android.media.ThumbnailUtils
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.Camera2Config
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import edu.gatech.ccg.aslrecorder.*
import edu.gatech.ccg.aslrecorder.R
import edu.gatech.ccg.aslrecorder.databinding.ActivityRecordBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList
import kotlin.concurrent.withLock


const val WORDS_PER_SESSION = 20

data class RecordingEntryVideo(val file: File, val videoStart: Date, val signStart: Date, val signEnd: Date, var isValid: Boolean) {
    override fun toString(): String {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss.SSS", Locale.US)
        val isValidPython = if (isValid) "True" else "False"
        return "(file=${file.absolutePath}, videoStart=${sdf.format(videoStart)}, signStart=${sdf.format(signStart)}, signEnd=${sdf.format(signEnd)}, isValid=${isValidPython})"}
}

/**
 * This class handles the recording of ASL into videos.
 *
 * @author  Matthew So <matthew.so@gatech.edu>, Sahir Shahryar <contact@sahirshahryar.com>
 * @since   October 4, 2021
 * @version 1.1.0
 */
class RecordingActivity : AppCompatActivity(), CameraXConfig.Provider {

    companion object {
        private val TAG = RecordingActivity::class.java.simpleName
        private const val TARGET_RECORDINGS_PER_WORD = 20
        private const val APP_VERSION = "1.0"
    }

    private lateinit var context: Context

    // UI elements
    lateinit var recordButton: FloatingActionButton
    lateinit var countdownTimer: CountDownTimer
    lateinit var countdownText: TextView
    lateinit var wordPager: ViewPager2
    private lateinit var binding: ActivityRecordBinding

    // UI state variables
    private var recordButtonDisabled = false
    private var cameraInitialized = false
    private var isRecording = false
    private var endSessionOnRecordButtonRelease = false
    private var currentPage: Int = 0

    // Word data
    private lateinit var wordList: ArrayList<String>
    private lateinit var completeWordList: ArrayList<String>
    private var currentWord: String = "test"

    // Recording and session data
    private lateinit var filename: String
    private lateinit var metadataFilename: String
    private lateinit var recordingCategory: String

    private var userUID: String = ""
    private var sessionVideoFiles = HashMap<String, ArrayList<RecordingEntryVideo>>()
    private lateinit var countMap: HashMap<String, Int>

    private lateinit var currRecording: Recording
    private lateinit var sessionStartTime: Date
    private lateinit var segmentStartTime: Date

    // Camera API variables
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private lateinit var recordingLightView: ImageView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var videoCapture: VideoCapture<Recorder>

    // Permissions
    private var permissions: Boolean = true

    val permission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
            //handle individual results if desired
            if (map[Manifest.permission.CAMERA] == true && map[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) {
                // Access granted
            }
            map.entries.forEach { entry ->
                when (entry.key) {
                    Manifest.permission.CAMERA ->
                        permissions = permissions && entry.value
                    Manifest.permission.WRITE_EXTERNAL_STORAGE ->
                        permissions = permissions && entry.value
                }
            }
        }

    override fun getCameraXConfig(): CameraXConfig = Camera2Config.defaultConfig()

    @SuppressLint("UnsafeOptInUsageError", "RestrictedApi")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            binding.viewFinder.also {
                it.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                it.scaleType = PreviewView.ScaleType.FIT_CENTER
            }

            // Select front camera as a default
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            // video recording

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.HIGHEST, Quality.UHD, Quality.FHD, Quality.HD, Quality.SD, Quality.LOWEST))

            val recorder = Recorder.Builder()
                .setExecutor(cameraExecutor).setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture)

                val cameraCharacteristics = Camera2CameraInfo.extractCameraCharacteristics(camera.cameraInfo)

                var cameraRequestOptions = CaptureRequestOptions.Builder().setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_ZOOM_RATIO,
//                    1.0F
                    cameraCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)!!.lower
                ).setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    ).build()

                val cameraId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
                val cameraManager = ContextCompat.getSystemService(this, CameraManager::class.java) as CameraManager
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val configs : StreamConfigurationMap? = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

                val imageAnalysisSizes = configs?.getOutputSizes(ImageFormat.YUV_420_888)
                imageAnalysisSizes?.forEach {
                    Log.d("Recording","Image capturing YUV_420_888 available output size: $it")
                }

                val previewSizes = configs?.getOutputSizes(SurfaceTexture::class.java)
                previewSizes?.forEach {
                    Log.d("Preview", "Preview available output size: $it")
                }
                Log.d("Final", "Chosen Preview resolution: ${preview?.attachedSurfaceResolution}")

                Camera2CameraControl.from(camera.cameraControl).addCaptureRequestOptions(cameraRequestOptions)

            } catch(exc: Exception) {
                Log.e(TAG, "Video feed Use case binding failed", exc)
            }

            // Create MediaStoreOutputOptions for our recorder

            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss.SSS", Locale.US)
            val currentWord = this@RecordingActivity.currentWord
            filename = "${userUID}-${recordingCategory}-${sdf.format(Date())}"
            metadataFilename = filename

            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$filename.mp4")
            }
            val mediaStoreOutput = MediaStoreOutputOptions.Builder(super.getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build()

            // 2. Configure Recorder and Start recording to the mediaStoreOutput.

            currRecording = videoCapture.output
                .prepareRecording(context, mediaStoreOutput)
                .start(ContextCompat.getMainExecutor(super.getBaseContext())) { videoRecordEvent ->
                    run {
                        if (videoRecordEvent is VideoRecordEvent.Start) {
                            sessionStartTime = Calendar.getInstance().time
                            Log.d("currRecording", "Recording Started")
                            recordButton.animate().apply {
                                alpha(1.0f)
                                duration = 250
                            }.start()

                            recordButton.visibility = View.VISIBLE

                            recordButtonDisabled = false
                            recordButton.isClickable = true
                            recordButton.isFocusable = true

                            val filterMatrix = ColorMatrix()
                            filterMatrix.setSaturation(1.0f)
                            val filter = ColorMatrixColorFilter(filterMatrix)
                            recordingLightView.colorFilter = filter

                        } else if (videoRecordEvent is VideoRecordEvent.Pause) {
                            Log.d("currRecording", "Recording Paused")
                        } else if (videoRecordEvent is VideoRecordEvent.Resume) {
                            Log.d("currRecording", "Recording Resumed")
                        } else if (videoRecordEvent is VideoRecordEvent.Finalize) {
                            val finalizeEvent = videoRecordEvent as VideoRecordEvent.Finalize
                            // Handles a finalize event for the active recording, checking Finalize.getError()
                            val error = finalizeEvent.error
                            Log.d("currRecording", "Recording finalized. Cause: {$error}")

                            if (error != VideoRecordEvent.Finalize.ERROR_NONE && error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                                Log.d("currRecording", "Error in saving")
                            } else {
                                Log.d("currRecording", "Recording Finalized")

                                val filterMatrix = ColorMatrix()
                                filterMatrix.setSaturation(0.0f)
                                val filter = ColorMatrixColorFilter(filterMatrix)
                                recordingLightView.colorFilter = filter
                            }

                            if (error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                                wordPager.currentItem = wordList.size + 1
                                Log.d("currRecording", "Recording finalized. Cause: {$}")
                            }
                        } else {

                        }

                        // All events, including VideoRecordEvent.Status, contain RecordingStats.
                        // This can be used to update the UI or track the recording duration.
                        // val recordingStats = videoRecordEvent.recordingStats
                    }
                }

            countdownText = findViewById(R.id.timerLabel)

            countdownTimer = object : CountDownTimer(900_000, 1000) {
                override fun onTick(p0: Long) {
                    val rawSeconds = (p0 / 1000).toInt() + 1
                    val minutes = padZeroes(rawSeconds / 60, 2)
                    val seconds = padZeroes(rawSeconds % 60, 2)
                    countdownText.text = "$minutes:$seconds"
                }

                override fun onFinish() {
                    if (isRecording) {
                        endSessionOnRecordButtonRelease = true
                    } else {
                        wordPager.currentItem = wordList.size + 1
                    }
                }
            }

            countdownTimer.start()

        }, ContextCompat.getMainExecutor(this))
    }


    /**
     * This code initializes the camera-related portion of the code, adding listeners to enable
     * video recording as long as we hold down the Record button.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        /**
         * First, check camera permissions. If the user has not granted permission to use the
         * camera, give a prompt asking them to grant that permission in the Settings app, then
         * relaunch the app.
         */
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
               PackageManager.PERMISSION_GRANTED) {

            val errorRoot = findViewById<ConstraintLayout>(R.id.main_root)
            val errorMessage = layoutInflater.inflate(R.layout.permission_error, errorRoot,
                false)
            errorRoot.addView(errorMessage)

            // Dim Record button
            recordButton.backgroundTintList = ColorStateList.valueOf(0xFFFA9389.toInt())
        }


        /**
         * User has given permission to use the camera
         */
        else {
            startCamera()

            val buttonLock = ReentrantLock()

            /**
             * Set a listener for when the user presses the record button.
             */
            recordButton.setOnTouchListener { _, event ->
                /**
                 * Do nothing if the record button is disabled.
                 */
                if (recordButtonDisabled) {
                    return@setOnTouchListener false
                }

                when (event.action) {
                    /**
                     * User holds down the record button:
                     */
                    MotionEvent.ACTION_DOWN -> lifecycleScope.launch(Dispatchers.IO) {

                        Log.d(TAG, "Record button down")

                        buttonLock.withLock {

                            Log.d(TAG, "Recording starting")

                            segmentStartTime = Calendar.getInstance().time
                            isRecording = true

                        }
                        wordPager.isUserInputEnabled = false
                        recordButton.backgroundTintList = ColorStateList.valueOf(0xFF7C0000.toInt())
                        recordButton.setColorFilter(0x80ffffff.toInt(), PorterDuff.Mode.MULTIPLY)
                    }

                    /**
                     * User releases the record button:
                     */
                    MotionEvent.ACTION_UP -> lifecycleScope.launch(Dispatchers.IO) {
                        Log.d(TAG, "Record button up")

                        buttonLock.withLock {

                            /**
                             * Add this recording to the list of recordings for the currently-selected
                             * word.
                             */
                            if (!sessionVideoFiles.containsKey(currentWord)) {
                                sessionVideoFiles[currentWord] = ArrayList()
                            }

                            val recordingList = sessionVideoFiles[currentWord]!!
                            Log.d("VideoPlayback", MediaStore.Video.Media.getContentUri("external").toString())
                            val outputFile = File("/storage/emulated/0/Movies/$filename.mp4")
                            if (recordingList.size == 0) {
                                runOnUiThread {
                                    if (currentPage < wordList.size) {
                                        countMap[currentWord] =
                                            countMap.getOrDefault(currentWord, 0) + 1
                                    }
                                }
                            } else {
                                recordingList[recordingList.size - 1].isValid = false
                            }

                            recordingList.add(RecordingEntryVideo(
                                outputFile, sessionStartTime, segmentStartTime,
                                Calendar.getInstance().time, true
                            ))

                            val wordPagerAdapter = wordPager.adapter as WordPagerAdapter
                            wordPagerAdapter.updateRecordingList()

                            recordButton.performHapticFeedback(HapticFeedbackConstants.REJECT)

                            wordPager.currentItem += 1

                        }
                        wordPager.isUserInputEnabled = true
                        recordButton.backgroundTintList = ColorStateList.valueOf(0xFFF80000.toInt())
                        recordButton.clearColorFilter()

                        isRecording = false
                        if (endSessionOnRecordButtonRelease) {
                            runOnUiThread {
                                wordPager.currentItem = wordList.size + 1
                            }
                        }
                    }
                }
                true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
    }

    /**
     * END BORROWED CODE FROM AOSP.
     */

    fun generateCameraThread() = HandlerThread("CameraThread").apply { start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        context = this

        // Set up view pager
        wordPager = findViewById(R.id.wordPager)

        val bundle = intent.extras

        val fullWordList = if (bundle?.containsKey("WORDS") == true) {
            ArrayList(bundle.getStringArrayList("WORDS"))
        } else {
            // Something has gone wrong if this code ever executes
            val wordArray = resources.getStringArray(R.array.animals)
            ArrayList(listOf(*wordArray))
        }

        recordingCategory = if (bundle?.containsKey("CATEGORY") == true) {
            bundle.getString("CATEGORY").toString()
        } else {
            "randombatch"
        }

        val randomSeed = if (bundle?.containsKey("SEED") == true) {
            bundle.getLong("SEED")
        } else {
            null
        }

        this.userUID = if (bundle?.containsKey("UID") == true) {
            bundle.getString("UID").toString()
        } else {
            "999"
        }

        countMap = intent.getSerializableExtra("MAP") as HashMap<String, Int>

        completeWordList = intent.getStringArrayListExtra("ALL_WORDS") as ArrayList<String>

        Log.d("RECORD",
            "Choosing $WORDS_PER_SESSION words from a total of ${fullWordList.size}")
        wordList = randomChoice(fullWordList, WORDS_PER_SESSION, randomSeed)
        currentWord = wordList[0]

        // Set title bar text
        title = "1 of ${wordList.size}"

        recordButton = findViewById(R.id.recordButton)
        recordButton.isHapticFeedbackEnabled = true
        recordButton.visibility = View.INVISIBLE

        wordPager.adapter = WordPagerAdapter(this, wordList, sessionVideoFiles)
        wordPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = wordPager.currentItem
                super.onPageSelected(currentPage)

                Log.d("D", "${wordList.size}")

                runOnUiThread {
                    if (currentPage < wordList.size) {
                        // Animate the record button back in, if necessary

                        this@RecordingActivity.currentWord = wordList[currentPage]

                        countMap[currentWord] = countMap.getOrDefault(currentWord, 0)
                        title = "${currentPage + 1} of ${wordList.size}"

                        if (recordButtonDisabled) {
                            recordButton.isClickable = true
                            recordButton.isFocusable = true
                            recordButtonDisabled = false

                            recordButton.animate().apply {
                                alpha(1.0f)
                                duration = 250
                            }.start()
                        }
                    } else if (currentPage == wordList.size) {
                        title = "Save or continue?"

                        recordButton.isClickable = false
                        recordButton.isFocusable = false
                        recordButtonDisabled = true

                        recordButton.animate().apply {
                            alpha(0.0f)
                            duration = 250
                        }.start()

                    } else {
                        // Hide record button and move the slider to the front (so users can't
                        // accidentally press record)
                        Log.d(
                            TAG, "Recording stopped. Check " +
                                    this@RecordingActivity.getExternalFilesDir(null)?.absolutePath
                        )

                        window.setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

                        currRecording.stop()

                        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

                        wordPager.isUserInputEnabled = false

                        countdownText.animate().apply {
                            alpha(0.0f)
                            duration = 250
                        }.start()

                        countdownTimer.cancel()

                        recordButton.animate().apply {
                            alpha(0.0f)
                            duration = 250
                        }.start()

                        recordButton.isClickable = false
                        recordButton.isFocusable = false
                        recordButtonDisabled = true

                        title = "Session summary"

                        val filterMatrix = ColorMatrix()
                        filterMatrix.setSaturation(0.0f)
                        val filter = ColorMatrixColorFilter(filterMatrix)
                        recordingLightView.colorFilter = filter
                    }
                }
            }
        })

        recordingLightView = findViewById(R.id.videoRecordingLight3)

        val filterMatrix = ColorMatrix()
        filterMatrix.setSaturation(0.0f)
        val filter = ColorMatrixColorFilter(filterMatrix)
        recordingLightView.colorFilter = filter

        initializeCamera()
    }

    override fun onResume() {
        super.onResume()

        cameraThread = generateCameraThread()
        cameraHandler = Handler(cameraThread.looper)

        if (!cameraInitialized) {
            initializeCamera()
            cameraInitialized = true
        }
    }

    fun goToWord(index: Int) {
        wordPager.currentItem = index
    }

    fun deleteMostRecentRecording(word: String) {
        // Since only the last recording is shown, the last recording should be deleted
        if (sessionVideoFiles.containsKey(word)) {
            sessionVideoFiles[word]?.get(sessionVideoFiles[word]!!.size - 1)?.isValid = false
        }
    }

    fun concludeRecordingSession() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        with (prefs.edit()) {
            for (entry in sessionVideoFiles) {
                val key = "RECORDING_COUNT_${entry.key}"
                val recordingCount = prefs.getInt(key, 0)
                if (entry.value.isNotEmpty() and entry.value.last().isValid) {
                    putInt(key, recordingCount + 1)
                }
            }
            commit()
        }
        createTimestampFileAllinOne(sessionVideoFiles)
        sendConfirmationEmail()
        finish()
    }

    fun sendConfirmationEmail() {
        val userId = this.userUID

        var wordList = ArrayList<Pair<String, Int>>()
        var recordings = ArrayList<Pair<String, RecordingEntryVideo>>()
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        for (entry in sessionVideoFiles) {
            if (entry.value.isNotEmpty()) {
                val prefsKey = "RECORDING_COUNT_${entry.key}"
                val recordingCount = prefs.getInt(prefsKey, 0)

                wordList.add(Pair(entry.key, recordingCount))
                recordings.addAll(entry.value.map { Pair(entry.key, it) })
            }
        }

        recordings.sortBy { it.second.signStart }

        val outputFile = File("/storage/emulated/0/Movies/$filename.mp4")

        val fileDigest = outputFile.md5()

        val subject = "Recording confirmation for $userId"
        val wordListWithCounts = wordList.joinToString(", ", "", "", -1,
        "") {
            "${it.first} (${it.second} / $TARGET_RECORDINGS_PER_WORD)"
        }
        var body = "The user '$userId' recorded the following ${wordList.size} word(s) to the " +
                "file $filename.mp4 (MD5 = $fileDigest): $wordListWithCounts\n\n"

        var totalWordCount = 0
        for (word in completeWordList) {
            totalWordCount += prefs.getInt("RECORDING_COUNT_$word", 0)
        }

        body += "Overall progress: $totalWordCount / " +
                "${TARGET_RECORDINGS_PER_WORD * completeWordList.size}\n\n"

        fun formatTime(millis: Long): String {
            val minutes = millis / 60_000
            val seconds = ((millis % 60_000) / 1000).toInt()
            val millisRemaining = (millis % 1_000).toInt()

            return "$minutes:${padZeroes(seconds, 2)}.${padZeroes(millisRemaining, 3)}"
        }

        for (entry in recordings) {
            body += "- '${entry.first}'"
            if (!entry.second.isValid) {
                body += " (discarded)"
            }

            val clipData = entry.second

            val startMillis = clipData.signStart.time - clipData.videoStart.time
            val endMillis = clipData.signEnd.time - clipData.videoStart.time

            body += ": ${formatTime(startMillis)} - ${formatTime(endMillis)}\n"
        }

        body += "\n\nApp version $APP_VERSION\n\n"

        body += "EXIF data:\n ${convertRecordingListToString(sessionVideoFiles)}"

        val emailTask = Thread {
            kotlin.run {
                Log.d("EMAIL", "Running thread to send email...")
                sendEmail("gtsignstudy.confirmation@gmail.com",
                    listOf("gtsignstudy@gmail.com"), subject, body)
            }
        }

        emailTask.start()
    }

    fun createTimestampFileAllinOne(sampleVideos: HashMap<String, ArrayList<RecordingEntryVideo>>) {
        // resort sampleVideos around files
        if (sampleVideos.size > 0) {
            val thumbnailValues = ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    "$metadataFilename-timestamps.jpg"
                );       //file name
                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    "image/jpeg"
                );        //file extension, will automatically add to file
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES
                );     //end "/" is not mandatory
            }
            var uri = contentResolver.insert(
                MediaStore.Images.Media.getContentUri("external"),
                thumbnailValues
            )
            var outputThumbnail = uri?.let { contentResolver.openOutputStream(it) }
            sampleVideos[sampleVideos.keys.random()]?.first()?.file?.let {
                ThumbnailUtils.createVideoThumbnail(
                    it,
                    Size(640, 480),
                    null
                )?.apply {
                    compress(Bitmap.CompressFormat.JPEG, 90, outputThumbnail)
                    recycle()
                }
            }
            outputThumbnail?.flush()
            outputThumbnail?.close()

            var imageFd = uri?.let { contentResolver.openFileDescriptor(it, "rw") }

            var exif = imageFd?.let { ExifInterface(it.fileDescriptor) }
            exif?.setAttribute(
                TAG_IMAGE_DESCRIPTION,
                convertRecordingListToString(sessionVideoFiles)
            )
            exif?.saveAttributes()
        }

        val text = "Video successfully saved"
        val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        toast.show()
    }
}