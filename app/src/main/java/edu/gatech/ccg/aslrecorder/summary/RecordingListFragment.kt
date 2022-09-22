/**
 * RecordingListFragment.kt
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
package edu.gatech.ccg.aslrecorder.summary

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import edu.gatech.ccg.aslrecorder.R
import edu.gatech.ccg.aslrecorder.recording.RecordingActivity
import edu.gatech.ccg.aslrecorder.recording.RecordingEntryVideo
import java.util.*
import kotlin.collections.HashMap

class RecordingListFragment(wordList: ArrayList<String>,
                            sessionFiles: HashMap<String, ArrayList<RecordingEntryVideo>>,
                            activity: RecordingActivity,
                            @LayoutRes layout: Int): Fragment(layout) {

    val words = wordList
    val files = sessionFiles
    val recording = activity

    var recordingListAdapter: RecordingListAdapter? = null

    lateinit var saveButton: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // super.onViewCreated(view, savedInstanceState)
        Log.d("HELLO", "onViewCreated!")
        val scrollView = view.findViewById<RecyclerView>(R.id.recordingList)
        scrollView.layoutManager = LinearLayoutManager(this.context)
        recordingListAdapter = RecordingListAdapter(words, files, recording)
        scrollView.adapter = recordingListAdapter

        var loadingScreen = view.findViewById<LinearLayout>(R.id.loadingScreen)
        loadingScreen.alpha = 0.0f

        var loadingWheel = view.findViewById<RelativeLayout>(R.id.loadingPanel)
        loadingWheel.visibility = View.INVISIBLE

        this.saveButton = view.findViewById(R.id.closeSession)
        this.saveButton.setOnClickListener {
            recording.concludeRecordingSession()
        }

        determineExitButtonAvailability()
    }

    fun updateList() {
        recording.runOnUiThread {
            recordingListAdapter?.notifyDataSetChanged()
            if (this::saveButton.isInitialized) {
                determineExitButtonAvailability()
            }
        }
    }

    fun determineExitButtonAvailability() {
        this.saveButton.isEnabled = true
//        for (entry in files) {
//            if (entry.value.size > 0) {
//                this.saveButton.isEnabled = true
//                return
//            }
//        }
//
//        this.saveButton.isEnabled = false
    }


}