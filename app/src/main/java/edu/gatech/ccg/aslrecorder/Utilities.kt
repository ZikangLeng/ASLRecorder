/**
 * Utilities.kt
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
package edu.gatech.ccg.aslrecorder

import android.util.Log
import edu.gatech.ccg.aslrecorder.recording.RecordingEntryVideo
import java.io.File
import java.lang.Math.min
import java.security.MessageDigest
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.random.Random

import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Selects `count` elements from `list` at random, using the designated seed if given,
 * or a random seed otherwise.
 */
fun <T> randomChoice(list: List<T>, count: Int, seed: Long? = null): ArrayList<T> {
    if (count == 0) {
        return ArrayList()
    }

    /**
     * Because Kotlin pointlessly restricts function parameters to being constants,
     * we need to make this duplicate variable
     */
    var seed2 = seed
    if (seed2 == null) {
        seed2 = Random.nextLong()
    }

    // Initialize Random object and resulting ArrayList.
    val rand = Random(seed2)
    var result = ArrayList<T>()

    if (count == 1) {
        result.add(list[rand.nextInt(list.size)])
        return result
    }

    /**
     * pickedSet: a HashSet of array indices which have been already selected. Used for
     *            determining, in O(1) time, which elements have already been chosen and
     *            should not be re-chosen for the result.
     * pickedList: an ArrayList of indices which have already been selected. This is used
     *             after random selection is complete, in order to map the selected indices
     *             to their matching elements in the original list.
     */
    val pickedSet = HashSet<Int>()
    val pickedList = ArrayList<Int>()

    /**
     * This algorithm trades space efficiency for time efficiency in cases where the user
     * has asked for a random selection of MOST of the elements in the original list (for example,
     * choosing 9 elements out of a list of 10 elements). Once you get to a point where most
     * indices have already been selected, it takes an increasing number of random selections
     * to pick an index that has not already been included in the random choice. When choosing
     * the final element in the above example, eight of the ten indices will already have been
     * chosen, meaning the last choice requires (on average) 5 attempts to choose a valid
     * index. This can get much, much worse when the size of the array gets larger.
     *
     * To stave off this issue, this algorithm creates a list of indices that have not yet been
     * picked ONLY once we cross a specific threshold. This prevents us from creating a comically
     * large list of indices that haven't been selected when it doesn't save us much time.
     * However, once we cross that threshold, taking the effort to create a list of indices that
     * haven't been picked yet, then choosing at random from that list, saves a lot of time.
     *
     * notYetPicked: an ArrayList of indices of the original list that have not yet been selected
     *               for the final result. The size of this list will be no greater than
     *               list.size * (1 - threshold).
     * selectingFromNYP: a boolean dictating whether we are selecting indices from notYetPicked
     *                   or still picking indices at random in the range [0, list.size).
     * threshold: the point at which selectingFromNYP should become true and notYetPicked should
     *            be constructed. Note that this value is compared to `i / list.size` (i.e., we
     *            are dividing the current number of selected indices versus the total size of
     *            the list). If threshold = 0.8, and we are selecting 3 items from a list of 10,
     *            then we will never cross the threshold and activate this function.
     */
    val notYetPicked = ArrayList<Int>()
    var selectingFromNYP = false
    val threshold = 0.8

    for (i in 1..count) {
        var index: Int
        if (selectingFromNYP) {
            val nypIndex = rand.nextInt(notYetPicked.size)
            index = notYetPicked[nypIndex]
            notYetPicked.removeAt(nypIndex)
        } else {
            do {
                index = rand.nextInt(list.size)
            } while (pickedSet.contains(index))
        }

        pickedSet.add(index)
        pickedList.add(index)

        if (i < count && !selectingFromNYP) {
            val ratio = i.toFloat() / list.size
            if (ratio > threshold) {
                selectingFromNYP = true
                for (j in list.indices) {
                    if (!pickedSet.contains(j)) {
                        notYetPicked.add(j)
                    }
                }
            }
        }
    }

    for (index in pickedList) {
        result.add(list[index])
    }

    return result
}


fun padZeroes(number: Int, digits: Int = 5): String {
    val asString = number.toString()
    if (asString.length >= digits) {
        return asString
    }

    return "0".repeat(digits - asString.length) + asString
}

fun <T> weightedRandomChoice(list: List<T>, weights: List<Float>, count: Int,
                             seed: Long? = null): ArrayList<T> {
    Log.d("DEBUG", "weightedRandomChoice elements: [" +
            list.joinToString(", ") + "]")

    val result = ArrayList<T>()

    var seed2 = seed
    if (seed2 == null) {
        seed2 = Random.nextLong()
    }

    val rand = Random(seed2)

    if (count == 0 || list.isEmpty()) {
        return result
    }

    val totalWeight = weights.sum()
    if (totalWeight == 0.0f) {
        return result
    }

    val indexedWeights = ArrayList<Pair<Float, Int>>()
    var i = 0
    while (i < weights.size) {
        indexedWeights.add(Pair(weights[i], i))
        i++
    }

    indexedWeights.sortBy { it.first }

    val cumulativeSums = ArrayList<Float>()
    var sum = 0.0f
    for (elem in indexedWeights) {
        sum += elem.first
        cumulativeSums.add(sum)
    }

    Log.d("DEBUG", "weightedRandomChoice cumulative weights: [" +
            cumulativeSums.joinToString(", ") + "]")

    for (j in 1..count) {
        val weightedPoint = rand.nextFloat() * sum
        val correspondingIndex = binarySearchRegion(cumulativeSums, weightedPoint,
            0, cumulativeSums.size)

        val weightData = indexedWeights[correspondingIndex]
        val actualSelectionIndex = weightData.second
        result.add(list[actualSelectionIndex])

        // Adjust sums
        for (k in correspondingIndex until cumulativeSums.size) {
            cumulativeSums[k] -= weightData.first
        }

        cumulativeSums.removeAt(correspondingIndex)
    }

    return result
}


/**
 * Find the region that contains the target integer. For example:
 *
 * [ 0.0, 1.5, 3.7, 4.9, 8.0, 11.6, 17.7 ] with weight 9.4
 *
 * Round 1: lo = 0, hi = 7, mid = 3    -> mid (4.9) >!= target, mid+1 (8.0) >!= target
 * Round 2: lo = 3, hi = 7, mid = 5    -> mid (11.6) >= target
 * Round 3: lo = 3, hi = 5, mid = 4    -> mid (8.0) >!= target, mid+1 (11.6) >= target -> return 4
 */
fun binarySearchRegion(regions: List<Float>, target: Float, lo: Int, hi: Int): Int {
    if (lo == hi) {
        return lo
    }

    val mid = (lo + hi) / 2
    return when {
        regions[mid] >= target     -> binarySearchRegion(regions, target, lo, mid)
        mid + 1 == regions.size    -> mid
        regions[mid + 1] >= target -> mid

        else                       -> binarySearchRegion(regions, target, mid, hi)
    }
}

fun convertRecordingEntryVideoToString(recordingEntry: RecordingEntryVideo): String {
    var conversion: StringBuilder = StringBuilder()

    conversion.append("{\'file\': \'${recordingEntry.file.absolutePath}\', ")
    conversion.append("\'videoStart\': \'${recordingEntry.videoStart}\', ")
    conversion.append("\'signStart\': \'${recordingEntry.signStart}\', ")
    conversion.append("\'signEnd\': \'${recordingEntry.signEnd}\'}")

    return conversion.toString()
}

fun convertRecordingListToString(sessionVideoFiles: HashMap<String, ArrayList<RecordingEntryVideo>>): String {
    if (sessionVideoFiles.isEmpty()) {
        return "{}"
    }

    var conversion: StringBuilder = StringBuilder()

    conversion.append("{")

    for ((key, value) in sessionVideoFiles) {
        conversion.append("\'$key\': [")
        for (entry in value) {
            conversion.append("${entry}, ")
        }

        if (value.isNotEmpty()) {
            conversion.delete(conversion.length - 2, conversion.length)
        }

        conversion.append("], ")
    }

    if (sessionVideoFiles.isNotEmpty()) {
        conversion.delete(conversion.length - 2, conversion.length)
    }

    conversion.append("}")
    return conversion.toString()
}

fun <T> lowestCountRandomChoice(list: List<T>, numRecordings: List<Int>, count: Int,
                             seed: Long? = null): ArrayList<T> {
    Log.d("DEBUG", "weightedRandomChoice elements: [" +
            list.joinToString(", ") + "]")

    val result = ArrayList<T>()

    var seed2 = seed
    if (seed2 == null) {
        seed2 = Random.nextLong()
    }

    val rand = Random(seed2)

    if (count == 0 || list.isEmpty()) {
        return result
    }

    var recordingCounts = numRecordings.zip(list)
    var recordingCountsMap = TreeMap<Int, ArrayList<T>>()
    for (signCount in recordingCounts) {
        if (!recordingCountsMap.containsKey(signCount.first)) {
            recordingCountsMap[signCount.first] = ArrayList<T>()
        }
        recordingCountsMap[signCount.first]?.add(signCount.second)
    }

    var countRemaining = count

    for (entry in recordingCountsMap.entries.iterator()) {
        if (countRemaining <= 0) {
            break
        }
        val currSigns = entry.value
        val numSelected = min(currSigns.size, countRemaining)
        result.addAll(randomChoice(currSigns, numSelected))
        countRemaining -= numSelected
    }

    return result
}

/**
 * Sends an email from the given address to the list of recipients,
 * with a given subject and message content.
 *
 * Based on code by Stack Overflow user Blundell (CC BY-SA 4.0)
 * https://stackoverflow.com/a/60090464
 */
fun sendEmail(from: String, to: List<String>, subject: String, content: String, password: String) {
    val props = Properties()

    val server = "smtp.gmail.com"

    val auth = PasswordAuthentication(
        "gtsignstudy.confirmation@gmail.com",
        password
    )

    props["mail.smtp.auth"] = "true"
    props["mail.user"] = from
    props["mail.smtp.host"] = server
    props["mail.smtp.port"] = "587"
    props["mail.smtp.starttls.enable"] = "true"
    props["mail.smtp.ssl.trust"] = server
    props["mail.mime.charset"] = "UTF-8"

    props["mail.smtp.connectiontimeout"] = "10000"
    props["mail.smtp.timeout"] = "10000"

    val msg: Message = MimeMessage(Session.getDefaultInstance(props, object : Authenticator() {
        override fun getPasswordAuthentication() = auth
    }))

    msg.setFrom(InternetAddress(from))
    msg.sentDate = Calendar.getInstance().time

    val recipients = to.map { InternetAddress(it) }
    msg.setRecipients(Message.RecipientType.TO, recipients.toTypedArray())

    msg.replyTo = arrayOf(InternetAddress(from))

    msg.addHeader("X-Mailer", "ASLRecorder")
    msg.addHeader("Precedence", "bulk")
    msg.subject = subject

    msg.setContent(MimeMultipart().apply {
        addBodyPart(MimeBodyPart().apply {
            setText(content, "iso-8859-1")
        })
    })

    Log.d("EMAIL", "Attempting to send email with subject '$subject' and " +
            "message '$content'")

    try {
        Transport.send(msg)
    } catch (ex: MessagingException) {
        Log.d("EMAIL", "Email send failed: ${ex.message}")
    }
}

/**
 * Computes the MD5 hash for a File object.
 *
 * Written by Stack Overflow user broc.seib (CC BY-SA 4.0)
 * https://stackoverflow.com/a/62963461
 */
fun File.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    return this.inputStream().use { fis ->
        val buffer = ByteArray(8192)
        generateSequence {
            when (val bytesRead = fis.read(buffer)) {
                -1 -> null
                else -> bytesRead
            }
        }.forEach { bytesRead -> md.update(buffer, 0, bytesRead) }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}