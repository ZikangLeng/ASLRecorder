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

//import kotlinx.android.synthetic.main.activity_record.*
import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import edu.gatech.ccg.aslrecorder.R
import edu.gatech.ccg.aslrecorder.databinding.ActivityRecordBinding
import edu.gatech.ccg.aslrecorder.randomChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


const val WORDS_PER_SESSION = 5

/**
 * This class handles the recording of ASL into videos.
 *
 * @author  Matthew So <matthew.so@gatech.edu>, Sahir Shahryar <contact@sahirshahryar.com>
 * @since   October 4, 2021
 * @version 1.1.0
 */
class RecordingActivity : AppCompatActivity(), VideoConfirmationInterface {

    private lateinit var context: Context

    /**
     * The button that must be held to record video.
     */
    lateinit var recordButton: FloatingActionButton


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
     * value (ArrayList<File>) list of recording files for each word
     */
    var sessionVideoFiles = HashMap<String, ArrayList<File>>()

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

    private lateinit var fileObserver: FileObserver


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
    private lateinit var outputFile: File


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

    private var wordsComplete = 1

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

    override fun statusMessage(msg: String) {
        if (msg == "COMPLETE") {
            wordPager.isUserInputEnabled = true
            wordPager.currentItem += 1
            wordPager.isUserInputEnabled = false
        }
    }

    fun openVideoPreview(word: String, filepath: String) {
        val bundle = Bundle()
        bundle.putString("word", word)
        bundle.putInt("recordingIndex", 0)
        bundle.putString("filename", filepath)

        // Here is where the recording preview begins
        val previewFragment = VideoPreviewFragment(R.layout.recording_preview)
        previewFragment.arguments = bundle

        val transaction = this@RecordingActivity.supportFragmentManager.beginTransaction()
        transaction.add(previewFragment, "videoPreview")
        transaction.commit()
    }

//    inner class MyFileObserver(currpath: File) :
//        FileObserver(currpath) {
//        override fun onEvent(event: Int, path: String?) {
//            Log.d("VideoDisplay", "Event " + event + ", path " + path!!)
//            // display video recording
//            this@RecordingActivity.openVideoPreview("template", path)
//        }
//    }

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

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

            // video recording

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))

            val recorder = Recorder.Builder()
                .setExecutor(cameraExecutor).setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            try {
                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, videoCapture)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

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

            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)

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

                            val currentWord = this@RecordingActivity.currentWord
                            filename = "${UID}-${currentWord}-${sdf.format(Date())}.mp4"

                            outputFile = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES).toString(), filename)

//                            fileObserver = MyFileObserver(outputFile)
//                            fileObserver.startWatching()

                            Log.d(TAG, "Recording starting")

                            /**
                             * Prevents screen rotation during the video recording
                             */

                            // Create MediaStoreOutputOptions for our recorder

                            val fileOutputOptions = FileOutputOptions.Builder(outputFile).build()

                            // 2. Configure Recorder and Start recording to the mediaStoreOutput.

                            currRecording = videoCapture.output
                                .prepareRecording(context, fileOutputOptions)
                                .start(ContextCompat.getMainExecutor(super.getBaseContext())) { videoRecordEvent: VideoRecordEvent ->
                                        if (videoRecordEvent is VideoRecordEvent.Start) {
                                            Log.d("currRecording", "Recording Started")
                                        } else if (videoRecordEvent is VideoRecordEvent.Pause) {
                                            Log.d("currRecording", "Recording Paused")
                                        } else if (videoRecordEvent is VideoRecordEvent.Resume) {
                                            Log.d("currRecording", "Recording Resumed")
                                        } else if (videoRecordEvent is VideoRecordEvent.Finalize) {
                                            val finalizeEvent =
                                                videoRecordEvent as VideoRecordEvent.Finalize
                                            // Handles a finalize event for the active recording, checking Finalize.getError()
                                            val error = finalizeEvent.error
                                            if (error == VideoRecordEvent.Finalize.ERROR_NONE) {
                                                val bundle = Bundle()
                                                bundle.putString("word", currentWord)
                                                bundle.putInt("recordingIndex", 0)
                                                bundle.putString("filename", outputFile.absolutePath)

                                                // Here is where the recording preview begins
                                                val previewFragment = VideoPreviewFragment(R.layout.recording_preview)
                                                previewFragment.arguments = bundle

                                                val transaction = this@RecordingActivity.supportFragmentManager.beginTransaction()
                                                transaction.add(previewFragment, "videoPreview")
                                                transaction.addToBackStack("videoPreview")
                                                transaction.commit()
                                            }
                                            Log.d("currRecording", "Recording Finalized")
                                        } else {

                                        }

                                        // All events, including VideoRecordEvent.Status, contain RecordingStats.
                                        // This can be used to update the UI or track the recording duration.
                                        // val recordingStats = videoRecordEvent.recordingStats
                                }
                        }
                    }

                    /**
                     * User releases the record button:
                     */
                    MotionEvent.ACTION_UP -> lifecycleScope.launch(Dispatchers.IO) {

                        Log.d(TAG, "Record button up")

                        buttonLock.withLock {

                            Log.d(
                                TAG, "Recording stopped. Check " +
                                        this@RecordingActivity.getExternalFilesDir(null)?.absolutePath
                            )

                            currRecording.stop()

                            /**
                             * Add this recording to the list of recordings for the currently-selected
                             * word.
                             */
                            if (!sessionVideoFiles.containsKey(currentWord)) {
                                sessionVideoFiles[currentWord] = ArrayList()
                            }

                            val recordingList = sessionVideoFiles[currentWord]!!
                            Log.d("VideoPlayback", outputFile.absolutePath)
                            recordingList.add(outputFile)

                            val wordPagerAdapter = wordPager.adapter as WordPagerAdapter
                            wordPagerAdapter.updateRecordingList()

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

        Log.d("RECORD",
            "Choosing $WORDS_PER_SESSION words from a total of ${fullWordList.size}")
        wordList = randomChoice(fullWordList, WORDS_PER_SESSION, randomSeed)
        currentWord = wordList[0]

        // Set title bar text
        title = "1 of ${wordList.size}"

        wordsComplete = 1

        wordPager.adapter = WordPagerAdapter(this, wordList, sessionVideoFiles)
        wordPager.isUserInputEnabled = false
        wordPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)

                Log.d("D", "${wordList.size}")
                Log.d("D/Curr position", "${position}")
                Log.d("D/Curr complete", "${wordsComplete}")
                // Animate the record button back in, if necessary.
                if (recordButtonDisabled) {
                    recordButton.animate().apply {
                        alpha(1.0f)
                        duration = 250
                    }.start()

                    recordButtonDisabled = false
                    recordButton.isClickable = true
                    recordButton.isFocusable = true
                }

                this@RecordingActivity.currentWord = wordList[position]
//                    this@RecordingActivity.outputFile = createFile(this@RecordingActivity)
                title = "${position + 1} of ${wordList.size}"
//                else {
//                    // Hide record button and move the slider to the front (so users can't
//                    // accidentally press record)
//                    recordButton.animate().apply {
//                        alpha(0.0f)
//                        duration = 250
//                    }.start()
//
//                    recordButton.isClickable = false
//                    recordButton.isFocusable = false
//                    recordButtonDisabled = true
//
//                    title = "Session summary"
            }
        })

        recordButton = findViewById(R.id.recordButton)
        recordButton.isHapticFeedbackEnabled = true

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

    public fun goToWord(index: Int) {
        wordPager.currentItem = index
    }

    fun deleteRecording(word: String, index: Int) {
        sessionVideoFiles[word]?.removeAt(index)
    }

    fun concludeRecordingSession() {
        for (entry in sessionVideoFiles) {
            for (file in entry.value) {
                copyFileToDownloads(this.applicationContext, file)
            }
        }

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

}