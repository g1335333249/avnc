/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.viewmodel

import android.app.Application
import android.media.ToneGenerator
import androidx.lifecycle.MutableLiveData
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.session.InputBroadcaster
import com.gaurav.avnc.session.Messenger
import com.gaurav.avnc.session.MultiRemoteSessionManager
import com.gaurav.avnc.util.LiveEvent
import com.gaurav.avnc.util.Tones
import com.gaurav.avnc.util.getKnownHostsFile
import com.gaurav.avnc.util.isCertificateTrusted
import com.gaurav.avnc.vnc.UserCredential
import com.gaurav.avnc.vnc.VncClient
import kotlinx.coroutines.delay
import java.io.File
import java.security.cert.X509Certificate

class MultiVncViewModel(app: Application) : BaseViewModel(app) {

    val sessions = MutableLiveData<List<MultiRemoteSessionManager.SessionSnapshot>>(emptyList())
    val frameUpdatedEvent = LiveEvent<Long>()

    private val sessionManager = MultiRemoteSessionManager(SessionObserver())
    val inputBroadcaster = InputBroadcaster { sessionManager.connectedSnapshots() }

    private val manuallyStoppedSessionIds = mutableSetOf<Long>()
    private val connectedProfileIds = mutableSetOf<Long>()
    private val reconnectingSessionIds = mutableSetOf<Long>()

    @Volatile
    private var activityResumed = false

    override fun onCleared() {
        super.onCleared()
        sessionManager.stopAll()
    }

    fun start(profileIds: LongArray) {
        if (sessions.value?.isNotEmpty() == true)
            return

        launchMain {
            for (id in profileIds) {
                serverProfileDao.getByID(id)?.let { sessionManager.start(it) }
            }
            publishSessions()
        }
    }

    fun stop(sessionId: Long) {
        manuallyStoppedSessionIds.add(sessionId)
        sessionManager.stop(sessionId)
        publishSessions()
    }

    fun reconnect(sessionId: Long) {
        val snapshot = sessionManager.snapshot(sessionId) ?: return
        manuallyStoppedSessionIds.remove(sessionId)
        reconnectingSessionIds.remove(sessionId)

        if (snapshot.state == MultiRemoteSessionManager.State.Disconnected) {
            sessionManager.forgetDisconnected(sessionId)
            sessionManager.start(snapshot.profile)
            publishSessions()
        }
    }

    fun stopAll() {
        manuallyStoppedSessionIds.addAll(sessionManager.snapshots().map { it.id })
        sessionManager.stopAll()
        publishSessions()
    }

    fun onActivityResumed() {
        activityResumed = true
        reconnectDisconnectedSessions()
    }

    fun onActivityPaused() {
        activityResumed = false
    }

    private fun publishSessions() {
        sessions.postValue(sessionManager.snapshots())
    }

    private fun reconnectDisconnectedSessions() {
        sessionManager.snapshots()
                .filter { it.state == MultiRemoteSessionManager.State.Disconnected }
                .filter { canReconnect(it) }
                .forEach { scheduleReconnect(it.id, it.profile, 0L) }
    }

    private fun scheduleReconnect(sessionId: Long, profile: ServerProfile, delayMs: Long = RECONNECT_DELAY_MS) {
        if (!reconnectingSessionIds.add(sessionId))
            return

        launchMain {
            if (delayMs > 0)
                delay(delayMs)

            reconnectingSessionIds.remove(sessionId)
            val snapshot = sessionManager.snapshot(sessionId) ?: return@launchMain
            if (snapshot.state != MultiRemoteSessionManager.State.Disconnected || !canReconnect(snapshot))
                return@launchMain

            sessionManager.forgetDisconnected(sessionId)
            sessionManager.start(profile)
            publishSessions()
        }
    }

    private fun canReconnect(snapshot: MultiRemoteSessionManager.SessionSnapshot): Boolean {
        return activityResumed &&
               snapshot.id !in manuallyStoppedSessionIds &&
               snapshot.profile.ID in connectedProfileIds
    }

    private inner class SessionObserver : MultiRemoteSessionManager.Observer {

        override fun onSessionConnecting(sessionId: Long, profile: ServerProfile) {
            publishSessions()
        }

        override fun onSessionConnected(sessionId: Long, profile: ServerProfile, vncClient: VncClient, messenger: Messenger) {
            if (profile.ID != 0L)
                connectedProfileIds.add(profile.ID)
            publishSessions()
        }

        override fun onSessionDisconnected(sessionId: Long, profile: ServerProfile) {
            publishSessions()
            sessionManager.snapshot(sessionId)?.let {
                if (canReconnect(it))
                    scheduleReconnect(sessionId, profile)
            }
        }

        override fun onSessionConnectionError(sessionId: Long, profile: ServerProfile, error: Throwable) {
            publishSessions()
        }

        override fun onSessionWakeOnLanBroadcastError(sessionId: Long, profile: ServerProfile, error: Throwable) {
            publishSessions()
        }

        override fun getVncPassword(sessionId: Long, profile: ServerProfile): String {
            return profile.password
        }

        override fun getVncCredentials(sessionId: Long, profile: ServerProfile): UserCredential {
            return UserCredential(profile.username, profile.password)
        }

        override fun verifyVncServerCertificate(sessionId: Long, profile: ServerProfile, certificate: X509Certificate): Boolean {
            return isCertificateTrusted(app, certificate)
        }

        override fun onCutTextReceived(sessionId: Long, profile: ServerProfile, text: String) {}

        override fun onFramebufferUpdated(sessionId: Long, profile: ServerProfile) {
            frameUpdatedEvent.fireAsync(sessionId)
        }

        override fun onFramebufferSizeChanged(sessionId: Long, profile: ServerProfile, width: Int, height: Int) {
            publishSessions()
        }

        override fun onPointerMoved(sessionId: Long, profile: ServerProfile, x: Int, y: Int) {
            frameUpdatedEvent.fireAsync(sessionId)
        }

        override fun onBell(sessionId: Long, profile: ServerProfile) {
            if (pref.ui.bell)
                Tones.notify(ToneGenerator.TONE_PROP_BEEP)
        }

        override fun getKnownSshHostsFile(sessionId: Long, profile: ServerProfile): File {
            return getKnownHostsFile(app)
        }

        override fun confirmSshHostKeyWithUser(sessionId: Long, profile: ServerProfile, message: String, isNewHost: Boolean): Boolean {
            return false
        }

        override fun getSshPassword(sessionId: Long, profile: ServerProfile): String {
            return profile.sshPassword
        }

        override fun getSshKeyPassword(sessionId: Long, profile: ServerProfile): String {
            return profile.sshPassword
        }
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 1000L
    }
}
