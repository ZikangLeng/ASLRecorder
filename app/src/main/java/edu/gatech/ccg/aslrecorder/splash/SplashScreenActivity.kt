package edu.gatech.ccg.aslrecorder.splash

//import androidx.core.app.FragmentManager;
//import androidx.core.app.FragmentTransaction;

import android.Manifest
import android.accounts.AccountManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
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
import com.mbientlab.metawear.*
import com.mbientlab.metawear.android.BtleService
import com.mbientlab.metawear.android.BtleService.LocalBinder
import com.mbientlab.metawear.module.Accelerometer
import com.tapwithus.sdk.TapListener
import com.tapwithus.sdk.TapSdk
import com.tapwithus.sdk.airmouse.AirMousePacket
import com.tapwithus.sdk.bluetooth.BluetoothManager
import com.tapwithus.sdk.bluetooth.TapBluetoothManager
import com.tapwithus.sdk.mode.RawSensorData
import com.tapwithus.sdk.mode.TapInputMode
import com.tapwithus.sdk.mouse.MousePacket
import com.tapwithus.sdk.tap.Tap
import edu.gatech.ccg.aslrecorder.*
import edu.gatech.ccg.aslrecorder.Constants.MAX_RECORDINGS_IN_SITTING
import edu.gatech.ccg.aslrecorder.Constants.RECORDINGS_PER_WORD
import edu.gatech.ccg.aslrecorder.Constants.WORDS_PER_SESSION
import edu.gatech.ccg.aslrecorder.databinding.ActivitySplashRevisedBinding
import edu.gatech.ccg.aslrecorder.recording.RecordingActivity
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.min
import edu.gatech.ccg.aslrecorder.R


class SplashScreenActivity: ComponentActivity(), ServiceConnection {
    var EXTRA_BT_DEVICE = "edu.gatech.ccg.aslrecorder.splash.SplashScreenActivity.EXTRA_BT_DEVICE"
    private var boardReady = false
    private var btDevice: BluetoothDevice? = null
    protected var mwBoard: MetaWearBoard? = null
    private var accelerometer: Accelerometer? = null
    protected var streamRoute: Route? = null
    lateinit var wristStatus: TextView

    private val sdk: TapSdk? = null
    private var lastConnectedTapAddress = ""
    lateinit var ringStatus: TextView
    lateinit var tapIdentifier: String
    //var rawMode = MutableBooleanParcelable(false)
    lateinit var rawModePref :SharedPreferences
//    lateinit var ringDataList:  ArrayList<String>


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
                    putExtra("WRIST", btDevice)
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

        if (statsShowableWords.size > 0 && statsShowableWords[statsShowableWords.lastIndex].first >= RECORDINGS_PER_WORD) {
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
            weights.add(min(1.0e-3f, (RECORDINGS_PER_WORD - count).toFloat() / (RECORDINGS_PER_WORD*wordList.size - totalRecordings).toFloat()))
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
                        putExtra("WRIST", btDevice)
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
        //connect to wrist sensor
        btDevice =
            intent.getParcelableExtra<BluetoothDevice>("WRIST")
        applicationContext.bindService(
            Intent(this, BtleService::class.java),
            this,
            BIND_AUTO_CREATE
        )
        //connect to tap strap 2
//        rawMode = true
        val bluetoothManager =
            BluetoothManager(applicationContext, BluetoothAdapter.getDefaultAdapter())
        val tapBluetoothManager = TapBluetoothManager(bluetoothManager)
        val sdk = TapSdk(tapBluetoothManager)
        sdk.enableDebug()
        sdk.registerTapListener(tapListener)
        if (sdk.isConnectionInProgress) {
            Log.d("MyTag", "A Tap is connecting")
        }
        sdk.setDefaultMode(TapInputMode.rawSensorData(2.toByte(), 2.toByte(), 2.toByte()), true)
        rawModePref = getSharedPreferences("raw_mode", MODE_PRIVATE)
        with(rawModePref.edit()) {
            putBoolean("rawMode",false)
            apply()
        }


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

        ringStatus = findViewById(R.id.ring_status)

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

//        ringDataList = ArrayList<String>()
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
        if (totalSessions >= MAX_RECORDINGS_IN_SITTING) {
            setContentView(R.layout.end_of_sitting_message)
            return
        }
        setupUI()
    }

    fun getRandomWords(wordList: ArrayList<String>, recordingCounts: ArrayList<Int>) {
        words = lowestCountRandomChoice(wordList, recordingCounts, WORDS_PER_SESSION)

        nextSessionWords = findViewById(R.id.recordingListColumn1)
        nextSessionWords.text = words.subList(0, 5).joinToString("\n")

        nextSessionWords = findViewById(R.id.recordingListColumn2)
        nextSessionWords.text = words.subList(5, 10).joinToString("\n")
    }

    companion object {
        lateinit var EXTRA_BT_DEVICE: String
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)

        var ringDataList = java.util.ArrayList<String>()
    }


    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        if (btDevice == null){
            Log.i("Mytag", "btDevice is null")
        }
        mwBoard = (service as LocalBinder).getMetaWearBoard(btDevice)
        try {
            boardReady = true
            boardReady()
        } catch (e: UnsupportedModuleException) {
        }
    }

    override fun onServiceDisconnected(componentName: ComponentName?) {
    }

    fun getBtDevice(): BluetoothDevice? {
        return btDevice
    }

    @Throws(UnsupportedModuleException::class)
    protected fun boardReady() {
        accelerometer = mwBoard!!.getModuleOrThrow(Accelerometer::class.java)
        wristStatus = findViewById(R.id.wrist_status)
        wristStatus.text = "Wrist Sensor: Connected"
    }

    private val tapListener: TapListener = object : TapListener {
        override fun onBluetoothTurnedOn() {
            Log.d("MyTag","Bluetooth turned ON")
        }

        override fun onBluetoothTurnedOff() {
            Log.d("MyTag","Bluetooth turned OFF")
        }

        override fun onTapStartConnecting(tapIdentifier: String) {
            Log.d("MyTag","Tap started connecting - $tapIdentifier")
        }

        override fun onTapConnected(tapIdentifier: String) {
            ringStatus.text = "Ring Sensor: Connected"
            Log.d("MyTag","TAP connected $tapIdentifier")
            val tap: Tap? = sdk?.getCachedTap(tapIdentifier)
            if (tap == null) {
                Log.d("MyTag","Unable to get cached Tap")
                return
            }
            lastConnectedTapAddress = tapIdentifier
            Log.d("MyTag",tap.toString())

        }

        override fun onTapDisconnected(tapIdentifier: String) {
            ringStatus.text = "Ring Sensor: Not Connected"
            Log.d("MyTag","TAP disconnected $tapIdentifier")
        }

        override fun onTapResumed(tapIdentifier: String) {
            Log.d("MyTag","TAP resumed $tapIdentifier")
            val tap: Tap? = sdk?.getCachedTap(tapIdentifier)
            if (tap == null) {
                Log.d("MyTag","Unable to get cached Tap")
                return
            }
            Log.d("MyTag",tap.toString())
        }

        override fun onTapChanged(tapIdentifier: String) {
            Log.d("MyTag","TAP changed $tapIdentifier")
            val tap: Tap? = sdk?.getCachedTap(tapIdentifier)
            if (tap == null) {
                Log.d("MyTag","Unable to get cached Tap")
                return
            }
            Log.d("MyTag","TAP changed $tap")
        }

        override fun onTapInputReceived(tapIdentifier: String, data: Int, repeatData: Int) {
            Log.d("MyTag","TapInputReceived - $tapIdentifier, $data, repeatData = $repeatData")
        }

        override fun onTapShiftSwitchReceived(tapIdentifier: String, data: Int) {
            Log.d("MyTag","TapSwitchShiftReceived - $tapIdentifier, $data")
        }

        override fun onMouseInputReceived(tapIdentifier: String, data: MousePacket) {}

        override fun onAirMouseInputReceived(tapIdentifier: String, data: AirMousePacket) {
            Log.d("MyTag",tapIdentifier + " air mouse input received " + data.gesture.int)
        }

        override fun onRawSensorInputReceived(tapIdentifier: String, rsData: RawSensorData) {
//            println(rawMode.value)
            val rawMode = rawModePref.getBoolean("rawMode", false)
//            println(rawMode)
            if (rawMode && rsData.dataType === RawSensorData.DataType.Device ){
                println(rsData)
                ringDataList.add(rsData.toString())
            }
//            //RawSensorData Object has a timestamp, dataType and an array points(x,y,z).
//            if (rsData.dataType === RawSensorData.DataType.Device) {
//                // Fingers accelerometer.
//                // Each point in array represents the accelerometer value of a finger (thumb, index, middle, ring, pinky).
//                val thumb = rsData.getPoint(RawSensorData.iDEV_INDEX)
//                println(rsData)
//                if (thumb != null) {
//                    val x = thumb.x
//                    val y = thumb.y
//                    val z = thumb.z
//                }
//                // Etc... use indexes: RawSensorData.iDEV_THUMB, RawSensorData.iDEV_INDEX, RawSensorData.iDEV_MIDDLE, RawSensorData.iDEV_RING, RawSensorData.iDEV_PINKY
//            }
        }

        override fun onTapChangedState(tapIdentifier: String, state: Int) {
            Log.d("MyTag","$tapIdentifier changed state: $state")
        }

        override fun onError(tapIdentifier: String, code: Int, description: String) {
            Log.d("MyTag","Error - $tapIdentifier - $code - $description")
        }
    }

}