/**
 * RecordingListAdapter.kt
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import edu.gatech.ccg.aslrecorder.R
import edu.gatech.ccg.aslrecorder.recording.RecordingActivity
import edu.gatech.ccg.aslrecorder.recording.RecordingEntryVideo
import edu.gatech.ccg.aslrecorder.recording.VideoPreviewFragment
import java.lang.ref.WeakReference
import java.time.Duration
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class RecordingListAdapter(
    wordList: ArrayList<String>,
    sessionFiles: HashMap<String, ArrayList<RecordingEntryVideo>>,
    activity: RecordingActivity
):
    RecyclerView.Adapter<RecordingListAdapter.RecordingListItem>() {

    val words = wordList
    val recordings = sessionFiles.mapValues { it.value.subList(it.value.size - 1, it.value.size) }
    val activity = WeakReference(activity)

    val breakpoints = ArrayList<Int>()
    var totalSize = 0

    init {
        updateBreakpoints()
    }

    fun updateBreakpoints(): Int {
        breakpoints.clear()
        breakpoints.add(0)
        var nextIndex = 0
        for (word in words) {
            nextIndex += 1 + (recordings[word]?.size ?: 0)
            breakpoints.add(nextIndex)
        }

        breakpoints.removeAt(breakpoints.size - 1)
        totalSize = nextIndex

        Log.d("HELLO", "Total size = $totalSize")
        return totalSize
    }

    open class RecordingListItem(itemView: View): RecyclerView.ViewHolder(itemView)

    class SectionHeader(itemView: View): RecordingListItem(itemView) {
        fun setData(word: String, paginationIndex: Int,
                    activity: WeakReference<RecordingActivity>) {
            val label = itemView.findViewById<TextView>(R.id.recordedWord)
            label.text = word
        }
    }

    class RecordingEntry(itemView: View): RecordingListItem(itemView) {
        fun setData(word: String, recordingIndex: Int, entryPosition: Int,
                    activity: WeakReference<RecordingActivity>,
                    listAdapter: WeakReference<RecordingListAdapter>) {
            val label = itemView.findViewById<TextView>(R.id.recordingTitle)
            label.text = "Recording #${recordingIndex + 1}"

            // TODO: Show recording preview when user taps the title
            val deleteButton = itemView.findViewById<ImageButton>(R.id.deleteRecording)
            deleteButton.setOnClickListener {
                activity.get()?.deleteRecording(word, recordingIndex)

                // NOTE: This was previously notifyItemRemoved as that is more
                // efficient, but it would cause crashes when not deleting the very latest
                // recording for a given word (as the indices would be rearranged)
                listAdapter.get()?.notifyDataSetChanged()
            }

            label.setOnClickListener {
                val entry = listAdapter.get()?.recordings?.get(word)!![recordingIndex]

//                val startTime = listAdapter.get()?.times.g

                val bundle = Bundle()
                bundle.putString("word", word)
                bundle.putInt("recordingIndex", recordingIndex)
                bundle.putString("filename", entry.file.absolutePath)
                bundle.putLong("startTime", Duration.between(entry.videoStart.toInstant(), entry.signStart.toInstant()).toMillis())
                bundle.putLong("endTime", Duration.between(entry.videoStart.toInstant(), entry.signEnd.toInstant()).toMillis())

                val previewFragment = VideoPreviewFragment(R.layout.recording_preview)
                previewFragment.arguments = bundle

                val transaction = activity.get()!!.supportFragmentManager.beginTransaction()
                transaction.add(previewFragment, "videoPreview")
                transaction.commit()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingListItem {
        return when (viewType) {
            1 -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.recording_list_item, parent, false)
                RecordingEntry(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.recording_list_header, parent, false)
                SectionHeader(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecordingListItem, position: Int) {
        val decomposedIndex = getWordAndIndex(position)
        Log.d("HELLO", "Binding $position ($decomposedIndex)")
        if (holder is SectionHeader) {
            // First: Index of word in words array
            // Second: words[index] (i.e. the actual word)
            // Third: index of a particular recording inside the array at recordings[word]
            holder.setData(decomposedIndex.second, decomposedIndex.first, activity)
        } else if (holder is RecordingEntry) {
            holder.setData(decomposedIndex.second, decomposedIndex.third!!,
                           position, activity, WeakReference(this)
            )
        }
    }

    override fun getItemCount(): Int {
        Log.d("HELLO", "totalSize = $totalSize")
        return updateBreakpoints()
    }

    override fun getItemViewType(position: Int): Int {
        Log.d("HELLO", "item view type of  $position")
        val decomposedIndex = getWordAndIndex(position)
        return if (decomposedIndex.third != null) 1 else 0
    }

    fun getWordAndIndex(itemIndex: Int): Triple<Int, String, Int?> {
        var index = 1
        while (index < words.size) {
            if (breakpoints[index] > itemIndex) {
                break
            } else {
                index += 1
            }
        }

        index -= 1
        Log.d("HELLO", "itemIndex: $itemIndex, i:$index, b:$breakpoints, wsize:${words.size}")
        val wordStartingIndex = breakpoints[index]
        val word = words[index]
        var recordingIndex: Int? = null

        val relativeIndex = itemIndex - wordStartingIndex
        if (relativeIndex > 0) {
            // It should be logically impossible to access this code unless
            // there is at least one recording for the given word
            val recordingCount = recordings[word]!!.size
            recordingIndex = recordingCount - relativeIndex
        }

        return Triple(index, word, recordingIndex)
    }

}