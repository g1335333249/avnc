/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.multi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gaurav.avnc.R
import com.gaurav.avnc.session.MultiRemoteSessionManager
import com.gaurav.avnc.viewmodel.MultiVncViewModel
import com.google.android.material.button.MaterialButton

private const val PROFILE_IDS_KEY = "com.gaurav.avnc.multi.profile_ids"

fun createMultiVncIntent(context: Context, profileIds: LongArray): Intent {
    return Intent(context, MultiVncActivity::class.java).apply {
        putExtra(PROFILE_IDS_KEY, profileIds)
    }
}

fun startMultiVncActivity(source: Activity, profileIds: LongArray) {
    source.startActivity(createMultiVncIntent(source, profileIds))
}

class MultiVncActivity : AppCompatActivity() {

    private val viewModel by viewModels<MultiVncViewModel>()
    private lateinit var adapter: SessionAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var syncInputButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi_vnc)

        recyclerView = findViewById(R.id.sessions_rv)
        adapter = SessionAdapter(viewModel)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        syncInputButton = findViewById(R.id.sync_input_btn)
        syncInputButton.isCheckable = true
        syncInputButton.addOnCheckedChangeListener { _, isChecked ->
            adapter.syncInput = isChecked
            adapter.updateSyncInput(recyclerView)
            if (!isChecked)
                viewModel.inputBroadcaster.clearTargets()
        }

        findViewById<MaterialButton>(R.id.stop_all_btn).setOnClickListener {
            viewModel.stopAll()
        }

        viewModel.sessions.observe(this) { adapter.submitList(it) }
        viewModel.frameUpdatedEvent.observe(this) { adapter.renderSession(it) }

        val profileIds = intent.getLongArrayExtra(PROFILE_IDS_KEY)
        if (profileIds == null || profileIds.isEmpty()) {
            Toast.makeText(this, R.string.msg_no_servers_selected, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        viewModel.start(profileIds)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResumed()
        adapter.resumeVisibleFrames(recyclerView)
    }

    override fun onPause() {
        viewModel.onActivityPaused()
        adapter.pauseVisibleFrames(recyclerView)
        super.onPause()
    }

    private class SessionAdapter(private val viewModel: MultiVncViewModel)
        : ListAdapter<MultiRemoteSessionManager.SessionSnapshot, SessionAdapter.ViewHolder>(Differ) {

        private val frameViews = mutableMapOf<Long, MultiFrameView>()
        var syncInput = false

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.multi_vnc_session_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onViewRecycled(holder: ViewHolder) {
            frameViews.remove(holder.sessionId)
            holder.frameView.onPause()
            super.onViewRecycled(holder)
        }

        fun renderSession(sessionId: Long) {
            frameViews[sessionId]?.requestRender()
        }

        fun updateSyncInput(recyclerView: RecyclerView) {
            frameViews.values.forEach { it.syncInput = syncInput }
            forEachVisibleHolder(recyclerView) { it.frameView.syncInput = syncInput }
        }

        fun resumeVisibleFrames(recyclerView: RecyclerView) {
            forEachVisibleHolder(recyclerView) { it.frameView.onResume() }
        }

        fun pauseVisibleFrames(recyclerView: RecyclerView) {
            forEachVisibleHolder(recyclerView) { it.frameView.onPause() }
        }

        private fun forEachVisibleHolder(recyclerView: RecyclerView, action: (ViewHolder) -> Unit) {
            repeat(recyclerView.childCount) {
                val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(it))
                if (holder is ViewHolder)
                    action(holder)
            }
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val frameView: MultiFrameView = view.findViewById(R.id.frame_view)
            private val titleView: TextView = view.findViewById(R.id.title)
            private val stateContainer: View = view.findViewById(R.id.state_container)
            private val stateView: TextView = view.findViewById(R.id.state)
            private val reconnectBtn: MaterialButton = view.findViewById(R.id.reconnect_btn)
            var sessionId = 0L
                private set

            fun bind(snapshot: MultiRemoteSessionManager.SessionSnapshot) {
                sessionId = snapshot.id
                titleView.text = snapshot.profile.name.ifBlank { "${snapshot.profile.host}:${snapshot.profile.port}" }
                stateView.text = stateText(snapshot)
                stateContainer.isVisible = snapshot.state != MultiRemoteSessionManager.State.Connected
                reconnectBtn.isVisible = snapshot.state == MultiRemoteSessionManager.State.Disconnected

                frameView.bind(snapshot.id, snapshot.client, viewModel.inputBroadcaster)
                frameView.syncInput = syncInput
                frameView.setFramebufferSize(snapshot.framebufferWidth, snapshot.framebufferHeight)
                frameViews[snapshot.id] = frameView

                reconnectBtn.setOnClickListener { viewModel.reconnect(snapshot.id) }
                frameView.onResume()
            }

            private fun stateText(snapshot: MultiRemoteSessionManager.SessionSnapshot): String {
                val stateTitle = when (snapshot.state) {
                    MultiRemoteSessionManager.State.Created -> R.string.state_created
                    MultiRemoteSessionManager.State.Connecting -> R.string.state_connecting
                    MultiRemoteSessionManager.State.Connected -> R.string.state_connected
                    MultiRemoteSessionManager.State.Disconnecting -> R.string.state_disconnecting
                    MultiRemoteSessionManager.State.Disconnected -> R.string.state_disconnected
                }.let(itemView.context::getString)

                val errorMessage = snapshot.lastError?.let(::localizedErrorMessage)
                return if (errorMessage.isNullOrBlank()) {
                    stateTitle
                } else {
                    itemView.context.getString(R.string.state_with_error, stateTitle, errorMessage)
                }
            }

            private fun localizedErrorMessage(error: Throwable): String {
                val message = error.message ?: return error.javaClass.simpleName
                return if (message.equals("Connection aborted", ignoreCase = true)) {
                    itemView.context.getString(R.string.msg_connection_aborted)
                } else {
                    message
                }
            }
        }

        object Differ : DiffUtil.ItemCallback<MultiRemoteSessionManager.SessionSnapshot>() {
            override fun areItemsTheSame(
                    old: MultiRemoteSessionManager.SessionSnapshot,
                    new: MultiRemoteSessionManager.SessionSnapshot,
            ) = old.id == new.id

            override fun areContentsTheSame(
                    old: MultiRemoteSessionManager.SessionSnapshot,
                    new: MultiRemoteSessionManager.SessionSnapshot,
            ) = old == new
        }
    }
}
