/**
 * WordPromptFragment.kt
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

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import edu.gatech.ccg.aslrecorder.R
import edu.gatech.ccg.aslrecorder.splash.SplashScreenActivity

class WordPromptFragment(label: String, @LayoutRes layout: Int): Fragment(layout) {

    var label: String = label
    lateinit var counter: TextView

    private var TARGET_RECORDINGS: Int = 1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val textField = view.findViewById<TextView>(R.id.promptText)
        textField.text = label

        counter = view.findViewById(R.id.recordingCounter)
        counter.text = "0 / $TARGET_RECORDINGS"

//        counter = view.findViewById(R.id.recordingCounter)
//        counter.text = "0 / $TARGET_RECORDINGS"

        val helpButton: Button = view.findViewById(R.id.helpButton)
        // Is there a video for this recording?
        try {
            val videoTutorial = context?.resources?.assets?.openFd("videos/$label.mp4")

            if (videoTutorial != null) {
                helpButton.setOnClickListener {
                    val bundle = Bundle()
                    bundle.putString("word", label)
                    bundle.putBoolean("landscape", true)

                    val previewFragment = VideoPreviewFragment(R.layout.recording_preview)
                    previewFragment.arguments = bundle

                    val transaction = requireActivity().supportFragmentManager.beginTransaction()
                    transaction.add(previewFragment, "videoPreview")
                    transaction.commit()
                }
            } else {
                helpButton.isEnabled = false
            }
        } catch (e: Exception) {
            // No video available, so disable the button
            helpButton.isEnabled = false
        }
    }

    fun updateWordCount(count: Int) {
        counter.text = "$count / $TARGET_RECORDINGS"
    }

}