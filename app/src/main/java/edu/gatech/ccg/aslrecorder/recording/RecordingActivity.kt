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
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.*
import android.media.ExifInterface
import android.media.ExifInterface.TAG_IMAGE_DESCRIPTION
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.Layout
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import edu.gatech.ccg.aslrecorder.R
import edu.gatech.ccg.aslrecorder.randomChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedInputStream

import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.concurrent.withLock

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import com.google.common.base.Joiner
import edu.gatech.ccg.aslrecorder.convertRecordingListToString
import edu.gatech.ccg.aslrecorder.databinding.ActivityRecordBinding
import edu.gatech.ccg.aslrecorder.padZeroes
//import kotlinx.android.synthetic.main.activity_record.*
import java.util.concurrent.Executors

import java.util.Calendar

const val WORDS_PER_SESSION = 5

data class RecordingEntryVideo(val file: File, val videoStart: Date, val signStart: Date, val signEnd: Date) {
    override fun toString(): String {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss.SSS", Locale.US)
        return "(file=${file.absolutePath}, videoStart=${sdf.format(videoStart)}, signStart=${sdf.format(signStart)}, signEnd=${sdf.format(signEnd)})"}
}

/**
 * This class handles the recording of ASL into videos.
 *
 * @author  Matthew So <matthew.so@gatech.edu>, Sahir Shahryar <contact@sahirshahryar.com>
 * @since   October 4, 2021
 * @version 1.1.0
 */
class RecordingActivity : AppCompatActivity() {

    private lateinit var context: Context

    /**
     * The button that must be held to record video.
     */
    lateinit var recordButton: FloatingActionButton

    /**
     * A timer that will be shown in the corner
     */
    lateinit var countdownTimer: CountDownTimer

    lateinit var countdownText: TextView

    /**
     * Whether or not the recording button is disabled. When this is true,
     * all interactions with the record button will be passed to the layer
     * underneath.
     */
    var recordButtonDisabled = false


    /**
     * List of words that we can swipe through
     */
    lateinit var wordList: ArrayList<String>


    /**
     * The pager used to swipe back and forth between words.
     */
    lateinit var wordPager: ViewPager2


    /**
     * The current word that the user has been asked to sign.
     */
    var currentWord: String = "test"


    /**
     * Map of video recordings the user has taken
     * key (String) the word being recorded
     * value (ArrayList<Triple<File, Date, Date>>) list of recording files for each word (file, sign start time, video start time)
     */
    var sessionVideoFiles = HashMap<String, ArrayList<RecordingEntryVideo>>()

    /**
     * SUBSTANTIAL PORTIONS OF THE BELOW CODE BELOW ARE BORROWED
     * from the Android Open Source Project (AOSP), WHICH IS LICENSED UNDER THE
     * Apache 2.0 LICENSE (https://www.apache.org/licenses/LICENSE-2.0). (c) 2020 AOSP.
     *
     * SEE https://github.com/android/camera-samples/blob/master/Camera2Video/app/
     *     src/main/java/com/example/android/camera2/video/fragments/CameraFragment.kt
     */

    /**
     * The camera being used for recording.
     */
    private lateinit var camera: CameraDevice


    /**
     * The thread responsible for handling the camera.
     */
    private lateinit var cameraThread: HandlerThread


    /**
     * Handler object for the camera.
     */
    private lateinit var cameraHandler: Handler


//    /**
//     * The current recording session, if we are currently capturing video.
//     */
//    private lateinit var session: CameraCaptureSession


    /**
     * Deprecated: CameraX now used. The Android service responsible for providing information about the phone's camera setup.
     */
//    private val cameraManager: CameraManager by lazy {
//        val context = this.applicationContext
//        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
//    }


    /**
     * A [Surface] (canvas) which is used to show the user a real-time preview of their video feed.
     */
    private var previewSurface: Surface? = null


    /**
     * Deprecated: CameraX now used. The [CaptureRequest] needed to send video data to [previewSurface].
     */
//    private lateinit var previewRequest: CaptureRequest


    /**
     * Deprecated: CameraX now used. The [CaptureRequest] needed to send video data to a recording file.
     */
//    private lateinit var recordRequest: CaptureRequest


    /**
     * Deprecated: CameraX now used.
     * The [File] where the next recording will be stored. The filename contains the word being
     * signed, as well as the date and time of the recording.
     */
//    private lateinit var outputFile: File


    /**
     * Deprecated: CameraX now used. The media recording service.
     */
//    private lateinit var recorder: MediaRecorder


    /**
     * The time at which the current recording started. We use this to ensure that recordings
     * are at least one second long.
     */
    private var recordingStartMillis: Long = 0L


    /**
     * Camera executor
     */
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    /*
     * CameraX video capture object
     */
    private lateinit var videoCapture: VideoCapture<Recorder>

    /*
     * Current recording being recorded
     */
    private lateinit var currRecording: Recording

    private lateinit var binding: ActivityRecordBinding

    /*
     * Filename of file currently being recorded.
     */
    private lateinit var filename: String

    /*
     * User's UID. Assigned by CCG to each user during recording app deployment.
     */
    private var UID: String = ""

    private lateinit var videoStartTime: Date

    private lateinit var currStartTime: Date

    private lateinit var currEndTime: Date

    private lateinit var category: String

    private lateinit var metadataFilename: String

    private lateinit var countMap: HashMap<String, Int>

    private lateinit var recordingLightView: ImageView

    // Mediapipe
    private lateinit var imageCapture: ImageCapture
    private lateinit var imageCaptureBuilder: ImageCapture.Builder

    private var permissions: Boolean = true

    private var intermediateScreen: Boolean = false

    val permission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
            //handle individual results if desired
            if (map[Manifest.permission.CAMERA] == true && map[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) {
                // Access granted
            }
            map.entries.forEach { entry ->
                when (entry.key) {
                    Manifest.permission.CAMERA ->
//                        mBinding.iconCameraPermission.isEnabled = entry.value
                        permissions = permissions && entry.value
                    Manifest.permission.WRITE_EXTERNAL_STORAGE ->
//                        mBinding.iconMicrophonePermission.isEnabled = entry.value
                        permissions = permissions && entry.value
                }
            }
        }

    /**
     * Additional data for recordings.
     */
    companion object {
        private val TAG = RecordingActivity::class.java.simpleName

        /**
         * Record video at 15 Mbps. At 1080p30, this level of detail should be more than high
         * enough.
         */
        private const val RECORDER_VIDEO_BITRATE: Int = 15_000_000


        /**
         * The minimum recording time is one second.
         */
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            // Select front camera as a default
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview)

                // Insert Mediapipe use case here


            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

            // video recording

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD))

            val recorder = Recorder.Builder()
                .setExecutor(cameraExecutor).setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            try {
                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

            //Bind Mediapipe
//            if (::imageCaptureBuilder.isInitialized) {
//                imageCapture = imageCaptureBuilder.build()
//                val camera = cameraProvider.bindToLifecycle(
//                    this, cameraSelector, preview, imageCapture
//                )
//            }

            // Create MediaStoreOutputOptions for our recorder

            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss.SSS", Locale.US)
            val currentWord = this@RecordingActivity.currentWord
            filename = "${UID}-${category}-${sdf.format(Date())}"
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
                            videoStartTime = Calendar.getInstance().time
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

                            if (error != VideoRecordEvent.Finalize.ERROR_NONE) {
                                Log.d("currRecording", "Error in saving")
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

                                var loadingScreen = findViewById<LinearLayout>(R.id.loadingScreen)
                                loadingScreen.alpha = 0.0f

                                var loadingWheel = findViewById<RelativeLayout>(R.id.loadingPanel)
                                loadingWheel.visibility = View.INVISIBLE

                                Log.d("currRecording", "Recording Finalized")

                                val filterMatrix = ColorMatrix()
                                filterMatrix.setSaturation(0.0f)
                                val filter = ColorMatrixColorFilter(filterMatrix)
                                recordingLightView.colorFilter = filter
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
         *
         * TODO: Streamline this flow so that users don't need to restart the app at all.
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

                            currStartTime = Calendar.getInstance().time

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
                            recordingList.add(RecordingEntryVideo(outputFile, videoStartTime, currStartTime, Calendar.getInstance().time))

                            val wordPagerAdapter = wordPager.adapter as WordPagerAdapter
                            wordPagerAdapter.updateRecordingList()

                            runOnUiThread(Runnable() {
                                val currFragment =
                                    supportFragmentManager.findFragmentByTag("f"+wordPager.currentItem) as WordPromptFragment
                                currFragment.updateWordCount(countMap.getOrDefault(currentWord, 0) + 1)
                                countMap[currentWord] = countMap.getOrDefault(currentWord, 0) + 1
                            })
                            // copyFileToDownloads(this@RecordingActivity, outputFile)
                            // outputFile = createFile(this@RecordingActivity)

                            // Send a haptic feedback on recording end
                            // delay(100)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Log.d(TAG, "Requesting haptic feedback (R+)")
                                recordButton.performHapticFeedback(HapticFeedbackConstants.REJECT)
                            } else {
                                Log.d(TAG, "Requesting haptic feedback (R-)")
                                recordButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        }
                        wordPager.isUserInputEnabled = true
                        recordButton.backgroundTintList = ColorStateList.valueOf(0xFFF80000.toInt())
                        recordButton.clearColorFilter()
                    }
                }
                true
            }
        }
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

//    private suspend fun createCaptureSession(
//        device: CameraDevice,
//        targets: List<Surface>,
//        handler: Handler? = null
//    ): CameraCaptureSession = suspendCoroutine { cont ->
//
//        // Creates a capture session using the predefined targets, and defines a session state
//        // callback which resumes the coroutine once the session is configured
//        device.createCaptureSession(targets, object: CameraCaptureSession.StateCallback() {
//            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
//
//            override fun onConfigureFailed(session: CameraCaptureSession) {
//                val exc = RuntimeException("Camera ${device.id} session configuration failed")
//                Log.e(TAG, exc.message, exc)
//                // cont.resumeWithException(exc)
//            }
//        }, handler)
//    }

    override fun onStop() {
        super.onStop()
        try {
//            session.close()
            camera.close()
            cameraThread.quitSafely()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
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

//        setContentView(R.layout.activity_record)

        context = this

//        outputFile = createFile(this)

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

        category = if (bundle?.containsKey("CATEGORY") == true) {
            bundle.getString("CATEGORY").toString()
        } else {
            "randombatch"
        }

        val randomSeed = if (bundle?.containsKey("SEED") == true) {
            bundle.getLong("SEED")
        } else {
            null
        }

        this.UID = if (bundle?.containsKey("UID") == true) {
            bundle.getString("UID").toString()
        } else {
            "999"
        }

        countMap = intent.getSerializableExtra("MAP") as HashMap<String, Int>

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
                super.onPageSelected(position)

                Log.d("D", "${wordList.size}")
                if (position < wordList.size) {
                    // Animate the record button back in, if necessary

                    this@RecordingActivity.currentWord = wordList[position]

                    runOnUiThread(Runnable() {
                        val currFragment =
                            supportFragmentManager.findFragmentByTag("f$position") as WordPromptFragment
                        currFragment.updateWordCount(countMap.getOrDefault(currentWord, 0))
                        countMap[currentWord] = countMap.getOrDefault(currentWord, 0)
                    })

//                    this@RecordingActivity.outputFile = createFile(this@RecordingActivity)
                    title = "${position + 1} of ${wordList.size}"

                    if (recordButtonDisabled) {
                        recordButton.isClickable = true
                        recordButton.isFocusable = true
                        recordButtonDisabled = false

                        recordButton.animate().apply {
                            alpha(1.0f)
                            duration = 250
                        }.start()
                    }

                    intermediateScreen = false
                } else if (position == wordList.size) {
                    title = "Save or continue?"

                    intermediateScreen = true

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

                    intermediateScreen = false

                    window.setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

                    currRecording.stop()

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
        })

        recordingLightView = findViewById<ImageView>(R.id.videoRecordingLight3)

        val filterMatrix = ColorMatrix()
        filterMatrix.setSaturation(0.0f)
        val filter = ColorMatrixColorFilter(filterMatrix)
        recordingLightView.colorFilter = filter

        initializeCamera()
    }

//    private fun setupCameraCallback() {
//        viewFinder.holder.addCallback(object: SurfaceHolder.Callback {
//            override fun surfaceCreated(holder: SurfaceHolder) {
//                Log.d(TAG,"Initializing surface!")
//                previewSurface = holder.surface
//
//                holder.setFixedSize(1920, 1080)
//                initializeCamera()
//            }
//
//            override fun surfaceChanged(
//                holder: SurfaceHolder,
//                format: Int,
//                width: Int,
//                height: Int
//            ) {
//                Log.d(TAG, "Camera preview surface changed!")
//                // PROBABLY NOT THE BEST IDEA!
////                previewSurface = holder.surface
////                initializeCamera()
//            }
//
//            override fun surfaceDestroyed(holder: SurfaceHolder) {
//                Log.d(TAG, "Camera preview surface destroyed!")
//                previewSurface = null
//            }
//        })
//    }

    private var initializedAlready = false

    override fun onResume() {
        super.onResume()

        cameraThread = generateCameraThread()
        cameraHandler = Handler(cameraThread.looper)
//
        if (!initializedAlready) {
            initializeCamera()
//            setupCameraCallback()
            initializedAlready = true
        }
    }

    fun goToWord(index: Int) {
        wordPager.currentItem = index
    }

    fun deleteRecording(word: String, index: Int) {
        sessionVideoFiles[word]?.removeAt(index)
    }

    fun concludeRecordingSession() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
//        var recordingIndex = prefs.getInt("RECORDING_INDEX", 0)
        with (prefs.edit()) {
//            putInt("RECORDING_INDEX", recordingIndex)
            for (entry in sessionVideoFiles) {
                val key = "RECORDING_COUNT_${entry.key}"
                val recordingCount = prefs.getInt(key, 0);
                putInt(key, recordingCount + entry.value.size)
            }
            commit()
        }
//        for (word in sessionVideoFiles) {
//            for (entry in word.value) {
//                copyFileToDownloads(this.applicationContext, entry.file)
//                createTimestampFile(word.key, entry)
//            }
//        }
        createTimestampFileAllinOne(sessionVideoFiles)
        finish()
    }


    /**
     * THE CODE BELOW IS COPIED FROM Rubén Viguera at StackOverflow (CC-BY-SA 4.0),
     * with some modifications.
     * See https://stackoverflow.com/a/64357198/13206041.
     */
    private val DOWNLOAD_DIR = Environment
        .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

    // val finalUri : Uri? = copyFileToDownloads(context, downloadedFile)

    fun copyFileToDownloads(context: Context, videoFile: File): Uri? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.VideoColumns.DISPLAY_NAME, videoFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.SIZE, videoFile.length())
            }
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        } else {
            val authority = "${context.packageName}.provider"
            // Modify the line below if we add support for a subfolder within Downloads
            val destinyFile = File(DOWNLOAD_DIR, videoFile.name)
            FileProvider.getUriForFile(context, authority, destinyFile)
        }?.also { downloadedUri ->
            resolver.openOutputStream(downloadedUri).use { outputStream ->
                val brr = ByteArray(1024)
                var len: Int
                val bufferedInputStream = BufferedInputStream(FileInputStream(videoFile.absoluteFile))
                while ((bufferedInputStream.read(brr, 0, brr.size).also { len = it }) != -1) {
                    outputStream?.write(brr, 0, len)
                }
                outputStream?.flush()
                bufferedInputStream.close()
            }
        }
    }

    /**
     * End borrowed code from Rubén Viguera.
     */

    fun createTimestampFileAllinOne(sampleVideos: HashMap<String, ArrayList<RecordingEntryVideo>>) {
        // resort sampleVideos around files

//        var sampleVideoFile = sampleVideoRecording.file
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
    /**
     * End borrowed code from Rubén Viguera.
     */

    fun createTimestampFile(videoWord: String, sampleVideoRecording: RecordingEntryVideo) {
        var sampleVideoFile = sampleVideoRecording.file
        val thumbnailValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sampleVideoFile.name.substring(0,sampleVideoFile.name.length - sampleVideoFile.name.lastIndexOf("."))+"-"+videoWord+"-"+"-timestamps.jpg");       //file name
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
        ThumbnailUtils.createVideoThumbnail(
            sampleVideoFile.absolutePath,
            MediaStore.Images.Thumbnails.MINI_KIND
        )?.apply {
            compress(Bitmap.CompressFormat.JPEG, 50, outputThumbnail)
            recycle()
        }
    }
}