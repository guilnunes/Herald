package com.guilnunes.herald.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.guilnunes.herald.HeraldApp
import com.guilnunes.herald.R
import com.guilnunes.herald.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps reading alive with the screen off and exposes controls on the lock screen,
 * notification shade, headphones and Bluetooth through a media session.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSessionCompat
    private val engine get() = (application as HeraldApp).engine
    private var foreground = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) engine.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "Herald").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = engine.play()
                override fun onPause() = engine.pause()
                override fun onStop() = stopAll()
                override fun onSkipToNext() {
                    if (engine.state.value.gate != null) engine.decide(false) else engine.nextParagraph()
                }
                override fun onSkipToPrevious() = engine.previousParagraph()
                override fun onFastForward() = engine.nextSection()
                override fun onRewind() = engine.previousSection()
                override fun onCustomAction(action: String?, extras: android.os.Bundle?) = handleAction(action)
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        startInForeground(buildNotification(engine.state.value))

        scope.launch {
            engine.state
                .map { Snapshot(it.status, it.script?.title, it.crumbs.size, it.sectionTitle, it.percent, it.gate) }
                .distinctUntilChanged()
                .collect { snap ->
                    val st = engine.state.value
                    updateSession(st)
                    if (st.script == null && st.status == Status.IDLE) { stopAll(); return@collect }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(st))
                    if (snap.status == Status.PLAYING && !foreground) startInForeground(buildNotification(st))
                }
        }
    }

    private data class Snapshot(val status: Status, val title: String?, val depth: Int, val section: String?, val percent: Int, val gate: Gate?)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService call must be answered with startForeground.
        startInForeground(buildNotification(engine.state.value))
        intent?.action?.let { handleAction(it) }
        return START_NOT_STICKY
    }

    private fun handleAction(action: String?) {
        when (action) {
            ACTION_TOGGLE -> engine.togglePlay()
            ACTION_NEXT -> engine.nextParagraph()
            ACTION_PREV -> engine.previousParagraph()
            ACTION_YES -> engine.decide(true)
            ACTION_NO -> engine.decide(false)
            ACTION_NEXT_SECTION -> engine.nextSection()
            ACTION_CLOSE -> stopAll()
        }
    }

    private var stopping = false

    private fun stopAll() {
        if (stopping) return
        stopping = true
        engine.close()
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
            foreground = true
        }
    }

    private fun updateSession(st: PlayerState) {
        val title = st.script?.title ?: "Herald"
        val subtitle = buildString {
            if (st.gate != null) append(st.gate.prompt)
            else {
                append(st.sectionTitle ?: "")
                if (isNotEmpty()) append(" · ")
                append("${st.percent}%")
            }
        }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, st.crumbs.dropLast(1).joinToString(" › ") { it.title }.ifEmpty { "Herald" })
                .build(),
        )
        val playing = st.status == Status.PLAYING
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_STOP
        val builder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                if (playing) 1f else 0f,
            )
        // Android 13+ builds lock-screen controls from these custom actions.
        if (st.gate != null) {
            builder.addCustomAction(ACTION_YES, st.gate.yesLabel, R.drawable.ic_check)
            builder.addCustomAction(ACTION_NO, st.gate.noLabel, R.drawable.ic_close)
        } else {
            builder.addCustomAction(ACTION_NEXT_SECTION, "Next section", R.drawable.ic_next_section)
            builder.addCustomAction(ACTION_CLOSE, "Stop", R.drawable.ic_close)
        }
        session.setPlaybackState(builder.build())
    }

    private fun buildNotification(st: PlayerState): Notification {
        val playing = st.status == Status.PLAYING
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_herald)
            .setContentTitle(st.script?.title ?: "Herald")
            .setContentText(st.gate?.prompt ?: listOfNotNull(st.sectionTitle, "${st.percent}%").joinToString(" · "))
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_CLOSE))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(playing)
        if (st.gate != null) {
            b.addAction(R.drawable.ic_check, st.gate.yesLabel, serviceIntent(ACTION_YES))
            b.addAction(R.drawable.ic_close, st.gate.noLabel, serviceIntent(ACTION_NO))
            b.setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
        } else {
            b.addAction(R.drawable.ic_prev, "Back", serviceIntent(ACTION_PREV))
            b.addAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Pause" else "Play", serviceIntent(ACTION_TOGGLE))
            b.addAction(R.drawable.ic_next, "Forward", serviceIntent(ACTION_NEXT))
            b.addAction(R.drawable.ic_close, "Stop", serviceIntent(ACTION_CLOSE))
            b.setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true).setCancelButtonIntent(serviceIntent(ACTION_CLOSE)))
        }
        return b.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Swiping Herald away from recent apps means "I'm done": stop reading and remove the controls. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopAll()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(noisy) }
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 7
        const val ACTION_TOGGLE = "herald.toggle"
        const val ACTION_NEXT = "herald.next"
        const val ACTION_PREV = "herald.prev"
        const val ACTION_YES = "herald.yes"
        const val ACTION_NO = "herald.no"
        const val ACTION_NEXT_SECTION = "herald.next_section"
        const val ACTION_CLOSE = "herald.close"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        }
    }
}
