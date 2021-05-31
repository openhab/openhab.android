/*
 * Copyright (c) 2010-2021 Contributors to the openHAB project
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

import android.Manifest
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.util.Base64
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.location.LocationManagerCompat
import androidx.core.text.inSpans
import androidx.core.view.GravityCompat
import androidx.core.view.forEach
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.ContentLoadingProgressBar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import java.nio.charset.Charset
import java.util.concurrent.CancellationException
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import org.openhab.habdroid.BuildConfig
import org.openhab.habdroid.R
import org.openhab.habdroid.background.BackgroundTasksManager
import org.openhab.habdroid.background.EventListenerService
import org.openhab.habdroid.background.NotificationUpdateObserver
import org.openhab.habdroid.core.CloudMessagingHelper
import org.openhab.habdroid.core.UpdateBroadcastReceiver
import org.openhab.habdroid.core.connection.CloudConnection
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.core.connection.ConnectionFactory
import org.openhab.habdroid.core.connection.ConnectionNotInitializedException
import org.openhab.habdroid.core.connection.DemoConnection
import org.openhab.habdroid.core.connection.NetworkNotAvailableException
import org.openhab.habdroid.core.connection.NoUrlInformationException
import org.openhab.habdroid.model.LinkedPage
import org.openhab.habdroid.model.ServerConfiguration
import org.openhab.habdroid.model.ServerProperties
import org.openhab.habdroid.model.Sitemap
import org.openhab.habdroid.model.WebViewUi
import org.openhab.habdroid.model.sortedWithDefaultName
import org.openhab.habdroid.model.toTagData
import org.openhab.habdroid.ui.activity.ContentController
import org.openhab.habdroid.ui.homescreenwidget.VoiceWidget
import org.openhab.habdroid.ui.homescreenwidget.VoiceWidgetWithIcon
import org.openhab.habdroid.ui.preference.toItemUpdatePrefValue
import org.openhab.habdroid.ui.widget.LockableDrawerLayout
import org.openhab.habdroid.util.AsyncServiceResolver
import org.openhab.habdroid.util.CrashReportingHelper
import org.openhab.habdroid.util.HttpClient
import org.openhab.habdroid.util.ImageConversionPolicy
import org.openhab.habdroid.util.PrefKeys
import org.openhab.habdroid.util.ScreenLockMode
import org.openhab.habdroid.util.Util
import org.openhab.habdroid.util.addToPrefs
import org.openhab.habdroid.util.areSitemapsShownInDrawer
import org.openhab.habdroid.util.determineDataUsagePolicy
import org.openhab.habdroid.util.getActiveServerId
import org.openhab.habdroid.util.getConfiguredServerIds
import org.openhab.habdroid.util.getCurrentWifiSsid
import org.openhab.habdroid.util.getDefaultSitemap
import org.openhab.habdroid.util.getGroupItems
import org.openhab.habdroid.util.getHumanReadableErrorMessage
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.getPrimaryServerId
import org.openhab.habdroid.util.getRemoteUrl
import org.openhab.habdroid.util.getSecretPrefs
import org.openhab.habdroid.util.getStringOrNull
import org.openhab.habdroid.util.hasPermissions
import org.openhab.habdroid.util.isDebugModeEnabled
import org.openhab.habdroid.util.isEventListenerEnabled
import org.openhab.habdroid.util.isScreenTimerDisabled
import org.openhab.habdroid.util.openInAppStore
import org.openhab.habdroid.util.putActiveServerId
import org.openhab.habdroid.util.updateDefaultSitemap

class MainActivity : AbstractBaseActivity(), ConnectionFactory.UpdateListener {
    private lateinit var prefs: SharedPreferences
    private var serviceResolveJob: Job? = null
    private lateinit var drawerLayout: LockableDrawerLayout
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var drawerMenu: Menu
    private lateinit var drawerModeSelectorContainer: View
    private lateinit var drawerModeToggle: ImageView
    private lateinit var drawerServerNameView: TextView
    private var drawerIconTintList: ColorStateList? = null
    lateinit var viewPool: RecyclerView.RecycledViewPool
        private set
    private var progressBar: ContentLoadingProgressBar? = null
    private var sitemapSelectionDialog: AlertDialog? = null
    var connection: Connection? = null
        private set

    private var pendingAction: PendingAction? = null
    private lateinit var controller: ContentController
    var serverProperties: ServerProperties? = null
        private set
    private var propsUpdateHandle: ServerProperties.Companion.UpdateHandle? = null
    private var retryJob: Job? = null
    private var isStarted: Boolean = false
    private var shortcutManager: ShortcutManager? = null
    private val backgroundTasksManager = BackgroundTasksManager()
    private var inServerSelectionMode = false
    private var wifiSsidDuringLastOnStart: String? = null

    private val preferenceActivityCallback =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            CrashReportingHelper.d(TAG, "preferenceActivityCallback: $result")
            val data = result.data ?: return@registerForActivityResult
            if (data.getBooleanExtra(PreferencesActivity.RESULT_EXTRA_SITEMAP_CLEARED, false)) {
                updateSitemapDrawerEntries()
                executeOrStoreAction(PendingAction.ChooseSitemap())
            }
            if (data.getBooleanExtra(PreferencesActivity.RESULT_EXTRA_SITEMAP_DRAWER_CHANGED, false)) {
                updateSitemapDrawerEntries()
            }
            if (data.getBooleanExtra(PreferencesActivity.RESULT_EXTRA_THEME_CHANGED, false)) {
                recreate()
            }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        CrashReportingHelper.d(TAG, "onNewIntent()")
        processIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReportingHelper.d(TAG, "onCreate()")

        prefs = getPrefs()

        // Disable screen timeout if set in preferences
        if (prefs.isScreenTimerDisabled()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        super.onCreate(savedInstanceState)

        val controllerClassName = resources.getString(R.string.controller_class)
        try {
            val controllerClass = Class.forName(controllerClassName)
            val constructor = controllerClass.getConstructor(MainActivity::class.java)
            controller = constructor.newInstance(this) as ContentController
        } catch (e: Exception) {
            Log.wtf(TAG, "Could not instantiate activity controller class '$controllerClassName'")
            throw RuntimeException(e)
        }

        setContentView(R.layout.activity_main)
        // inflate the controller dependent content view
        controller.inflateViews(findViewById(R.id.content_stub))

        setupToolbar()
        setupDrawer()

        viewPool = RecyclerView.RecycledViewPool()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            shortcutManager = getSystemService(ShortcutManager::class.java)
        }

        // Check if we have openHAB page url in saved instance state?
        if (savedInstanceState != null) {
            serverProperties = savedInstanceState.getParcelable(STATE_KEY_SERVER_PROPERTIES)
            val lastConnectionHash = savedInstanceState.getInt(STATE_KEY_CONNECTION_HASH)
            if (lastConnectionHash != -1) {
                val c = ConnectionFactory.activeUsableConnection?.connection
                if (c != null && c.hashCode() == lastConnectionHash) {
                    connection = c
                }
            }

            controller.onRestoreInstanceState(savedInstanceState)
            val lastControllerClass = savedInstanceState.getString(STATE_KEY_CONTROLLER_NAME)
            if (controller.javaClass.canonicalName != lastControllerClass) {
                // Our controller type changed, so we need to make the new controller aware of the
                // page hierarchy. If the controller didn't change, the hierarchy will be restored
                // via the fragment state restoration.
                controller.recreateFragmentState()
            }
            if (savedInstanceState.getBoolean(STATE_KEY_SITEMAP_SELECTION_SHOWN)) {
                showSitemapSelectionDialog()
            }

            updateSitemapDrawerEntries()
        }

        processIntent(intent)

        if (prefs.getBoolean(PrefKeys.FIRST_START, true) ||
            prefs.getBoolean(PrefKeys.RECENTLY_RESTORED, false)
        ) {
            NotificationUpdateObserver.createNotificationChannels(this)
            Log.d(TAG, "Start intro")
            val intent = Intent(this, IntroActivity::class.java)
            startActivity(intent)
        }
        UpdateBroadcastReceiver.updateComparableVersion(prefs.edit())

        val isSpeechRecognizerAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        GlobalScope.launch {
            showPushNotificationWarningIfNeeded()
            manageVoiceRecognitionShortcut(isSpeechRecognizerAvailable)
            setVoiceWidgetComponentEnabledSetting(VoiceWidget::class.java, isSpeechRecognizerAvailable)
            setVoiceWidgetComponentEnabledSetting(VoiceWidgetWithIcon::class.java, isSpeechRecognizerAvailable)
        }

        EventListenerService.startOrStopService(this)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        CrashReportingHelper.d(TAG, "onPostCreate()")
        super.onPostCreate(savedInstanceState)
        // Sync the toggle state after onRestoreInstanceState has occurred.
        drawerToggle.syncState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        CrashReportingHelper.d(TAG, "onConfigurationChanged()")
        super.onConfigurationChanged(newConfig)
        drawerToggle.onConfigurationChanged(newConfig)
    }

    override fun onStart() {
        CrashReportingHelper.d(TAG, "onStart()")
        super.onStart()
        isStarted = true

        ConnectionFactory.addListener(this)

        updateDrawerServerEntries()
        onActiveConnectionChanged()

        if (connection != null && serverProperties == null) {
            controller.clearServerCommunicationFailure()
            queryServerProperties()
        }

        val currentWifiSsid = getCurrentWifiSsid()
        val switchToServer = determineServerIdToSwitchToBasedOnWifi(currentWifiSsid, wifiSsidDuringLastOnStart)
        wifiSsidDuringLastOnStart = currentWifiSsid
        if (pendingAction == null && switchToServer != -1) {
            switchServerBasedOnWifi(switchToServer)
        }
        handlePendingAction()
    }

    public override fun onStop() {
        CrashReportingHelper.d(TAG, "onStop()")
        isStarted = false
        super.onStop()
        ConnectionFactory.removeListener(this)
        serviceResolveJob?.cancel()
        serviceResolveJob = null
        if (sitemapSelectionDialog?.isShowing == true) {
            sitemapSelectionDialog?.dismiss()
        }
        propsUpdateHandle?.cancel()
    }

    override fun onResume() {
        CrashReportingHelper.d(TAG, "onResume()")
        super.onResume()

        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter != null) {
            val intent = Intent(this, javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(this, 0, intent, 0)
            nfcAdapter.enableForegroundDispatch(this, pi, null, null)
        }

        updateTitle()
        showMissingPermissionsWarningIfNeeded()

        val intentFilter = BackgroundTasksManager.getIntentFilterForForeground(this)
        if (intentFilter.countActions() != 0 && !prefs.isEventListenerEnabled()) {
            registerReceiver(backgroundTasksManager, intentFilter)
        }

        showDataSaverHintSnackbarIfNeeded()
    }

    override fun onPause() {
        CrashReportingHelper.d(TAG, "onPause()")
        super.onPause()
        retryJob?.cancel(CancellationException("onPause() was called"))

        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (e: IllegalStateException) {
            // See #1776
        }

        try {
            unregisterReceiver(backgroundTasksManager)
        } catch (e: IllegalArgumentException) {
            // Receiver isn't registered
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        CrashReportingHelper.d(TAG, "onCreateOptionsMenu()")
        val inflater = menuInflater
        inflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        CrashReportingHelper.d(TAG, "onPrepareOptionsMenu()")
        menu.findItem(R.id.mainmenu_voice_recognition).isVisible = connection != null
        menu.findItem(R.id.mainmenu_crash).isVisible = BuildConfig.DEBUG
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        CrashReportingHelper.d(TAG, "onOptionsItemSelected()")
        // Handle back navigation arrow
        if (item.itemId == android.R.id.home && controller.canGoBack()) {
            controller.goBack()
            return true
        }

        // Handle hamburger menu
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }

        // Handle menu items
        return when (item.itemId) {
            R.id.mainmenu_voice_recognition -> {
                launchVoiceRecognition()
                true
            }
            R.id.mainmenu_crash -> {
                throw Exception("Crash menu item pressed")
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        CrashReportingHelper.d(TAG, "onSaveInstanceState()")
        isStarted = false
        with(savedInstanceState) {
            putParcelable(STATE_KEY_SERVER_PROPERTIES, serverProperties)
            putBoolean(STATE_KEY_SITEMAP_SELECTION_SHOWN, sitemapSelectionDialog?.isShowing == true)
            putString(STATE_KEY_CONTROLLER_NAME, controller.javaClass.canonicalName)
            putInt(STATE_KEY_CONNECTION_HASH, connection?.hashCode() ?: -1)
            controller.onSaveInstanceState(this)
        }
        super.onSaveInstanceState(savedInstanceState)
    }

    override fun onBackPressed() {
        CrashReportingHelper.d(TAG, "onBackPressed()")
        when {
            drawerLayout.isDrawerOpen(findViewById<NavigationView>(R.id.left_drawer)) -> drawerLayout.closeDrawers()
            controller.canGoBack() -> controller.goBack()
            isFullscreenEnabled -> when {
                lastSnackbar?.isShown != true ->
                    showSnackbar(
                        SNACKBAR_TAG_PRESS_AGAIN_EXIT,
                        R.string.press_back_to_exit
                    )
                lastSnackbar?.view?.tag?.toString() == SNACKBAR_TAG_PRESS_AGAIN_EXIT -> super.onBackPressed()
                else -> showSnackbar(
                    SNACKBAR_TAG_PRESS_AGAIN_EXIT,
                    R.string.press_back_to_exit
                )
            }
            else -> super.onBackPressed()
        }
    }

    override fun onActiveConnectionChanged() {
        CrashReportingHelper.d(TAG, "onActiveConnectionChanged()")
        val result = ConnectionFactory.activeUsableConnection
        val newConnection = result?.connection
        val failureReason = result?.failureReason

        if (ConnectionFactory.activeCloudConnection?.connection != null) {
            manageNotificationShortcut(true)
        }

        if (newConnection != null && newConnection === connection) {
            updateDrawerItemVisibility()
            return
        }

        retryJob?.cancel(CancellationException("onAvailableConnectionChanged() was called"))

        connection = newConnection
        hideSnackbar(SNACKBAR_TAG_CONNECTION_ESTABLISHED)
        hideSnackbar(SNACKBAR_TAG_SSE_ERROR)
        hideSnackbar(SNACKBAR_TAG_DEMO_MODE_ACTIVE)
        serverProperties = null
        handlePendingAction()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        when {
            newConnection != null -> {
                handleConnectionChange()
                controller.updateConnection(newConnection, null, 0)
            }
            failureReason is NoUrlInformationException -> {
                // Attempt resolving only if we're connected locally and
                // no local connection is configured yet
                if (failureReason.wouldHaveUsedLocalConnection() && ConnectionFactory.activeLocalConnection == null) {
                    if (serviceResolveJob == null) {
                        val resolver = AsyncServiceResolver(
                            this,
                            AsyncServiceResolver.OPENHAB_SERVICE_TYPE,
                            this
                        )
                        serviceResolveJob = launch {
                            handleServiceResolveResult(resolver.resolve())
                            serviceResolveJob = null
                        }
                        controller.updateConnection(null,
                            getString(R.string.resolving_openhab),
                            R.drawable.ic_home_search_outline_grey_340dp)
                    }
                } else {
                    val officialServer = !failureReason.wouldHaveUsedLocalConnection() &&
                        prefs.getRemoteUrl().matches("^(home.)?myopenhab.org$".toRegex())
                    controller.indicateMissingConfiguration(false, officialServer)
                }
            }
            failureReason is NetworkNotAvailableException && !wifiManager.isWifiEnabled -> {
                controller.indicateNoNetwork(getString(R.string.error_wifi_not_available), true)
            }
            failureReason is ConnectionNotInitializedException -> {
                controller.updateConnection(null, null, 0)
            }
            else -> {
                controller.indicateNoNetwork(getString(R.string.error_network_not_available), false)
                scheduleRetry {
                    ConnectionFactory.restartNetworkCheck()
                    recreate()
                }
            }
        }

        viewPool.clear()
        updateSitemapDrawerEntries()
        updateDrawerItemVisibility()
        updateDrawerServerEntries()
        invalidateOptionsMenu()
        updateTitle()
    }

    fun scheduleRetry(runAfterDelay: () -> Unit) {
        retryJob?.cancel(CancellationException("scheduleRetry() was called"))
        retryJob = CoroutineScope(Dispatchers.Main + Job()).launch {
            delay(30 * 1000)
            Log.d(TAG, "runAfterDelay()")
            runAfterDelay()
        }
    }

    override fun onPrimaryConnectionChanged() {
        // no-op
    }

    override fun onActiveCloudConnectionChanged(connection: CloudConnection?) {
        CrashReportingHelper.d(TAG, "onActiveCloudConnectionChanged()")
        updateDrawerItemVisibility()
        handlePendingAction()
    }

    override fun onPrimaryCloudConnectionChanged(connection: CloudConnection?) {
        CrashReportingHelper.d(TAG, "onPrimaryCloudConnectionChanged()")
        handlePendingAction()
        GlobalScope.launch {
            showPushNotificationWarningIfNeeded()
        }
    }

    /**
     * Determines whether to switch the server based on the wifi ssid. Returns -1 if no switch is required,
     * the server id otherwise.
     */
    private fun determineServerIdToSwitchToBasedOnWifi(ssid: String?, prevSsid: String?): Int {
        val anyServerHasSetWifi = prefs
            .getConfiguredServerIds()
            .map { id -> ServerConfiguration.load(prefs, getSecretPrefs(), id) }
            .any { config -> config?.wifiSsid?.isNotEmpty() == true }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val requiredPermission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.ACCESS_FINE_LOCATION
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> Manifest.permission.ACCESS_COARSE_LOCATION
            else -> null
        }

        when {
            !anyServerHasSetWifi -> {
                Log.d(TAG, "Cannot auto select server: No server with configured wifi")
                return -1
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !LocationManagerCompat.isLocationEnabled(locationManager) -> {
                Log.d(TAG, "Cannot auto select server: Location off")
                showSnackbar(
                    SNACKBAR_TAG_SWITCHED_SERVER,
                    R.string.settings_multi_server_wifi_ssid_location_off,
                )
                return -1
            }
            requiredPermission != null && !hasPermissions(arrayOf(requiredPermission)) -> {
                Log.d(TAG, "Cannot auto select server: Missing permission $requiredPermission")
                showSnackbar(
                    SNACKBAR_TAG_SWITCHED_SERVER,
                    R.string.settings_multi_server_wifi_ssid_missing_permissions,
                    actionResId = R.string.settings_background_tasks_permission_allow
                ) {
                    requestPermissionsIfRequired(
                        arrayOf(requiredPermission),
                        REQUEST_CODE_PERMISSIONS
                    )
                }
                return -1
            }
            ssid == prevSsid -> {
                Log.d(TAG, "Cannot auto select server: SSID didn't change since the last check")
                return -1
            }
            ssid.isNullOrEmpty() -> {
                Log.d(TAG, "Cannot auto select server: SSID empty, probably not connected to wifi")
                return -1
            }
        }

        val serverForCurrentWifi = prefs
            .getConfiguredServerIds()
            .map { id -> ServerConfiguration.load(prefs, getSecretPrefs(), id) }
            .firstOrNull { config -> config?.wifiSsid == ssid }
            ?: return -1

        val currentActive = prefs.getActiveServerId()
        if (serverForCurrentWifi.id == currentActive) {
            Log.d(TAG, "Server for current wifi already active")
            return -1
        }
        return serverForCurrentWifi.id
    }

    private fun switchServerBasedOnWifi(serverId: Int) {
        val prevActiveServer = prefs.getActiveServerId()
        val serverForCurrentWifi = ServerConfiguration.load(prefs, getSecretPrefs(), serverId) ?: return

        prefs.edit {
            putActiveServerId(serverForCurrentWifi.id)
        }
        showSnackbar(
            SNACKBAR_TAG_SWITCHED_SERVER,
            getString(R.string.settings_multi_server_wifi_ssid_switched, serverForCurrentWifi.name),
            Snackbar.LENGTH_LONG,
            R.string.undo
        ) {
            prefs.edit {
                putActiveServerId(prevActiveServer)
            }
        }
    }

    private fun handleConnectionChange() {
        if (connection is DemoConnection) {
            showSnackbar(
                SNACKBAR_TAG_DEMO_MODE_ACTIVE,
                R.string.info_demo_mode_short,
                actionResId = R.string.turn_off
            ) {
                prefs.edit {
                    putBoolean(PrefKeys.DEMO_MODE, false)
                }
            }
        } else {
            val hasLocalAndRemote =
                ConnectionFactory.activeLocalConnection != null && ConnectionFactory.activeRemoteConnection != null
            val type = connection?.connectionType
            if (hasLocalAndRemote && type == Connection.TYPE_LOCAL) {
                showSnackbar(
                    SNACKBAR_TAG_CONNECTION_ESTABLISHED,
                    R.string.info_conn_url,
                    Snackbar.LENGTH_SHORT
                )
            } else if (hasLocalAndRemote && type == Connection.TYPE_REMOTE) {
                showSnackbar(
                    SNACKBAR_TAG_CONNECTION_ESTABLISHED,
                    R.string.info_conn_rem_url,
                    Snackbar.LENGTH_SHORT
                )
            }
        }
        queryServerProperties()
    }

    fun enableWifiAndIndicateStartup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
            startActivity(panelIntent)
        } else {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = true
            controller.updateConnection(null, getString(R.string.waiting_for_wifi),
                R.drawable.ic_wifi_strength_outline_grey_24dp)
        }
    }

    fun retryServerPropertyQuery() {
        controller.clearServerCommunicationFailure()
        queryServerProperties()
    }

    override fun doesLockModeRequirePrompt(mode: ScreenLockMode): Boolean {
        return mode == ScreenLockMode.Enabled
    }

    private fun queryServerProperties() {
        propsUpdateHandle?.cancel()
        retryJob?.cancel(CancellationException("queryServerProperties() was called"))
        val successCb: (ServerProperties) -> Unit = { props ->
            serverProperties = props
            updateSitemapDrawerEntries()
            if (props.sitemaps.isEmpty()) {
                Log.e(TAG, "openHAB returned empty Sitemap list")
                controller.indicateServerCommunicationFailure(getString(R.string.error_empty_sitemap_list))
                scheduleRetry {
                    retryServerPropertyQuery()
                }
            } else {
                chooseSitemap()
            }
            if (connection !is DemoConnection) {
                prefs.edit {
                    putInt(PrefKeys.PREV_SERVER_FLAGS, props.flags)
                }
            }
            handlePendingAction()
        }
        propsUpdateHandle = ServerProperties.fetch(this, connection!!,
            successCb, this::handlePropertyFetchFailure)
        BackgroundTasksManager.triggerPeriodicWork(this)
    }

    private fun chooseSitemap() {
        val sitemap = selectConfiguredSitemapFromList()
        if (sitemap != null) {
            controller.openSitemap(sitemap)
        } else {
            showSitemapSelectionDialog()
        }
    }

    private fun handleServiceResolveResult(info: ServiceInfo?) {
        if (info != null && prefs.getConfiguredServerIds().isEmpty()) {
            info.addToPrefs(this)
        } else {
            Log.d(TAG, "Failed to discover openHAB server")
            controller.indicateMissingConfiguration(resolveAttempted = true, wouldHaveUsedOfficialServer = false)
        }
    }

    private fun processIntent(intent: Intent) {
        Log.d(TAG, "Got intent: $intent")

        if (intent.action == Intent.ACTION_MAIN) {
            intent.action = prefs.getStringOrNull(PrefKeys.START_PAGE)
        }

        when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED, Intent.ACTION_VIEW -> {
                val tag = intent.data?.toTagData()
                BackgroundTasksManager.enqueueNfcUpdateIfNeeded(this, tag)

                val sitemapUrl = tag?.sitemap
                if (!sitemapUrl.isNullOrEmpty()) {
                    executeOrStoreAction(PendingAction.OpenSitemapUrl(sitemapUrl, 0))
                }
            }
            ACTION_NOTIFICATION_SELECTED -> {
                CloudMessagingHelper.onNotificationSelected(this, intent)
                val notificationId = intent.getStringExtra(EXTRA_PERSISTED_NOTIFICATION_ID).orEmpty()
                executeActionIfPossible(PendingAction.OpenNotification(notificationId, true))
            }
            ACTION_HABPANEL_SELECTED, ACTION_OH3_UI_SELECTED, ACTION_FRONTAIL_SELECTED -> {
                val serverId = intent.getIntExtra(EXTRA_SERVER_ID, prefs.getActiveServerId())
                val ui = when (intent.action) {
                    ACTION_HABPANEL_SELECTED -> WebViewUi.HABPANEL
                    ACTION_FRONTAIL_SELECTED -> WebViewUi.FRONTAIL
                    else -> WebViewUi.OH3_UI
                }
                val subpage = intent.getStringExtra(EXTRA_SUBPAGE)
                executeOrStoreAction(PendingAction.OpenWebViewUi(ui, serverId, subpage))
            }
            ACTION_VOICE_RECOGNITION_SELECTED -> executeOrStoreAction(PendingAction.LaunchVoiceRecognition())
            ACTION_SITEMAP_SELECTED -> {
                val sitemapUrl = intent.getStringExtra(EXTRA_SITEMAP_URL) ?: return
                val serverId = intent.getIntExtra(EXTRA_SERVER_ID, prefs.getActiveServerId())
                executeOrStoreAction(PendingAction.OpenSitemapUrl(sitemapUrl, serverId))
            }
        }
    }

    fun triggerPageUpdate(pageUrl: String, forceReload: Boolean) {
        controller.triggerPageUpdate(pageUrl, forceReload)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.openhab_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)

        progressBar = toolbar.findViewById(R.id.toolbar_progress_bar)
        setProgressIndicatorVisible(false)
    }

    private fun setupDrawer() {
        drawerLayout = findViewById(R.id.activity_content)
        drawerToggle = ActionBarDrawerToggle(this, drawerLayout,
            R.string.drawer_open, R.string.drawer_close)
        drawerLayout.addDrawerListener(drawerToggle)
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                if (serverProperties != null && propsUpdateHandle == null) {
                    propsUpdateHandle = ServerProperties.updateSitemaps(this@MainActivity,
                        serverProperties!!, connection!!,
                        { props ->
                            serverProperties = props
                            updateSitemapDrawerEntries()
                        },
                        this@MainActivity::handlePropertyFetchFailure)
                }
            }
            override fun onDrawerClosed(drawerView: View) {
                super.onDrawerClosed(drawerView)
                updateDrawerMode(false)
            }
        })
        drawerLayout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START)

        val drawerView = findViewById<NavigationView>(R.id.left_drawer)
        drawerView.inflateMenu(R.menu.left_drawer)
        drawerMenu = drawerView.menu

        // We only want to tint the menu icons, but not our loaded sitemap icons. NavigationView
        // unfortunately doesn't support this directly, so we tint the icon drawables manually
        // instead of letting NavigationView do it.
        drawerIconTintList = drawerView.itemIconTintList
        drawerView.itemIconTintList = null
        drawerMenu.forEach { item -> item.icon = applyDrawerIconTint(item.icon) }

        drawerView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawers()
            var handled = false
            when (item.itemId) {
                R.id.notifications -> {
                    openNotifications(null, false)
                    handled = true
                }
                R.id.nfc -> {
                    val intent = Intent(this, NfcItemPickerActivity::class.java)
                    startActivity(intent)
                    handled = true
                }
                R.id.habpanel -> {
                    openWebViewUi(WebViewUi.HABPANEL, false, null)
                    handled = true
                }
                R.id.oh3_ui -> {
                    openWebViewUi(WebViewUi.OH3_UI, false, null)
                    handled = true
                }
                R.id.frontail -> {
                    openWebViewUi(WebViewUi.FRONTAIL, false, null)
                    handled = true
                }
                R.id.settings -> {
                    val settingsIntent = Intent(this@MainActivity, PreferencesActivity::class.java)
                    settingsIntent.putExtra(PreferencesActivity.START_EXTRA_SERVER_PROPERTIES, serverProperties)
                    preferenceActivityCallback.launch(settingsIntent)
                    handled = true
                }
                R.id.about -> {
                    val aboutIntent = Intent(this, AboutActivity::class.java)
                    aboutIntent.putExtra("serverProperties", serverProperties)
                    startActivity(aboutIntent)
                    handled = true
                }
                R.id.default_sitemap -> {
                    val sitemap = serverProperties?.sitemaps?.firstOrNull { s ->
                        s.name == prefs.getDefaultSitemap(connection)?.name
                    }
                    if (sitemap != null) {
                        controller.openSitemap(sitemap)
                        handled = true
                    } else if (prefs.getDefaultSitemap(connection) != null) {
                        executeOrStoreAction(PendingAction.ChooseSitemap())
                        handled = true
                    }
                }
            }
            if (item.groupId == R.id.sitemaps) {
                val sitemap = serverProperties?.sitemaps?.firstOrNull { s -> s.name.hashCode() == item.itemId }
                if (sitemap != null) {
                    controller.openSitemap(sitemap)
                    handled = true
                }
            }
            if (item.groupId == R.id.servers) {
                prefs.edit {
                    putActiveServerId(item.itemId)
                }
                updateServerNameInDrawer()
                // Menu views aren't updated in a click handler, so defer the menu update
                launch {
                    updateDrawerMode(false)
                }
                handled = true
            }
            handled
        }

        drawerModeSelectorContainer = drawerView.inflateHeaderView(R.layout.drawer_header)
        drawerModeSelectorContainer.setOnClickListener { updateDrawerMode(!inServerSelectionMode) }
        drawerModeToggle = drawerModeSelectorContainer.findViewById(R.id.drawer_mode_switcher)
        drawerServerNameView = drawerModeSelectorContainer.findViewById(R.id.server_name)
    }

    private fun updateDrawerServerEntries() {
        // Remove existing items from server group
        drawerMenu.getGroupItems(R.id.servers)
            .forEach { item -> drawerMenu.removeItem(item.itemId) }

        // Add new items
        val configs = prefs.getConfiguredServerIds()
            .mapNotNull { id -> ServerConfiguration.load(prefs, getSecretPrefs(), id) }
        configs.forEachIndexed { index, config -> drawerMenu.add(R.id.servers, config.id, index, config.name) }

        if (configs.size > 1 && connection !is DemoConnection) {
            drawerModeSelectorContainer.isVisible = true
        } else {
            drawerModeSelectorContainer.isGone = true
            inServerSelectionMode = false
        }

        updateServerNameInDrawer()
        updateDrawerItemVisibility()
    }

    private fun updateSitemapDrawerEntries() {
        val defaultSitemapItem = drawerMenu.findItem(R.id.default_sitemap)
        val sitemaps = serverProperties?.sitemaps
            ?.sortedWithDefaultName(prefs.getDefaultSitemap(connection)?.name.orEmpty())

        drawerMenu.getGroupItems(R.id.sitemaps)
            .filter { item -> item !== defaultSitemapItem }
            .forEach { item -> drawerMenu.removeItem(item.itemId) }

        if (sitemaps?.isNotEmpty() != true) {
            return
        }

        if (prefs.areSitemapsShownInDrawer()) {
            sitemaps.forEachIndexed { index, sitemap ->
                val item = drawerMenu.add(R.id.sitemaps, sitemap.name.hashCode(), index, sitemap.label)
                loadSitemapIcon(sitemap, item)
            }
        } else {
            val sitemap = serverProperties?.sitemaps?.firstOrNull { s ->
                s.name == prefs.getDefaultSitemap(connection)?.name.orEmpty()
            }
            if (sitemap != null) {
                defaultSitemapItem.title = sitemap.label
                loadSitemapIcon(sitemap, defaultSitemapItem)
            } else {
                defaultSitemapItem.title = getString(R.string.mainmenu_openhab_selectsitemap)
                defaultSitemapItem.icon =
                    applyDrawerIconTint(ContextCompat.getDrawable(this, R.drawable.ic_openhab_appicon_24dp))
            }
        }

        updateDrawerItemVisibility()
    }

    private fun updateServerNameInDrawer() {
        val activeConfig = ServerConfiguration.load(prefs, getSecretPrefs(), prefs.getActiveServerId())
        drawerServerNameView.text = activeConfig?.name
    }

    private fun updateDrawerItemVisibility() {
        val serverItems = drawerMenu.getGroupItems(R.id.servers)
        drawerMenu.setGroupVisible(R.id.servers, serverItems.size > 1 && inServerSelectionMode)

        if (serverProperties?.sitemaps?.isNotEmpty() == true && !inServerSelectionMode) {
            drawerMenu.setGroupVisible(R.id.sitemaps, true)

            val defaultSitemapItem = drawerMenu.findItem(R.id.default_sitemap)
            defaultSitemapItem.isVisible = !prefs.areSitemapsShownInDrawer()
        } else {
            drawerMenu.setGroupVisible(R.id.sitemaps, false)
        }

        if (inServerSelectionMode) {
            drawerMenu.setGroupVisible(R.id.options, false)
        } else {
            drawerMenu.setGroupVisible(R.id.options, true)

            val notificationsItem = drawerMenu.findItem(R.id.notifications)
            notificationsItem.isVisible = ConnectionFactory.activeCloudConnection?.connection != null

            val habPanelItem = drawerMenu.findItem(R.id.habpanel)
            habPanelItem.isVisible = serverProperties?.hasWebViewUiInstalled(WebViewUi.HABPANEL) == true &&
                prefs.getBoolean(PrefKeys.DRAWER_ENTRY_HABPANEL, true)
            manageHabPanelShortcut(serverProperties?.hasWebViewUiInstalled(WebViewUi.HABPANEL) == true)

            val oh3UiItem = drawerMenu.findItem(R.id.oh3_ui)
            oh3UiItem.isVisible = serverProperties?.hasWebViewUiInstalled(WebViewUi.OH3_UI) == true &&
                prefs.getBoolean(PrefKeys.DRAWER_ENTRY_OH3_UI, true)

            val frontailItem = drawerMenu.findItem(R.id.frontail)
            frontailItem.isVisible = serverProperties != null &&
                connection?.connectionType == Connection.TYPE_LOCAL &&
                prefs.getBoolean(PrefKeys.DRAWER_ENTRY_FRONTAIL, false)

            val nfcItem = drawerMenu.findItem(R.id.nfc)
            nfcItem.isVisible = serverProperties != null &&
                (NfcAdapter.getDefaultAdapter(this) != null || Util.isEmulator()) &&
                prefs.getPrimaryServerId() == prefs.getActiveServerId() &&
                prefs.getBoolean(PrefKeys.DRAWER_ENTRY_NFC, true)
        }
    }

    private fun updateDrawerMode(inServerMode: Boolean) {
        if (inServerMode == inServerSelectionMode) {
            return
        }
        inServerSelectionMode = inServerMode
        drawerModeToggle.setImageResource(
            if (inServerSelectionMode) R.drawable.ic_menu_up_24dp else R.drawable.ic_menu_down_24dp
        )
        updateDrawerItemVisibility()
    }

    private fun loadSitemapIcon(sitemap: Sitemap, item: MenuItem) {
        val defaultIcon = ContextCompat.getDrawable(this, R.drawable.ic_openhab_appicon_24dp)
        item.icon = applyDrawerIconTint(defaultIcon)
        val conn = connection

        if (sitemap.icon == null || conn == null) {
            return
        }
        launch {
            try {
                item.icon = conn.httpClient.get(
                    sitemap.icon.toUrl(this@MainActivity, determineDataUsagePolicy().loadIconsWithState)
                )
                    .asBitmap(defaultIcon!!.intrinsicWidth, ImageConversionPolicy.ForceTargetSize)
                    .response
                    .toDrawable(resources)
            } catch (e: HttpClient.HttpException) {
                Log.w(TAG, "Could not fetch icon for sitemap ${sitemap.name}")
            }
        }
    }

    private fun applyDrawerIconTint(icon: Drawable?): Drawable? {
        if (icon == null) {
            return null
        }
        val wrapped = DrawableCompat.wrap(icon.mutate())
        DrawableCompat.setTintList(wrapped, drawerIconTintList)
        return wrapped
    }

    private fun executeOrStoreAction(action: PendingAction) {
        if (!executeActionIfPossible(action)) {
            pendingAction = action
        }
    }

    private fun handlePendingAction() {
        val action = pendingAction
        if (action != null && executeActionIfPossible(action)) {
            pendingAction = null
        }
    }

    private fun executeActionIfPossible(action: PendingAction): Boolean = when {
        action is PendingAction.ChooseSitemap && isStarted -> {
            chooseSitemap()
            true
        }
        action is PendingAction.OpenSitemapUrl && isStarted && serverProperties != null -> {
            executeActionForServer(action.serverId) { buildUrlAndOpenSitemap(action.url) }
        }
        action is PendingAction.OpenWebViewUi && isStarted &&
            serverProperties?.hasWebViewUiInstalled(action.ui) == true -> {
            executeActionForServer(action.serverId) { openWebViewUi(action.ui, true, action.subpage) }
        }
        action is PendingAction.LaunchVoiceRecognition && serverProperties != null -> {
            launchVoiceRecognition()
            true
        }
        action is PendingAction.OpenNotification && isStarted -> {
            val conn = if (action.primary) {
                ConnectionFactory.primaryCloudConnection
            } else {
                ConnectionFactory.activeCloudConnection
            }
            if (conn?.connection != null) {
                openNotifications(action.notificationId, action.primary)
                true
            } else {
                false
            }
        }
        else -> false
    }

    private fun executeActionForServer(serverId: Int, action: () -> Unit): Boolean = when {
        serverId !in prefs.getConfiguredServerIds() -> {
            showSnackbar(
                SNACKBAR_TAG_SERVER_MISSING,
                R.string.home_shortcut_server_has_been_deleted
            )
            true
        }
        serverId != prefs.getActiveServerId() -> {
            prefs.edit {
                putActiveServerId(serverId)
            }
            updateDrawerServerEntries()
            false
        }
        else -> {
            action()
            true
        }
    }

    private fun selectConfiguredSitemapFromList(): Sitemap? {
        val configuredSitemap = prefs.getDefaultSitemap(connection)?.name.orEmpty()
        val sitemaps = serverProperties?.sitemaps
        val result = when {
            sitemaps == null -> null
            // We only have one sitemap, use it
            sitemaps.size == 1 -> sitemaps[0]
            // Select configured sitemap if still present, nothing otherwise
            configuredSitemap.isNotEmpty() -> sitemaps.firstOrNull { sitemap -> sitemap.name == configuredSitemap }
            // Nothing configured -> can't auto-select anything
            else -> null
        }

        Log.d(TAG, "Configured sitemap is '$configuredSitemap', selected $result")
        if (result == null && configuredSitemap.isNotEmpty()) {
            // clear old configuration
            prefs.updateDefaultSitemap(connection, null)
        } else if (result != null && (configuredSitemap.isEmpty() || configuredSitemap != result.name)) {
            // update result
            prefs.updateDefaultSitemap(connection, result)
            updateSitemapDrawerEntries()
        }

        return result
    }

    private fun showSitemapSelectionDialog() {
        Log.d(TAG, "Opening sitemap selection dialog")
        if (sitemapSelectionDialog?.isShowing == true) {
            sitemapSelectionDialog?.dismiss()
        }
        val sitemaps = serverProperties?.sitemaps
        if (isFinishing || sitemaps == null) {
            return
        }
        val sitemapLabels = sitemaps.map { s -> s.label }.toTypedArray()
        sitemapSelectionDialog = AlertDialog.Builder(this)
            .setTitle(R.string.mainmenu_openhab_selectsitemap)
            .setItems(sitemapLabels) { _, which ->
                val sitemap = sitemaps[which]
                Log.d(TAG, "Selected sitemap $sitemap")
                prefs.updateDefaultSitemap(connection, sitemap)
                controller.openSitemap(sitemap)
                updateSitemapDrawerEntries()
            }
            .show()
    }

    private fun openNotifications(highlightedId: String?, primaryServer: Boolean) {
        controller.openNotifications(highlightedId, primaryServer)
        drawerToggle.isDrawerIndicatorEnabled = false
    }

    private fun openWebViewUi(ui: WebViewUi, isStackRoot: Boolean, subpage: String?) {
        hideSnackbar(SNACKBAR_TAG_SSE_ERROR)
        controller.showWebViewUi(ui, isStackRoot, subpage)
        drawerToggle.isDrawerIndicatorEnabled = isStackRoot
    }

    private fun buildUrlAndOpenSitemap(partUrl: String) {
        controller.openPage("rest/sitemaps$partUrl")
    }

    fun onWidgetSelected(linkedPage: LinkedPage, source: WidgetListFragment) {
        Log.d(TAG, "Got widget link = ${linkedPage.link}")
        controller.openPage(linkedPage, source)
    }

    fun updateTitle() {
        val title = controller.currentTitle
        setTitle(title ?: getString(R.string.app_name))
        drawerToggle.isDrawerIndicatorEnabled = !controller.canGoBack()
    }

    fun setProgressIndicatorVisible(visible: Boolean) {
        if (visible) {
            progressBar?.show()
        } else {
            progressBar?.hide()
        }
    }

    private fun launchVoiceRecognition() {
        val speechIntent = BackgroundTasksManager.buildVoiceRecognitionIntent(this, false)
        try {
            startActivity(speechIntent)
        } catch (e: ActivityNotFoundException) {
            showSnackbar(
                SNACKBAR_TAG_NO_VOICE_RECOGNITION_INSTALLED,
                R.string.error_no_speech_to_text_app_found,
                actionResId = R.string.install
            ) {
                openInAppStore("com.google.android.googlequicksearchbox")
            }
        }
    }

    private suspend fun showPushNotificationWarningIfNeeded() {
        val status = CloudMessagingHelper.getPushNotificationStatus(this@MainActivity)
        if (status.notifyUser) {
            showSnackbar(SNACKBAR_TAG_PUSH_NOTIFICATION_FAIL, status.message)
        }
    }

    fun showRefreshHintSnackbarIfNeeded() {
        if (prefs.getBoolean(PrefKeys.SWIPE_REFRESH_EXPLAINED, false)) {
            return
        }

        showSnackbar(
            SNACKBAR_TAG_NO_MANUAL_REFRESH_REQUIRED,
            R.string.swipe_to_refresh_description,
            actionResId = R.string.got_it
        ) {
            prefs.edit {
                putBoolean(PrefKeys.SWIPE_REFRESH_EXPLAINED, true)
            }
        }
    }

    fun showDataSaverHintSnackbarIfNeeded() {
        if (prefs.getBoolean(PrefKeys.DATA_SAVER_EXPLAINED, false) ||
            determineDataUsagePolicy().loadIconsWithState
        ) {
            return
        }

        showSnackbar(
            SNACKBAR_TAG_DATA_SAVER_ON,
            R.string.data_saver_snackbar,
            actionResId = R.string.got_it
        ) {
            prefs.edit {
                putBoolean(PrefKeys.DATA_SAVER_EXPLAINED, true)
            }
        }
    }

    fun setDrawerLocked(locked: Boolean) {
        drawerLayout.isSwipeDisabled = locked
    }

    private fun handlePropertyFetchFailure(request: Request, statusCode: Int, error: Throwable) {
        Log.e(TAG, "Error: $error", error)
        Log.e(TAG, "HTTP status code: $statusCode")
        var message = getHumanReadableErrorMessage(request.url.toString(), statusCode, error, false)
        if (prefs.isDebugModeEnabled()) {
            message = SpannableStringBuilder(message).apply {
                inSpans(RelativeSizeSpan(0.8f)) {
                    append("\n\nURL: ").append(request.url.toString())

                    val authHeader = request.header("Authorization")
                    if (authHeader?.startsWith("Basic") == true) {
                        val base64Credentials = authHeader.substring("Basic".length).trim()
                        val credentials = String(Base64.decode(base64Credentials, Base64.DEFAULT),
                            Charset.forName("UTF-8"))
                        append("\nUsername: ")
                        append(credentials.substring(0, credentials.indexOf(":")))
                    }

                    append("\nException stack:\n")
                }

                inSpans(RelativeSizeSpan(0.6f)) {
                    var origError: Throwable?
                    var cause: Throwable? = error
                    do {
                        append(cause?.toString()).append('\n')
                        origError = cause
                        cause = origError?.cause
                    } while (cause != null && origError !== cause)
                }
            }
        }

        controller.indicateServerCommunicationFailure(message)
        scheduleRetry {
            retryServerPropertyQuery()
        }
        propsUpdateHandle = null
    }

    private fun showMissingPermissionsWarningIfNeeded() {
        val missingPermissions = BackgroundTasksManager.KNOWN_KEYS
            .filter { entry ->
                val requiredPermissions = BackgroundTasksManager.getRequiredPermissionsForTask(entry)
                prefs.getStringOrNull(entry)?.toItemUpdatePrefValue()?.first == true &&
                    requiredPermissions != null && !hasPermissions(requiredPermissions)
            }
            .mapNotNull { entry -> BackgroundTasksManager.getRequiredPermissionsForTask(entry)?.toList() }
            .flatten()
            .toSet()
            .filter { !hasPermissions(arrayOf(it)) }
            .toMutableList()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            missingPermissions.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            if (missingPermissions.size > 1) {
                Log.d(TAG, "Remove background location from permissions to request")
                missingPermissions.remove(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                showSnackbar(
                    SNACKBAR_TAG_BG_TASKS_MISSING_PERMISSION_LOCATION,
                    getString(
                        R.string.settings_background_tasks_permission_denied_background_location,
                        packageManager.backgroundPermissionOptionLabel
                    ),
                    actionResId = android.R.string.ok
                ) {
                    Intent(Settings.ACTION_APPLICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, BuildConfig.APPLICATION_ID)
                        startActivity(this)
                    }
                }
                return
            }
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, "At least one permission for background tasks has been denied")
            showSnackbar(
                SNACKBAR_TAG_BG_TASKS_MISSING_PERMISSIONS,
                R.string.settings_background_tasks_permission_denied,
                actionResId = R.string.settings_background_tasks_permission_allow
            ) {
                requestPermissionsIfRequired(
                    missingPermissions.toTypedArray(),
                    REQUEST_CODE_PERMISSIONS
                )
            }
        }
    }

    private fun manageHabPanelShortcut(visible: Boolean) {
        manageShortcut(visible, "habpanel", ACTION_HABPANEL_SELECTED,
            R.string.mainmenu_openhab_habpanel, R.mipmap.ic_shortcut_habpanel,
            R.string.app_shortcut_disabled_habpanel)
    }

    private fun manageNotificationShortcut(visible: Boolean) {
        manageShortcut(visible, "notification", ACTION_NOTIFICATION_SELECTED,
            R.string.app_notifications, R.mipmap.ic_shortcut_notifications,
            R.string.app_shortcut_disabled_notifications)
    }

    private fun manageVoiceRecognitionShortcut(visible: Boolean) {
        manageShortcut(visible, "voice_recognition", ACTION_VOICE_RECOGNITION_SELECTED,
            R.string.mainmenu_openhab_voice_recognition,
            R.mipmap.ic_shortcut_voice_recognition,
            R.string.app_shortcut_disabled_voice_recognition)
    }

    private fun manageShortcut(
        visible: Boolean,
        id: String,
        action: String,
        @StringRes shortLabel: Int,
        @DrawableRes icon: Int,
        @StringRes disableMessage: Int
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return
        }
        if (visible) {
            val intent = Intent(this, MainActivity::class.java)
                .setAction(action)
            val shortcut = ShortcutInfo.Builder(this, id)
                .setShortLabel(getString(shortLabel))
                .setIcon(Icon.createWithResource(this, icon))
                .setIntent(intent)
                .build()
            try {
                shortcutManager?.addDynamicShortcuts(listOf(shortcut))
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Failed to add shortcut $id", e)
            }
        } else {
            shortcutManager?.disableShortcuts(listOf(id), getString(disableMessage))
        }
    }

    private fun setVoiceWidgetComponentEnabledSetting(component: Class<*>, isSpeechRecognizerAvailable: Boolean) {
        val voiceWidget = ComponentName(this, component)
        val newState = if (isSpeechRecognizerAvailable)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(voiceWidget, newState, PackageManager.DONT_KILL_APP)
    }

    private sealed class PendingAction {
        class ChooseSitemap : PendingAction()
        class OpenSitemapUrl constructor(val url: String, val serverId: Int) : PendingAction()
        class OpenWebViewUi constructor(val ui: WebViewUi, val serverId: Int, val subpage: String?) : PendingAction()
        class LaunchVoiceRecognition : PendingAction()
        class OpenNotification constructor(val notificationId: String, val primary: Boolean) : PendingAction()
    }

    companion object {
        const val ACTION_NOTIFICATION_SELECTED = "org.openhab.habdroid.action.NOTIFICATION_SELECTED"
        const val ACTION_HABPANEL_SELECTED = "org.openhab.habdroid.action.HABPANEL_SELECTED"
        const val ACTION_OH3_UI_SELECTED = "org.openhab.habdroid.action.OH3_UI_SELECTED"
        const val ACTION_FRONTAIL_SELECTED = "org.openhab.habdroid.action.FRONTAIL"
        const val ACTION_VOICE_RECOGNITION_SELECTED = "org.openhab.habdroid.action.VOICE_SELECTED"
        const val ACTION_SITEMAP_SELECTED = "org.openhab.habdroid.action.SITEMAP_SELECTED"
        const val EXTRA_SITEMAP_URL = "sitemapUrl"
        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_SUBPAGE = "subpage"
        const val EXTRA_PERSISTED_NOTIFICATION_ID = "persistedNotificationId"

        const val SNACKBAR_TAG_DEMO_MODE_ACTIVE = "demoModeActive"
        const val SNACKBAR_TAG_PRESS_AGAIN_EXIT = "pressAgainToExit"
        const val SNACKBAR_TAG_CONNECTION_ESTABLISHED = "connectionEstablished"
        const val SNACKBAR_TAG_PUSH_NOTIFICATION_FAIL = "pushNotificationFail"
        const val SNACKBAR_TAG_DATA_SAVER_ON = "dataSaverOn"
        const val SNACKBAR_TAG_NO_VOICE_RECOGNITION_INSTALLED = "noVoiceRecognitionInstalled"
        const val SNACKBAR_TAG_NO_MANUAL_REFRESH_REQUIRED = "noManualRefreshRequired"
        const val SNACKBAR_TAG_BG_TASKS_MISSING_PERMISSIONS = "bgTasksMissingPermissions"
        const val SNACKBAR_TAG_BG_TASKS_MISSING_PERMISSION_LOCATION = "bgTasksMissingPermissionLocation"
        const val SNACKBAR_TAG_SSE_ERROR = "sseError"
        const val SNACKBAR_TAG_SHORTCUT_INFO = "shortcutInfo"
        const val SNACKBAR_TAG_SERVER_MISSING = "serverMissing"
        const val SNACKBAR_TAG_SWITCHED_SERVER = "switchedServer"

        private const val STATE_KEY_SERVER_PROPERTIES = "serverProperties"
        private const val STATE_KEY_SITEMAP_SELECTION_SHOWN = "isSitemapSelectionDialogShown"
        private const val STATE_KEY_CONTROLLER_NAME = "controller"
        private const val STATE_KEY_CONNECTION_HASH = "connectionHash"

        private val TAG = MainActivity::class.java.simpleName

        // Activities request codes
        private const val REQUEST_CODE_PERMISSIONS = 1002
    }
}
