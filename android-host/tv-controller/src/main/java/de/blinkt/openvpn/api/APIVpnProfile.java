/* Public external API parcelable originally by Arne Schwabe, 2012-2016.
 * Upstream explicitly excludes this file from GPLv2.
 * See https://github.com/schwabe/ics-openvpn/tree/master/remoteExample */
package de.blinkt.openvpn.api;
import android.os.Parcel;
import android.os.Parcelable;

public final class APIVpnProfile implements Parcelable {
    public final String mUUID;
    public final String mName;
    public final boolean mUserEditable;
    public APIVpnProfile(Parcel p) {
        mUUID = p.readString();
        mName = p.readString();
        mUserEditable = p.readInt() != 0;
    }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel p, int flags) {
        p.writeString(mUUID);
        p.writeString(mName);
        p.writeInt(mUserEditable ? 1 : 0);
    }
    public static final Creator<APIVpnProfile> CREATOR = new Creator<APIVpnProfile>() {
        @Override public APIVpnProfile createFromParcel(Parcel p) { return new APIVpnProfile(p); }
        @Override public APIVpnProfile[] newArray(int size) { return new APIVpnProfile[size]; }
    };
}
