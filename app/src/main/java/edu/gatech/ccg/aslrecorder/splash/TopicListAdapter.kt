/**
 * TopicListAdapter.kt
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
package edu.gatech.ccg.aslrecorder.splash

import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import edu.gatech.ccg.aslrecorder.R
import edu.gatech.ccg.aslrecorder.recording.RecordingActivity
import edu.gatech.ccg.aslrecorder.recording.WORDS_PER_SESSION

const val DISABLE_RANDOM = false
const val RANDOM_SEED = 1L

/**
 *
 */
class TopicListAdapter: RecyclerView.Adapter<TopicListAdapter.TopicListItem>() {

    var UID: String = ""

    class TopicListItem(itemView: View):

        RecyclerView.ViewHolder(itemView) {

        lateinit var wordList: ArrayList<String>
        lateinit var title: String
        lateinit var description: String

        fun setData(wordList: ArrayList<String>,
                    title: String, description: String, UID: String) {
            this.wordList = wordList
            this.title = title
            this.description = description

            // Set labels
            val titleText = itemView.findViewById<TextView>(R.id.cardTitle)
            titleText.text = title

            val descText = itemView.findViewById<TextView>(R.id.cardDescription)
            descText.text = description

            // When the user clicks on this card, start a recording session
            // using the words in this list.
            itemView.setOnClickListener {
                val recordingActivityIntent = Intent(itemView.context,
                    RecordingActivity::class.java).apply {
                    putStringArrayListExtra("WORDS", wordList)
                    putExtra("UID", UID)

                    if (DISABLE_RANDOM) {
                        putExtra("SEED", RANDOM_SEED)
                    }
                }

                itemView.context.startActivity(recordingActivityIntent)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopicListItem {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.splash_card, parent, false)

        return TopicListItem(view)
    }

    override fun onBindViewHolder(holder: TopicListItem, position: Int) {
        val words: ArrayList<String>
        /**
         * First item is a way to choose words across all categories
         */
        if (position == 0) {
            words = ArrayList()
            for (topic in WordDefinitions.values()) {
                val wordArray = holder.itemView.context.resources.getStringArray(topic.resourceId)
                words.addAll(listOf(*wordArray))
            }
            holder.setData(words, "All words",
                "$WORDS_PER_SESSION random words from any category", UID)
        }

        /**
         * Remaining list items are for choosing words from specific categories
         */
        else {
            val topic = WordDefinitions.values()[position - 1]
            val wordArray = holder.itemView.context.resources.getStringArray(topic.resourceId)
            words = ArrayList(listOf(*wordArray))
            holder.setData(words, topic.title, topic.desc, UID)
        }
    }

    override fun getItemCount(): Int {
        Log.d("TopicListAdapter", "Item count = ${WordDefinitions.values().size}")
        return WordDefinitions.values().size + 1
    }

    override fun getItemViewType(position: Int): Int {
        return R.layout.splash_card
    }

}


