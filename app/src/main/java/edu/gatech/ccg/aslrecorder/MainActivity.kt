package edu.gatech.ccg.aslrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.util.Range
import android.view.*
import androidx.camera.core.CameraSelector
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedInputStream

import java.io.File
import java.io.FileInputStream
import java.lang.IllegalStateException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


/**
 * @author  Sahir Shahryar <sahirshahryar@gatech.edu>
 * @since   October 4, 2021
 * @version 1.0.0
 */
class MainActivity : AppCompatActivity() {

    /**
     * Whether or not the application should use the rear camera. The functionality
     * to switch cameras on-the-fly has not yet been implemented, so this is a constant
     * that must be set at compile time.
     */
    private val useBackCamera = false

    /**
     * The camera preview.
     */
    lateinit var cameraView: SurfaceView

    /**
     * The button that must be held to record video.
     */
    lateinit var recordButton: FloatingActionButton

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
     * SUBSTANTIAL PORTIONS OF THE BELOW CODE BELOW ARE BORROWED
     * from the Android Open Source Project (AOSP), WHICH IS LICENSED UNDER THE
     * Apache 2.0 LICENSE (https://www.apache.org/licenses/LICENSE-2.0). (c) 2020 AOSP.
     *
     * SEE https://github.com/android/camera-samples/blob/master/Camera2Video/app/
     *     src/main/java/com/example/android/camera2/video/fragments/CameraFragment.kt
     */

    private lateinit var camera: CameraDevice

    private lateinit var cameraThread: HandlerThread

    private lateinit var cameraHandler: Handler

    private lateinit var session: CameraCaptureSession

    private val cameraManager: CameraManager by lazy {
        val context = this.applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

//    private val outputFile: File by lazy {
//        createFile(this)
//    }

    private var previewSurface: Surface? = null

    private lateinit var previewRequest: CaptureRequest

    private lateinit var recordRequest: CaptureRequest

    private lateinit var outputFile: File

    private lateinit var recordingSurface: Surface

    private lateinit var recorder: MediaRecorder

    private var recordingStartMillis: Long = 0L

    private fun createRecordingSurface(): Surface {
        val surface = MediaCodec.createPersistentInputSurface()
        val recorder = MediaRecorder()
        prepareRecorder(recorder, surface).apply {
            prepare()
            release()
        }

        return surface
    }

    private fun prepareRecorder(rec: MediaRecorder, surface: Surface)
            = rec.apply {
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(outputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        setVideoFrameRate(30)

        // TODO: Device-specific!
        setVideoSize(1920, 1080)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setInputSurface(surface)
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 15_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        /** Creates a [File] named with the current date and time */
        private fun createFile(activity: MainActivity): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
            val currentWord = activity.currentWord
            return File(activity.applicationContext.filesDir,
                "${currentWord}-${sdf.format(Date())}.mp4")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        // Test camera permission
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
               PackageManager.PERMISSION_GRANTED) {

            val errorRoot = findViewById<ConstraintLayout>(R.id.main_root)
            val errorMessage = layoutInflater.inflate(R.layout.permission_error, errorRoot, false)
            errorRoot.addView(errorMessage)

            // Dim Record button?
            recordButton.backgroundTintList = ColorStateList.valueOf(0xFFFA9389.toInt())
        } else {
            val manager: CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var cameraId = ""

            for (id in manager.cameraIdList) {
                val face = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (face == CameraSelector.LENS_FACING_FRONT) {
                    cameraId = id
                    break
                }
            }

            if (cameraId == "") {
                throw IllegalStateException("No front camera available")
            }

            // TODO: Adjust aspect ratio to 16:9 so it matches recording
            camera = openCamera(cameraManager, cameraId, cameraHandler)

            val targets = listOf(previewSurface!!, recordingSurface)
            session = createCaptureSession(camera, targets, cameraHandler)

            previewRequest = session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                previewSurface?.let { addTarget(it) }
            }.build()

            session.setRepeatingRequest(previewRequest, null, /* cameraHandler */ null)

            // React to user touching the capture button
            recordButton.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> lifecycleScope.launch(Dispatchers.IO) {

                        // Prevents screen rotation during the video recording
                        this@MainActivity.requestedOrientation =
                            ActivityInfo.SCREEN_ORIENTATION_LOCKED

                        // Start recording repeating requests, which will stop the ongoing preview
                        //  repeating requests without having to explicitly call
                        //  `session.stopRepeating`
                        recordRequest = session.device.createCaptureRequest(
                            CameraDevice.TEMPLATE_RECORD).apply {
                                previewSurface?.let { addTarget(it) }
                                addTarget(recordingSurface)

                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    Range(30, 30))
                            }.build()

                        session.setRepeatingRequest(recordRequest, null, cameraHandler)

                        prepareRecorder(recorder, recordingSurface)

                        // Finalizes recorder setup and starts recording
                        recorder.apply {
                            // For now, actually reading the device orientation is unnecessary
                            setOrientationHint(270)
                            prepare()
                            start()
                        }
                        recordingStartMillis = System.currentTimeMillis()
                        Log.d(TAG, "Recording started")

                        // Starts recording animation
                        // fragmentCameraBinding.overlay.post(animationTask)

                        // Send recording started haptic
                        // delay(100)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Log.d(TAG, "Requesting haptic feedback (R+)")
                            recordButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        } else {
                            Log.d(TAG, "Requesting haptic feedback (R-)")
                            recordButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                        }
                    }

                    MotionEvent.ACTION_UP -> lifecycleScope.launch(Dispatchers.IO) {

                        // Unlocks screen rotation after recording finished
                        // requireActivity().requestedOrientation =
                        //    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                        // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
                        val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
                        if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                            delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
                        }

                        Log.d(TAG, "Recording stopped. Check " +
                                this@MainActivity.getExternalFilesDir(null)?.absolutePath
                        )
                        recorder.stop()

                        copyFileToDownloads(this@MainActivity, outputFile)
                        outputFile = createFile(this@MainActivity)

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
                }

                true
            }
        }


        // TODO: Determine `cameraId`'s value

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
                this@MainActivity.finish()
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

    override fun onStop() {
        super.onStop()
        try {
            session.close()
            camera.close()
            cameraThread.quitSafely()
            recorder.release()
            recordingSurface.release()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        recorder.release()
        recordingSurface.release()
    }

    /**
     * END BORROWED CODE FROM AOSP.
     */

    fun generateCameraThread() = HandlerThread("CameraThread").apply { start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        outputFile = createFile(this)

        // Set title bar text
        title = "Please hold the button and record..."

        // Set up view pager
        wordPager = findViewById(R.id.wordPager)

        val wordArray = resources.getStringArray(R.array.words)
        wordList = ArrayList(listOf(*wordArray))
        currentWord = wordList[0]

        wordPager.adapter = WordPagerAdapter(this, wordList)
        wordPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)

                this@MainActivity.currentWord = wordList[position]
                this@MainActivity.outputFile = createFile(this@MainActivity)
            }
        })

        cameraView = findViewById(R.id.cameraPreview)
        recordButton = findViewById(R.id.recordButton)
        recordButton.isHapticFeedbackEnabled = true

        // setupCameraCallback()
    }

    private fun setupCameraCallback() {
        cameraView.holder.addCallback(object: SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG,"Initializing surface!")
                previewSurface = holder.surface
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

    private var initializedAlready = false

    override fun onResume() {
        super.onResume()

        cameraThread = generateCameraThread()
        cameraHandler = Handler(cameraThread.looper)

        recorder = MediaRecorder()
        recordingSurface = createRecordingSurface()

        if (!initializedAlready) {
            setupCameraCallback()
            initializedAlready = true
        }
    }


    /**
     * THE CODE BELOW IS COPIED FROM Rubén Viguera at StackOverflow (CC-BY-SA 4.0).
     * See https://stackoverflow.com/a/64357198/13206041.
     */
    private val DOWNLOAD_DIR = Environment
        .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

    // val finalUri : Uri? = copyFileToDownloads(context, downloadedFile)

    fun copyFileToDownloads(context: Context, videoFile: File): Uri? {
        val resolver = context.contentResolver
        // val relativeLoc = Environment.DIRECTORY_PICTURES + File.pathSeparator + "ASLRecorder"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                // put(MediaStore.Video.VideoColumns.RELATIVE_PATH, relativeLoc)
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

}