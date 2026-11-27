package eu.kanade.tachiyomi.data.social.matrix.room

import android.app.Application
import android.content.Context

/** [RoomSourceMemo] kept in the app preferences, so a room's source is sent once, not at every launch. */
class PrefsRoomSourceMemo(app: Application) : RoomSourceMemo {

    private val prefs = app.getSharedPreferences("shiro_room_source", Context.MODE_PRIVATE)

    override fun firstTime(roomId: String, stamp: String): Boolean {
        if (prefs.getString(roomId, null) == stamp) return false
        prefs.edit().putString(roomId, stamp).apply()
        return true
    }
}
