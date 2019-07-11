/*
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.ui

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

import org.openhab.habdroid.R
import org.openhab.habdroid.core.OpenHabApplication
import org.openhab.habdroid.util.Constants
import org.openhab.habdroid.util.Util
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.isScreenLockKioskMode
import org.openhab.habdroid.util.isScreenLockWholeApp
import kotlin.coroutines.CoroutineContext

abstract class AbstractBaseActivity : AppCompatActivity(), CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + job
    protected open val forceNonFullscreen = false
    private var isLocked = true

    // If we are 4.4 we can use fullscreen mode and Daydream features
    protected val isFullscreenEnabled: Boolean
        get() = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            false
        } else getPrefs().getBoolean(Constants.PREFERENCE_FULLSCREEN, false)

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Util.getActivityThemeId(this))
        checkFullscreen()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val typedValue = TypedValue()
            theme.resolveAttribute(R.attr.colorPrimary, typedValue, true)
            setTaskDescription(ActivityManager.TaskDescription(
                getString(R.string.app_name),
                BitmapFactory.decodeResource(resources, R.mipmap.icon),
                typedValue.data))
        }

        super.onCreate(savedInstanceState)
    }

    @CallSuper
    override fun onStart() {
        promptForDevicePasswordIfRequired()
        super.onStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onResume() {
        super.onResume()
        checkFullscreen()
    }

    @JvmOverloads
    fun checkFullscreen(isEnabled: Boolean = isFullscreenEnabled) {
        var uiOptions = window.decorView.systemUiVisibility
        val flags = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN)
        uiOptions = if (isEnabled && !forceNonFullscreen) {
            uiOptions or flags
        } else {
            uiOptions and flags.inv()
        }
        window.decorView.systemUiVisibility = uiOptions
    }

    @TargetApi(21)
    fun promptForDevicePassword() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        isLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            km.isDeviceSecure else km.isKeyguardSecure
        if (isLocked) {
            val intent = km.createConfirmDeviceCredentialIntent(null,
                getString(R.string.screen_lock_unlock_screen_description))
            startActivityForResult(intent, SCREEN_LOCK_REQUEST_CODE)
        }
    }

    private fun handleScreenLockResponse(locked: Boolean) {
        isLocked = locked
        if (locked) {
            promptForDevicePasswordIfRequired()
        } else {
            OpenHabApplication.lastAuthenticationTimestamp = SystemClock.elapsedRealtime()
        }
    }

    private fun promptForDevicePasswordIfRequired() {
        val screenLockEnabled = (getPrefs().isScreenLockKioskMode(this) && this !is MainActivity) ||
            getPrefs().isScreenLockWholeApp(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            screenLockEnabled &&
            timestampNeedsReauth(OpenHabApplication.lastAuthenticationTimestamp)
        ) {
            promptForDevicePassword()
        } else {
            isLocked = false
        }
    }

    private fun timestampNeedsReauth(ts: Long): Boolean {
        return ts == 0L || SystemClock.elapsedRealtime() - ts > AUTHENTICATION_VALIDITY_PERIOD
    }

    @CallSuper
    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            SCREEN_LOCK_REQUEST_CODE -> handleScreenLockResponse(resultCode != RESULT_OK)
        }
    }

    companion object {
        private const val AUTHENTICATION_VALIDITY_PERIOD = 2 * 60 * 1000L
        private const val SCREEN_LOCK_REQUEST_CODE = 2001
    }
}
