package software.globus.lab.kmp

/** UI-thread ownership; releasing a map and removing a handle both invalidate mutations. */
internal class DrawableLifetime(private val mapDisposed: () -> Boolean) {
    private var removed = false
    fun checkOpen() {
        check(!mapDisposed()) { "map_disposed" }
        check(!removed) { "object_removed" }
    }
    fun release(): Boolean {
        if (removed) return false
        removed = true
        return true
    }
}
