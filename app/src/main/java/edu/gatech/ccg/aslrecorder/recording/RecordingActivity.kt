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
import android.app.PendingIntent.getActivity
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.hardware.camera2.*
import android.media.ExifInterface
import android.media.ExifInterface.TAG_IMAGE_DESCRIPTION
import android.media.MediaCodec
import android.media.MediaRecorder
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import bolts.Task
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mbientlab.metawear.Data
import com.mbientlab.metawear.MetaWearBoard
import com.mbientlab.metawear.Route
import com.mbientlab.metawear.UnsupportedModuleException
import com.mbientlab.metawear.android.BtleService
import com.mbientlab.metawear.builder.RouteComponent
import com.mbientlab.metawear.data.Acceleration
import com.mbientlab.metawear.module.Accelerometer
import edu.gatech.ccg.aslrecorder.*
import edu.gatech.ccg.aslrecorder.Constants.RECORDINGS_PER_WORD
import edu.gatech.ccg.aslrecorder.Constants.WORDS_PER_SESSION
import edu.gatech.ccg.aslrecorder.databinding.ActivityRecordBinding
import edu.gatech.ccg.aslrecorder.splash.SplashScreenActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

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
class RecordingActivity : AppCompatActivity(), ServiceConnection {
    val CREATE_FILE = 1
    private var boardReady = false
    private var btDevice: BluetoothDevice? = null
    protected var mwBoard: MetaWearBoard? = null
    private var accelerometer: Accelerometer? = null
    protected var streamRoute: Route? = null
    private val ACC_RANG = 4f
    private val ACC_FREQ = 50f
    protected var samplePeriod = 0f
    val wristDataList = arrayListOf<Triple<Float, Float,Float>>()
    private lateinit var wristFilename: String
    lateinit var wristFile:File
    lateinit var wristFileUri: Uri

    lateinit var rawMode : MutableBooleanParcelable
    lateinit var rawModePref :SharedPreferences
    private lateinit var ringFilename: String

    companion object {
        private val TAG = RecordingActivity::class.java.simpleName


        /**
         * Record video at 15 Mbps. At 1944p30, this level of detail should be more than high
         * enough.
         */
        private const val RECORDER_VIDEO_BITRATE: Int = 15_000_000

        private const val RECORDING_HEIGHT = 2592
        private const val RECORDING_WIDTH = 1944

        private const val APP_VERSION = "1.1.6"
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
    private var isSigning = false
    private var isRecording = false
    private var endSessionOnRecordButtonRelease = false
    private var currentPage: Int = 0
    private val buttonLock = ReentrantLock()

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
    private lateinit var cameraRequest: CaptureRequest
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

    /**
     * Generates a new [Surface] for storing recording data, which will promptly be assigned to
     * the recordingSurface field above.
     */
    private fun createRecordingSurface(): Surface {
        val surface = MediaCodec.createPersistentInputSurface()
        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }

        val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.absolutePath
        outputFile = File(outputDir, "$filename.mp4")

        prepareRecorder(recorder, surface).apply {
            prepare()
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
        //setOutputFormat(MediaRecorder.OutputFormat.DEFAULT)
        setOutputFile(outputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        setVideoFrameRate(20)

        // TODO: Device-specific!
        setVideoSize(RECORDING_HEIGHT, RECORDING_WIDTH)
        setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
        //setVideoEncoder(MediaRecorder.VideoEncoder.DEFAULT)
        setInputSurface(surface)

        /**
         * The orientation of 270 degrees (-90 degrees) was determined through
         * experimentation. For now, we do not need to support other
         * orientations than the default portrait orientation.
         *
         * TODO: Verify validity of this orientation on devices other than the
         *       Pixel 5a.
         */
        setOrientationHint(270)
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
                            isSigning = true

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

                            recordButton.performHapticFeedback(HapticFeedbackConstants.REJECT)

                            runOnUiThread {
                                Log.d(
                                    "currentItem",
                                    "wordPager is incremented from ${wordPager.currentItem}"
                                )
                                wordPager.setCurrentItem(wordPager.currentItem + 1, false)
                            }

                            wordPager.isUserInputEnabled = true
                            recordButton.backgroundTintList = ColorStateList.valueOf(0xFFF80000.toInt())
                            recordButton.clearColorFilter()

                            isSigning = false
                            if (endSessionOnRecordButtonRelease) {
                                runOnUiThread {
                                    wordPager.currentItem = wordList.size + 1
                                }
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

//                holder.setFixedSize(RECORDING_HEIGHT, RECORDING_WIDTH)
                initializeCamera()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                Log.d(TAG, "New format, width, height: {$format}, {$width}, {$height}")
                Log.d(TAG, "Camera preview surface changed!")
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
            override fun onOpened(device: CameraDevice) {
                Log.d("openCamera", "New camera created with ID $cameraId")
                cont.resume(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                Log.w("openCamera", "Camera $cameraId has been disconnected")
                setResult(RESULT_CAMERA_DIED)
                finish()
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
            override fun onConfigured(session: CameraCaptureSession) {
                cont.resume(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                // cont.resumeWithException(exc)
            }
        }, handler)
    }



    private fun startRecording() {
        this@RecordingActivity.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_LOCKED

        /**
         * Create a request to record at 30fps.
         */
        cameraRequest = camera.createCaptureRequest(
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

        session.setRepeatingRequest(cameraRequest, null, cameraHandler)

        recorder.start()

        isRecording = true

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
                if (isSigning) {
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

    override fun onRestart() {
        try {
            super.onRestart()
            // Shut down app when no longer recording
            setResult(RESULT_RECORDING_DIED)
            finish()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error in RecordingActivity.onRestart()", exc)
        }
    }

    override fun onStop() {
        try {
            if (wordPager.currentItem <= wordList.size) {
                recorder.stop()
                session.stopRepeating()
                session.close()
                recorder.release()
//                cameraManager
                camera.close()
                cameraThread.quitSafely()
                recordingSurface.release()
                countdownTimer.cancel()
                cameraHandler.removeCallbacksAndMessages(null)
                Log.d("onStop", "Stop and release all recording variables")
                concludeSensorRecording()
                wristDataList.clear()
                SplashScreenActivity.Companion.ringDataList.clear()
            }
            wordPager.adapter = null
            super.onStop()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error in RecordingActivity.onStop()", exc)
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error in RecordingActivity.onDestroy()", exc)
        }
        concludeSensorRecording()
        wristDataList.clear()
        SplashScreenActivity.Companion.ringDataList.clear()
    }

    fun generateCameraThread() = HandlerThread("CameraThread").apply { start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        btDevice =
            intent.getParcelableExtra<BluetoothDevice>("WRIST")
        applicationContext.bindService(
            Intent(this, BtleService::class.java),
            this,
            BIND_AUTO_CREATE
        )

//        SplashScreenActivity.Companion.ringDataList = intent.getStringArrayListExtra("RING_DATA") as ArrayList<String>
//        rawMode = intent.getParcelableExtra<MutableBooleanParcelable>("RAW_MODE")!!
        rawModePref = getSharedPreferences("raw_mode", MODE_PRIVATE)


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

        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
        val currentWord = this@RecordingActivity.currentWord
        filename = "${userUID}-${sdf.format(Date())}"
        wristFilename = "${userUID}_wrist_${sdf.format(Date())}.csv"
        ringFilename = "${userUID}_sensor_${sdf.format(Date())}.csv"
        metadataFilename = filename

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

                Log.d("D", "${wordList.size}, ${position}")

                if (currentPage < wordList.size) {
                    // Animate the record button back in, if necessary
                    runOnUiThread {
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
                    }
                } else if (currentPage == wordList.size) {
                    runOnUiThread {
                        title = "Save or continue?"

                        recordButton.isClickable = false
                        recordButton.isFocusable = false
                        recordButtonDisabled = true

                        recordButton.animate().apply {
                            alpha(0.0f)
                            duration = 250
                        }.start()
                    }
                } else {
                    // Hide record button and move the slider to the front (so users can't
                    // accidentally press record)
                    concludeSensorRecording()
                    Log.d(
                        "MyTag", "sensor recording stopped"
                    )
                    Log.d(
                        TAG, "Recording stopped. Check " +
                                this@RecordingActivity.getExternalFilesDir(null)?.absolutePath
                    )

                    runOnUiThread {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        )
                    }

                    if (isRecording) {
                        recorder.stop()
                        session.stopRepeating()
                        session.close()
                        recorder.release()
//                cameraManager
                        camera.close()
                        cameraThread.quitSafely()
                        recordingSurface.release()
                        countdownTimer.cancel()
                        cameraHandler.removeCallbacksAndMessages(null)
                    }
                    isRecording = false

                    runOnUiThread {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

                        wordPager.isUserInputEnabled = false

                        countdownText.animate().apply {
                            alpha(0.0f)
                            duration = 250
                        }.start()

                        countdownText.visibility = View.GONE

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

        recordingSurface = createRecordingSurface()

        if (wordPager.currentItem >= wordList.size) {
            return
        } else if (!cameraInitialized) {
            setupCameraCallback()
            cameraInitialized = true
        }
    }

    fun deleteMostRecentRecording(word: String) {
        // Since only the last recording is shown, the last recording should be deleted
        if (sessionVideoFiles.containsKey(word)) {
            sessionVideoFiles[word]?.get(sessionVideoFiles[word]!!.size - 1)?.isValid = false
        }
    }

    fun concludeRecordingSession() {
        //concludeSensorRecording()
        dataToCSV()

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

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.VideoColumns.DISPLAY_NAME, outputFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.SIZE, outputFile.length())
        }

        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )

        uri?.let { contentUri ->
            contentResolver.openOutputStream(contentUri).use { outputStream ->
                val brr = ByteArray(1024)
                var len: Int
                val bufferedInputStream = BufferedInputStream(FileInputStream(outputFile.absoluteFile))
                while ((bufferedInputStream.read(brr, 0, brr.size).also { len = it }) != -1) {
                    outputStream?.write(brr, 0, len)
                }
                outputStream?.flush()
                bufferedInputStream.close()
            }
        }

        sendConfirmationEmail()
        setResult(RESULT_NO_ERROR)
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
            "${it.first} (${it.second} / ${RECORDINGS_PER_WORD})"
        }
        var body = "The user '$userId' recorded the following ${wordList.size} word(s) to the " +
                "file $filename.mp4 (MD5 = $fileDigest): $wordListWithCounts\n\n"

        var totalWordCount = 0
        for (word in completeWordList) {
            totalWordCount += prefs.getInt("RECORDING_COUNT_$word", 0)
        }

        body += "Overall progress: $totalWordCount / " +
                "${RECORDINGS_PER_WORD * completeWordList.size}\n\n"

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
                    listOf("kevenleng2003@gmail.com"), subject, body, emailPassword, wristFile)
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

            imageFd?.close()
        }

        val text = "Video successfully saved"
        val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        toast.show()
    }

    fun concludeSensorRecording() {
        accelerometer!!.stop()
        (if (accelerometer!!.packedAcceleration() == null) accelerometer!!.packedAcceleration() else accelerometer!!.acceleration()).stop()
        if (streamRoute != null) {
            streamRoute!!.remove()
            streamRoute = null
        }
        with(rawModePref.edit()) {
            putBoolean("rawMode",false)
            apply()
        }
        //dataToCSV()

        //wristDataList.clear()
    }

    fun dataToCSV() {
        wristFile = File(wristFilename)
//        val outputDirCSV = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!.absolutePath
//        wristFile = File(outputDirCSV, wristFilename)
        var dataType = "acceleration"
        var CSV_HEADER = String.format("time,x-%s,y-%s,z-%s%n", dataType, dataType, dataType)

        try {
            val fos: FileOutputStream = openFileOutput(wristFilename, MODE_PRIVATE)
            //val fos: FileOutputStream = FileOutputStream(wristFile)
            fos.write(CSV_HEADER.toByteArray())

            for (i in 0 until wristDataList.size) {
                fos.write(
                    String.format(
                        Locale.US, "%.3f,%.3f,%.3f,%.3f%n", i * samplePeriod,
                        wristDataList[i].first,
                        wristDataList[i].second,
                        wristDataList[i].third
                    ).toByteArray()
                )
            }
            fos.close()
        } catch (e:Exception ) {
            e.printStackTrace()
        }


        wristFile = context.getFileStreamPath(wristFilename)
        var contents = wristFile.readText()
        println(contents)

        //savetoDrive(wristFile, wristFilename)

        //write in ring data
        dataType = "data"
        CSV_HEADER = String.format("%s%n", dataType)
        try {
            val fos: FileOutputStream = openFileOutput(ringFilename, MODE_PRIVATE)
            fos.write(CSV_HEADER.toByteArray())

            for (i in 0 until SplashScreenActivity.Companion.ringDataList.size) {
                fos.write(
                    String.format("%s%n", SplashScreenActivity.Companion.ringDataList[i]).toByteArray()
                )
            }
            fos.close()
        } catch (e:Exception ) {
            e.printStackTrace()
        }

        val ringFile = context.getFileStreamPath(ringFilename)
        contents = ringFile.readText()
        println(contents)

        savetoDrive(ringFile, ringFilename)

        wristDataList.clear()
        SplashScreenActivity.Companion.ringDataList.clear()
    }

    fun savetoDrive(file: File, filename: String){
        val contentUri = FileProvider.getUriForFile(
            context,
            "edu.gatech.ccg.aslrecorder.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_SUBJECT, filename)
        intent.putExtra(Intent.EXTRA_STREAM, contentUri)

        val chooser = Intent.createChooser(intent, "Saving Data")

        val resInfoList: List<ResolveInfo> = context.packageManager
            .queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)

        for (resolveInfo in resInfoList) {
            val packageName = resolveInfo.activityInfo.packageName
            context.grantUriPermission(
                packageName,
                contentUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        startActivity(chooser)
    }


    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        if (btDevice == null){
            Log.i("Mytag", "btDevice is null")
        }
        mwBoard = (service as BtleService.LocalBinder).getMetaWearBoard(btDevice)
        //mwBoard.onUnexpectedDisconnect(UnexpectedDisconnectHandler { status: Int -> attemptReconnect() })
        try {
            boardReady = true
            boardReady()
            setup()
        } catch (e: UnsupportedModuleException) {
            //unsupportedModule()
        }
    }

    override fun onServiceDisconnected(componentName: ComponentName?) {
    }

    fun getBtDevice(): BluetoothDevice? {
        return btDevice
    }

    protected fun setup() {
        val editor = accelerometer!!.configure()
        editor.odr(ACC_FREQ)
        editor.range(ACC_RANG)
        editor.commit()
        samplePeriod = 1 / accelerometer!!.odr
        val producer =
            if (accelerometer!!.packedAcceleration() == null) accelerometer!!.packedAcceleration() else accelerometer!!.acceleration()
        producer.addRouteAsync { source: RouteComponent ->
            source.stream { data: Data, env: Array<Any?>? ->
                val value =
                    data.value(
                        Acceleration::class.java
                    )
                println(value)
                SplashScreenActivity.Companion.ringDataList.add(value.toString())
                println(SplashScreenActivity.Companion.ringDataList.size)
//                wristDataList.add(Triple(value.x(), value.y(), value.z()))
            }
        }.continueWith<Any?> { task: Task<Route> ->
            streamRoute = task.result
            producer.start()
            accelerometer!!.start()
            null
        }

//        rawMode.value = true
        with(rawModePref.edit()) {
            putBoolean("rawMode",true)
            apply()
        }
    }
    @Throws(UnsupportedModuleException::class)
    protected fun boardReady() {
        accelerometer = mwBoard!!.getModuleOrThrow(Accelerometer::class.java)
    }
}