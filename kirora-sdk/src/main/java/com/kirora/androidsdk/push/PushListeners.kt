package com.kirora.androidsdk.push

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Listeners and taps that arrived before anyone listened. Owned by `Kirora` rather than the push
 * manager, so they survive `initialize()` being called again and can be added before it.
 */
internal class PushListeners {
    val click = CopyOnWriteArraySet<(Map<String, String>) -> Unit>()
    val foreground = CopyOnWriteArraySet<(Map<String, String>) -> Unit>()
    val pendingClicks = ArrayDeque<Map<String, String>>()
}
