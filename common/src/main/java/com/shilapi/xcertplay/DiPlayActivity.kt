// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.util.concurrent.Executors

/** G3 driver-facing launcher. Only the vehicle's ordinary WPA2 hotspot is available. */
class DiPlayActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var hotspot: G3HotspotController
    private lateinit var ssid: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var applyButton: Button
    private lateinit var connectButton: Button
    private val guardedControls = mutableListOf<View>()
    private var hotspotReady = false
    private var readInFlight = false
    private var loading = true
    private var dirty = false
    private var busy = false
    private var authReady = false
    private val poll = object : Runnable {
        override fun run() {
            if (!busy) status.setText(when {
                CarPlayBackgroundSession.active -> R.string.g3_connected
                CarPlayBackgroundSession.hasSession() -> R.string.g3_connecting
                !hotspotReady -> R.string.g3_read_required
                authReady -> R.string.g3_ready
                else -> R.string.g3_auth_missing
            })
            handler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hotspot = G3HotspotController(this)
        setContentView(buildScreen())
        refreshHotspot()
        worker.execute {
            val ready = runCatching { DiPlayBootstrap.ensure(this, MfiTarget.LOCAL) }.isSuccess
            runOnUiThread { if (!isDestroyed) authReady = ready }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poll)
        handler.post(poll)
        if (!dirty && !loading && !busy) refreshHotspot()
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(SCREEN_PADDING_DP), dp(SCREEN_PADDING_DP), dp(SCREEN_PADDING_DP), dp(SCREEN_PADDING_DP))
            setBackgroundColor(Color.rgb(18, 25, 36))
        }
        val connection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var destination = connection
        fun text(id: Int, size: Float = BODY_TEXT_SP): TextView = TextView(this).apply {
            setText(id); textSize = size; setTextColor(Color.WHITE)
            setPadding(0, dp(BLOCK_GAP_DP), 0, dp(BLOCK_GAP_DP))
        }
        fun addButton(id: Int, tagName: String, click: () -> Unit): Button = Button(this).apply {
            setText(id); tag = tagName; textSize = BODY_TEXT_SP; isAllCaps = false
            minHeight = dp(CONTROL_HEIGHT_DP)
            guardedControls.add(this)
            setOnClickListener { if (!busy && !loading) click() }
            destination.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(BLOCK_GAP_DP) })
        }
        body.addView(text(R.string.app_name, TITLE_TEXT_SP))
        body.addView(text(R.string.g3_intro))
        status = text(R.string.g3_loading).also { it.tag = "g3_status"; destination.addView(it) }
        addButton(R.string.g3_choose_phone, "g3_choose_phone") { choosePhone(false) }
        connectButton = addButton(R.string.g3_connect, "g3_connect") { connect() }
        addButton(R.string.g3_disconnect, "g3_disconnect") {
            CarPlayBackgroundSession.stop { runOnUiThread { toast(R.string.g3_disconnected) } }
        }
        destination = settings
        destination.addView(text(R.string.settings_g3_hotspot, TITLE_TEXT_SP))
        destination.addView(text(R.string.settings_g3_hotspot_help))
        addButton(R.string.settings_g3_authorize, "g3_authorize") {
            AlertDialog.Builder(this).setMessage(R.string.settings_g3_authorize_help)
                .setPositiveButton(R.string.settings_g3_authorize) { _, _ ->
                    if (busy || loading) return@setPositiveButton
                    setBusy(true)
                    worker.execute {
                        val ready = G3HotspotShellClient(applicationContext).authorize()
                        runOnUiThread {
                            if (!isDestroyed) {
                                setBusy(false)
                                if (ready && !dirty) refreshHotspot()
                                else if (ready) toast(R.string.settings_g3_authorized)
                                else toast(R.string.settings_g3_authorize_failed)
                            }
                        }
                    }
                }.setNegativeButton(android.R.string.cancel, null).show()
        }

        destination.addView(text(R.string.settings_g3_ssid))
        ssid = EditText(this).apply {
            tag = "g3_ssid"; textSize = BODY_TEXT_SP; setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true; isSaveEnabled = false
            minHeight = dp(CONTROL_HEIGHT_DP)
        }
        destination.addView(ssid, LinearLayout.LayoutParams(-1, -2))
        destination.addView(text(R.string.settings_g3_password))
        password = EditText(this).apply {
            tag = "g3_password"; textSize = BODY_TEXT_SP; setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true; isSaveEnabled = false
            minHeight = dp(CONTROL_HEIGHT_DP)
        }
        destination.addView(password, LinearLayout.LayoutParams(-1, -2))
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!loading) dirty = true }
            override fun afterTextChanged(s: Editable?) {}
        }
        ssid.addTextChangedListener(watcher); password.addTextChangedListener(watcher)
        destination.addView(CheckBox(this).apply {
            setText(R.string.settings_g3_show_password); setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, shown ->
                // Screenshots are blocked while a password is visible.
                if (shown) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                password.inputType = InputType.TYPE_CLASS_TEXT or
                    if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                password.setSelection(password.text.length)
            }
        })
        applyButton = addButton(R.string.settings_g3_apply, "g3_apply") { confirmApply() }
        addButton(R.string.settings_g3_refresh, "g3_refresh") {
            if (dirty) AlertDialog.Builder(this).setMessage(R.string.settings_g3_discard)
                .setPositiveButton(R.string.settings_g3_refresh) { _, _ -> refreshHotspot() }
                .setNegativeButton(android.R.string.cancel, null).show()
            else refreshHotspot()
        }
        destination.addView(Switch(this).apply {
            setText(R.string.settings_g3_boot); setTextColor(Color.WHITE)
            isChecked = AirPlayPersistence.loadAutoStartOnBoot(this@DiPlayActivity)
            setOnCheckedChangeListener { _, checked -> AirPlayPersistence.saveAutoStartOnBoot(this@DiPlayActivity, checked) }
            minHeight = dp(CONTROL_HEIGHT_DP)
        })
        destination.addView(text(R.string.g3_phone_hint))
        if (resources.configuration.screenWidthDp >= TWO_COLUMN_MIN_DP) {
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(connection, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(SCREEN_PADDING_DP) })
                addView(settings, LinearLayout.LayoutParams(0, -2, 1.25f))
            })
        } else { body.addView(connection); body.addView(settings) }
        return ScrollView(this).apply { isFillViewport = true; addView(body) }
    }

    private fun refreshHotspot() {
        if (busy || readInFlight) return
        readInFlight = true
        loading = true
        updateControlState()
        worker.execute {
            val read = hotspot.load()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (read) {
                    is G3HotspotReadResult.Success -> {
                        ssid.setText(read.snapshot.ssid); password.setText(read.snapshot.password)
                        saveConnectionCredentials(read.snapshot.ssid, read.snapshot.password)
                        dirty = false; hotspotReady = true
                    }
                    is G3HotspotReadResult.Failure -> { hotspotReady = false; showFailure(read.status) }
                }
                loading = false; readInFlight = false
                updateControlState()
            }
        }
    }

    private fun saveConnectionCredentials(name: String, secret: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, name)
        AirPlayPersistence.saveManualHotspotPassphrase(this, secret)
        AirPlayPersistence.saveManualHotspotSecurity(this, ManualHotspotSecurity.WPA2)
    }

    private fun confirmApply() {
        if (loading || busy) return
        if (CarPlayBackgroundSession.hasSession()) { toast(R.string.settings_g3_stop_projection); return }
        val name = ssid.text.toString(); val secret = password.text.toString()
        G3HotspotValidation.error(name, secret)?.let { showFailure(it); return }
        // Passwords and network names never appear in this confirmation or any logs.
        AlertDialog.Builder(this).setTitle(R.string.settings_g3_apply)
            .setMessage(R.string.settings_g3_restart_warning)
            .setPositiveButton(R.string.settings_g3_apply_restart) { _, _ ->
                if (busy || loading) return@setPositiveButton
                setBusy(true)
                worker.execute {
                    val result = hotspot.apply(name, secret, restartActive = true)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        setBusy(false)
                        if (result.status == G3HotspotStatus.APPLIED) {
                            saveConnectionCredentials(name, secret); dirty = false
                            toast(R.string.settings_g3_saved); refreshHotspot()
                        } else {
                            showFailure(result.status)
                            if (result.rollbackSucceeded == false) toast(R.string.settings_g3_rollback_failed)
                        }
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun connect() {
        if (busy || loading) return
        if (CarPlayBackgroundSession.hasSession()) { openProjection(); return }
        if (!authReady) { toast(R.string.g3_auth_missing); return }
        if (dirty) { toast(R.string.settings_g3_save_first); return }
        if (DiPlayPreferences.phoneAddress(this) == null) { choosePhone(true); return }
        setBusy(true)
        worker.execute {
            val read = hotspot.load()
            val startup = if (read is G3HotspotReadResult.Success)
                CarHotspotTethering.enable(applicationContext, { isDestroyed }, log = {}) else null
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setBusy(false)
                if (read !is G3HotspotReadResult.Success) {
                    showFailure((read as G3HotspotReadResult.Failure).status)
                } else if (startup != CarHotspotTethering.Result.READY) {
                    toast(R.string.g3_hotspot_start_failed)
                } else {
                    saveConnectionCredentials(read.snapshot.ssid, read.snapshot.password)
                    openProjection()
                }
            }
        }
    }

    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    @Suppress("MissingPermission")
    private fun choosePhone(connectAfter: Boolean) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val devices = runCatching { adapter?.bondedDevices?.sortedBy { it.name.orEmpty() } }.getOrNull().orEmpty()
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.g3_pair_phone)
                .setPositiveButton(R.string.g3_bluetooth_settings) { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                        .onFailure { toast(R.string.g3_open_settings_failed) }
                }.setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        AlertDialog.Builder(this).setTitle(R.string.g3_choose_phone)
            .setItems(devices.map { it.name ?: "iPhone" }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                toast(R.string.g3_phone_saved)
                if (connectAfter) connect()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateControlState()
        if (value) status.setText(R.string.g3_working)
    }
    private fun updateControlState() {
        val enabled = !busy && !loading
        guardedControls.forEach { it.isEnabled = enabled }
        ssid.isEnabled = enabled; password.isEnabled = enabled
    }
    private fun toast(id: Int) { Toast.makeText(this, id, Toast.LENGTH_LONG).show() }
    private fun showFailure(value: G3HotspotStatus) = toast(when (value) {
        G3HotspotStatus.INVALID_SSID -> R.string.settings_g3_invalid_ssid
        G3HotspotStatus.INVALID_PASSWORD -> R.string.settings_g3_invalid_password
        G3HotspotStatus.NEEDS_WRITE_SETTINGS, G3HotspotStatus.NEEDS_WRITE_SECURE_SETTINGS -> R.string.settings_g3_permissions
        G3HotspotStatus.BUSY -> R.string.settings_g3_busy
        G3HotspotStatus.UNSUPPORTED -> R.string.settings_g3_unsupported
        else -> R.string.settings_g3_apply_failed
    })
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private companion object {
        const val TWO_COLUMN_MIN_DP = 800
        const val SCREEN_PADDING_DP = 24
        const val BLOCK_GAP_DP = 12
        const val CONTROL_HEIGHT_DP = 56
        const val TITLE_TEXT_SP = 28f
        const val BODY_TEXT_SP = 18f
        const val STATUS_POLL_MS = 1000L
    }
}
