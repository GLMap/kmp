package globus.glmap

fun main() {
    var disposed = false
    val handle = DrawableLifetime { disposed }
    handle.checkOpen()
    check(handle.release())
    check(!handle.release())
    repeat(5) { check(runCatching { handle.checkOpen() }.exceptionOrNull()?.message == "object_removed") }
    val pending = DrawableLifetime { disposed }
    disposed = true
    check(runCatching { pending.checkOpen() }.exceptionOrNull()?.message == "map_disposed")
    check(pending.release()); check(!pending.release())
    println("PASS drawable lifetime: live, removed, disposed, idempotent cleanup")
}
