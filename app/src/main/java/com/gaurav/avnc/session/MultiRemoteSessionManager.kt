/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.session

import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.viewmodel.service.SshClient
import com.gaurav.avnc.vnc.UserCredential
import com.gaurav.avnc.vnc.VncClient
import java.io.File
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns multiple independent [RemoteSession] instances.
 *
 * AVNC's existing viewer stack is intentionally session-centric: one [RemoteSession]
 * owns one [VncClient], one [Messenger], and their network threads. This manager keeps
 * that isolation and adds a small routing layer so a future multi-viewer UI can observe
 * and control several sessions without changing the single-session code path.
 */
class MultiRemoteSessionManager(private val observer: Observer) {

    interface Observer {
        fun onSessionConnecting(sessionId: Long, profile: ServerProfile)
        fun onSessionConnected(sessionId: Long, profile: ServerProfile, vncClient: VncClient, messenger: Messenger)
        fun onSessionDisconnected(sessionId: Long, profile: ServerProfile)
        fun onSessionConnectionError(sessionId: Long, profile: ServerProfile, error: Throwable)
        fun onSessionWakeOnLanBroadcastError(sessionId: Long, profile: ServerProfile, error: Throwable)

        fun getVncPassword(sessionId: Long, profile: ServerProfile): String
        fun getVncCredentials(sessionId: Long, profile: ServerProfile): UserCredential
        fun verifyVncServerCertificate(sessionId: Long, profile: ServerProfile, certificate: X509Certificate): Boolean
        fun onCutTextReceived(sessionId: Long, profile: ServerProfile, text: String)
        fun onFramebufferUpdated(sessionId: Long, profile: ServerProfile)
        fun onFramebufferSizeChanged(sessionId: Long, profile: ServerProfile, width: Int, height: Int)
        fun onPointerMoved(sessionId: Long, profile: ServerProfile, x: Int, y: Int)
        fun onBell(sessionId: Long, profile: ServerProfile)

        fun getKnownSshHostsFile(sessionId: Long, profile: ServerProfile): File
        fun confirmSshHostKeyWithUser(sessionId: Long, profile: ServerProfile, message: String, isNewHost: Boolean): Boolean
        fun getSshPassword(sessionId: Long, profile: ServerProfile): String
        fun getSshKeyPassword(sessionId: Long, profile: ServerProfile): String
    }

    enum class State {
        Created,
        Connecting,
        Connected,
        Disconnecting,
        Disconnected;
    }

    data class SessionSnapshot(
            val id: Long,
            val profile: ServerProfile,
            val state: State,
            val client: VncClient?,
            val messenger: Messenger?,
            val framebufferWidth: Int,
            val framebufferHeight: Int,
            val lastError: Throwable?,
    ) {
        val connected get() = state == State.Connected && client?.connected == true && messenger != null
    }

    private data class SessionEntry(
            val id: Long,
            val profile: ServerProfile,
            val session: RemoteSession,
    ) {
        @Volatile var state = State.Created
        @Volatile var client: VncClient? = null
        @Volatile var messenger: Messenger? = null
        @Volatile var framebufferWidth = 0
        @Volatile var framebufferHeight = 0
        @Volatile var lastError: Throwable? = null

        fun snapshot() = SessionSnapshot(
                id = id,
                profile = profile,
                state = state,
                client = client,
                messenger = messenger,
                framebufferWidth = framebufferWidth,
                framebufferHeight = framebufferHeight,
                lastError = lastError,
        )
    }

    private val sessionIds = AtomicLong(0)
    private val sessions = ConcurrentHashMap<Long, SessionEntry>()

    fun start(profile: ServerProfile): Long {
        val id = sessionIds.incrementAndGet()
        val sessionProfile = profile.copy()
        val session = RemoteSession(SessionObserver(id, sessionProfile))
        val entry = SessionEntry(id, sessionProfile, session)
        sessions[id] = entry
        session.start(sessionProfile)
        return id
    }

    fun stop(sessionId: Long) {
        sessions[sessionId]?.let {
            it.state = State.Disconnecting
            it.session.stop()
        }
    }

    fun stopAll() {
        sessions.keys.forEach { stop(it) }
    }

    fun forgetDisconnected(sessionId: Long): Boolean {
        val entry = sessions[sessionId] ?: return false
        if (entry.state != State.Disconnected)
            return false
        return sessions.remove(sessionId, entry)
    }

    fun snapshot(sessionId: Long): SessionSnapshot? = sessions[sessionId]?.snapshot()

    fun snapshots(): List<SessionSnapshot> {
        return sessions.values
                .map { it.snapshot() }
                .sortedBy { it.id }
    }

    fun connectedSnapshots(): List<SessionSnapshot> {
        return snapshots().filter { it.connected }
    }

    private fun entry(sessionId: Long): SessionEntry {
        return checkNotNull(sessions[sessionId]) { "Unknown session: $sessionId" }
    }

    private inner class SessionObserver(
            private val sessionId: Long,
            private val profile: ServerProfile,
    ) : RemoteSession.Observer {

        override fun onConnecting() {
            entry(sessionId).state = State.Connecting
            observer.onSessionConnecting(sessionId, profile)
        }

        override fun onConnected(vncClient: VncClient, messenger: Messenger) {
            entry(sessionId).let {
                it.client = vncClient
                it.messenger = messenger
                it.lastError = null
                it.state = State.Connected
            }
            observer.onSessionConnected(sessionId, profile, vncClient, messenger)
        }

        override fun onDisconnected() {
            entry(sessionId).let {
                it.client = null
                it.messenger = null
                it.state = State.Disconnected
            }
            observer.onSessionDisconnected(sessionId, profile)
        }

        override fun onConnectionError(error: Throwable) {
            entry(sessionId).lastError = error
            observer.onSessionConnectionError(sessionId, profile, error)
        }

        override fun onWakeOnLanBroadcastError(e: Throwable) {
            observer.onSessionWakeOnLanBroadcastError(sessionId, profile, e)
        }

        override fun getVncPassword(): String {
            return observer.getVncPassword(sessionId, profile)
        }

        override fun getVncCredentials(): UserCredential {
            return observer.getVncCredentials(sessionId, profile)
        }

        override fun verifyVncServerCertificate(certificate: X509Certificate): Boolean {
            return observer.verifyVncServerCertificate(sessionId, profile, certificate)
        }

        override fun onCutTextReceived(text: String) {
            observer.onCutTextReceived(sessionId, profile, text)
        }

        override fun onFramebufferUpdated() {
            observer.onFramebufferUpdated(sessionId, profile)
        }

        override fun onFramebufferSizeChanged(width: Int, height: Int) {
            entry(sessionId).let {
                it.framebufferWidth = width
                it.framebufferHeight = height
            }
            observer.onFramebufferSizeChanged(sessionId, profile, width, height)
        }

        override fun onPointerMoved(x: Int, y: Int) {
            observer.onPointerMoved(sessionId, profile, x, y)
        }

        override fun onBell() {
            observer.onBell(sessionId, profile)
        }

        override fun getKnownSshHostsFile(): File {
            return observer.getKnownSshHostsFile(sessionId, profile)
        }

        override fun confirmSshHostKeyWithUser(message: String, isNewHost: Boolean): Boolean {
            return observer.confirmSshHostKeyWithUser(sessionId, profile, message, isNewHost)
        }

        override fun getSshPassword(): String {
            return observer.getSshPassword(sessionId, profile)
        }

        override fun getSshKeyPassword(): String {
            return observer.getSshKeyPassword(sessionId, profile)
        }
    }
}
