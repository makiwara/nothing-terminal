// Vendored from nothing-to-say@8488823 (voice/RecordingState.kt). Faithful copy — re-sync, don't
// diverge. Cockpit-specific behaviour lives in the sink and the native surface, not here.
package com.humanemagica.nothing.terminal.voice

import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide flag that voice capture is active. Read by the notification path to suppress
 *  posts during recording (the heads-up sound would bleed into the OGG, and a reflex tap on the
 *  heads-up would pause the activity and cancel the gesture). */
object RecordingState {
    val active = MutableStateFlow(false)
}
