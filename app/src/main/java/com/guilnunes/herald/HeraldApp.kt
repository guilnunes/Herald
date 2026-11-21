package com.guilnunes.herald

import android.app.Application
import com.guilnunes.herald.data.Prefs
import com.guilnunes.herald.data.Repository
import com.guilnunes.herald.player.Engine
import com.guilnunes.herald.player.PlaybackService

class HeraldApp : Application() {
    lateinit var repo: Repository
        private set
    lateinit var engine: Engine
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this, Prefs(this))
        engine = Engine(this, repo)
        engine.onPlaybackStarted = { runCatching { PlaybackService.start(this) } }
    }
}
