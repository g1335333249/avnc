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
import androidx.recyclerview.widget.GridLayoutManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi_vnc)

        recyclerView = findViewById(R.id.sessions_rv)
        adapter = SessionAdapter(viewModel)
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        recyclerView.adapter = adapter

        findViewById<MaterialButton>(R.id.stop_all_btn).setOnClickListener {
            viewModel.stopAll()
        }

        viewModel.sessions.observe(this) { adapter.submitList(it) }
        viewModel.frameUpdatedEvent.observe(this) { adapter.renderSession(it) }

        val profileIds = intent.getLongArrayExtra(PROFILE_IDS_KEY)
        if (profileIds == null || profileIds.isEmpty()) {
            Toast.makeText(this, "No servers selected", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        viewModel.start(profileIds)
    }

    override fun onResume() {
        super.onResume()
        adapter.resumeVisibleFrames(recyclerView)
    }

    override fun onPause() {
        adapter.pauseVisibleFrames(recyclerView)
        super.onPause()
    }

    private class SessionAdapter(private val viewModel: MultiVncViewModel)
        : ListAdapter<MultiRemoteSessionManager.SessionSnapshot, SessionAdapter.ViewHolder>(Differ) {

        private val frameViews = mutableMapOf<Long, MultiFrameView>()

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
            private val stateView: TextView = view.findViewById(R.id.state)
            private val stopBtn: MaterialButton = view.findViewById(R.id.stop_btn)
            var sessionId = 0L
                private set

            fun bind(snapshot: MultiRemoteSessionManager.SessionSnapshot) {
                sessionId = snapshot.id
                titleView.text = snapshot.profile.name.ifBlank { "${snapshot.profile.host}:${snapshot.profile.port}" }
                stateView.text = snapshot.lastError?.message ?: snapshot.state.toString()
                stateView.isVisible = snapshot.state != MultiRemoteSessionManager.State.Connected

                frameView.bind(snapshot.id, snapshot.client, viewModel.inputBroadcaster)
                frameView.setFramebufferSize(snapshot.framebufferWidth, snapshot.framebufferHeight)
                frameViews[snapshot.id] = frameView

                stopBtn.setOnClickListener { viewModel.stop(snapshot.id) }
                frameView.onResume()
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
