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
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ExifInterface
import android.media.ExifInterface.TAG_IMAGE_DESCRIPTION
import android.media.MediaCodec
import android.media.MediaRecorder
import android.media.ThumbnailUtils
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Range
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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.lang.IllegalStateException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


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

        /**
         * Record video at 15 Mbps. At 1080p30, this level of detail should be more than high
         * enough.
         */
        private const val RECORDER_VIDEO_BITRATE: Int = 15_000_000


        private const val TARGET_RECORDINGS_PER_WORD = 20
        private const val APP_VERSION = "1.1"
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

    private lateinit var sessionStartTime: Date
    private lateinit var segmentStartTime: Date

    // Camera API variables
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private lateinit var recordingLightView: ImageView
    private lateinit var recordingSurface: Surface
    private lateinit var recorder: MediaRecorder
    private lateinit var camera: CameraDevice
    private var previewSurface: Surface? = null
    private lateinit var session: CameraCaptureSession
    lateinit var cameraView: SurfaceView
    private lateinit var previewRequest: CaptureRequest
    private lateinit var recordRequest: CaptureRequest
    private lateinit var outputFile: File
    private val cameraManager: CameraManager by lazy {
        val context = this.applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

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

    /**
     * Generates a new [Surface] for storing recording data, which will promptly be assigned to
     * the recordingSurface field above.
     */
    private fun createRecordingSurface(): Surface {
        val surface = MediaCodec.createPersistentInputSurface()
        val recorder = MediaRecorder()
        outputFile = File("/storage/emulated/0/Movies/$filename.mp4")
        prepareRecorder(recorder, surface).apply {
            prepare()
            release()
        }

        return surface
    }


    /**
     * Prepares a [MediaRecorder] using the given surface.
     */
    private fun prepareRecorder(rec: MediaRecorder, surface: Surface)
            = rec.apply {
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(outputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        setVideoFrameRate(30)

        // TODO: Device-specific!
        setVideoSize(2592, 1944)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setInputSurface(surface)
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
            outputFile = File("/storage/emulated/0/Movies/$filename.mp4")

            /**
             * Find the front camera, crashing if none is available (should never happen, assuming
             * a reasonably modern device)
             */
            var cameraId = ""

            for (id in cameraManager.cameraIdList) {
                val face = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                if (face == CameraSelector.LENS_FACING_FRONT) {
                    cameraId = id
                    break
                }
            }

            if (cameraId == "") {
                throw IllegalStateException("No front camera available")
            }

            camera = openCamera(cameraManager, cameraId, cameraHandler)

            /**
             * Send video feed to both [previewSurface] and [recordingSurface].
             */
            val targets = listOf(previewSurface!!, recordingSurface)
            session = createCaptureSession(camera, targets, cameraHandler)

            startRecording()

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

    private fun setupCameraCallback() {
        cameraView.holder.addCallback(object: SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG,"Initializing surface!")
                previewSurface = holder.surface

                holder.setFixedSize(2592, 1944)
                initializeCamera()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                Log.d(TAG, "Camera preview surface changed!")
                // PROBABLY NOT THE BEST IDEA!
//                previewSurface = holder.surface
//                initializeCamera()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Camera preview surface destroyed!")
                previewSurface = null
            }
        })
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                this@RecordingActivity.finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when(error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Creates a capture session using the predefined targets, and defines a session state
        // callback which resumes the coroutine once the session is configured
        device.createCaptureSession(targets, object: CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                // cont.resumeWithException(exc)
            }
        }, handler)
    }

    private fun startRecording() {
        previewRequest = session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            previewSurface?.let {
                addTarget(it)
            }
        }.build()

        session.setRepeatingRequest(previewRequest, null, /* cameraHandler */ null)

        this@RecordingActivity.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_LOCKED

        /**
         * Create a request to record at 30fps.
         */
        recordRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_RECORD).apply {
            previewSurface?.let { addTarget(it) }
            addTarget(recordingSurface)

            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(30, 30)
            )
            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }.build()

        session.setRepeatingRequest(recordRequest, null, cameraHandler)

        prepareRecorder(recorder, recordingSurface)

        // Finalizes recorder setup and starts recording
        recorder.apply {
            /**
             * The orientation of 270 degrees (-90 degrees) was determined through
             * experimentation. For now, we do not need to support other
             * orientations than the default portrait orientation.
             *
             * TODO: Verify validity of this orientation on devices other than the
             *       Pixel 5a.
             */
            setOrientationHint(270)
            prepare()
            start()
        }

        sessionStartTime = Calendar.getInstance().time

        recordButton.animate().apply {
            alpha(1.0f)
            duration = 250
        }.start()

        recordButton.visibility = View.VISIBLE

        recordButtonDisabled = false
        recordButton.isClickable = true
        recordButton.isFocusable = true

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

        val filterMatrix = ColorMatrix()
        filterMatrix.setSaturation(1.0f)
        val filter = ColorMatrixColorFilter(filterMatrix)
        recordingLightView.colorFilter = filter
    }

    override fun onStop() {
        try {
            session.close()
            camera.close()
            cameraThread.quitSafely()
            recorder.release()
            recordingSurface.release()

            super.onStop()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error in RecordingActivity.onStop()", exc)
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            cameraThread.quitSafely()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error in RecordingActivity.onDestroy()", exc)
        }
    }

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

        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss.SSS", Locale.US)
        val currentWord = this@RecordingActivity.currentWord
        filename = "${userUID}-${recordingCategory}-${sdf.format(Date())}"
        metadataFilename = filename

        // Set title bar text
        title = "1 of ${wordList.size}"

        recordButton = findViewById(R.id.recordButton)
        recordButton.isHapticFeedbackEnabled = true
        recordButton.visibility = View.INVISIBLE

        wordPager.adapter = WordPagerAdapter(this, wordList, sessionVideoFiles)
        wordPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                runOnUiThread {
                    currentPage = wordPager.currentItem
                    super.onPageSelected(currentPage)

                    Log.d("D", "${wordList.size}")

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

                        recorder.stop()

                        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

                        wordPager.isUserInputEnabled = false

                        countdownText.animate().apply {
                            alpha(0.0f)
                            duration = 250
                        }.start()

                        countdownText.visibility = View.GONE

                        countdownTimer.cancel()

                        recordButton.animate().apply {
                            alpha(0.0f)
                            duration = 250
                        }.start()

                        recordButton.visibility = View.GONE

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

        cameraView = findViewById(R.id.cameraPreview)
//        var surfaceParams = cameraView.layoutParams
//        surfaceParams.width = 1944
//        surfaceParams.height = 2592
//        cameraView.layoutParams = surfaceParams
        cameraView.holder.setSizeFromLayout()

        var aspectRatioConstraint = findViewById<ConstraintLayout>(R.id.aspectRatioConstraint)
        var layoutParams = aspectRatioConstraint.layoutParams
        layoutParams.height = layoutParams.width * 4 / 3
        aspectRatioConstraint.layoutParams = layoutParams

        recordingLightView = findViewById(R.id.videoRecordingLight3)

        val filterMatrix = ColorMatrix()
        filterMatrix.setSaturation(0.0f)
        val filter = ColorMatrixColorFilter(filterMatrix)
        recordingLightView.colorFilter = filter
    }

    override fun onResume() {
        super.onResume()

        cameraThread = generateCameraThread()
        cameraHandler = Handler(cameraThread.looper)

        recorder = MediaRecorder()
        recordingSurface = createRecordingSurface()

        if (wordPager.currentItem >= wordList.size) {
            return
        } else if (!cameraInitialized) {
            setupCameraCallback()
            cameraInitialized = true
        }
    }

    override fun onPause() {
        super.onPause()
        onDestroy()
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

        val emailPassword = resources.getString(R.string.confirmation_gmail_password)

        val emailTask = Thread {
            kotlin.run {
                Log.d("EMAIL", "Running thread to send email...")
                sendEmail("gtsignstudy.confirmation@gmail.com",
                    listOf("gtsignstudy@gmail.com"), subject, body, emailPassword)
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