package edu.gatech.ccg.aslrecorder

import android.os.Parcel
import android.os.Parcelable

class MutableBooleanParcelable(var value: Boolean) : Parcelable {

    constructor(parcel: Parcel) : this(parcel.readByte() != 0.toByte())

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (value) 1 else 0)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<MutableBooleanParcelable> {
        override fun createFromParcel(parcel: Parcel): MutableBooleanParcelable {
            return MutableBooleanParcelable(parcel)
        }

        override fun newArray(size: Int): Array<MutableBooleanParcelable?> {
            return arrayOfNulls(size)
        }
    }
}