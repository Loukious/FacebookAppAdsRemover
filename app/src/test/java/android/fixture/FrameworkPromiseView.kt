package android.fixture

/** Models the inherited WebView API that collides with a native promise reject helper. */
open class FrameworkPromiseView {
    fun loadData(data: String, mimeType: String, encoding: String) {}
}
