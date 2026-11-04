package android.net

import android.os.Parcel

class FakeUri(
    private val fakeScheme: String,
    private val fakePath: String
) : Uri() {
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getScheme(): String = fakeScheme
    override fun getSchemeSpecificPart(): String = "//$fakePath"
    override fun getEncodedSchemeSpecificPart(): String = "//$fakePath"
    override fun getAuthority(): String = ""
    override fun getEncodedAuthority(): String = ""
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String = ""
    override fun getPort(): Int = -1
    override fun getPath(): String = fakePath
    override fun getEncodedPath(): String = fakePath
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): MutableList<String> = mutableListOf(fakePath)
    override fun getLastPathSegment(): String = fakePath
    override fun buildUpon(): Builder = throw UnsupportedOperationException()
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {}
    override fun toString(): String = "$fakeScheme://$fakePath"
}
