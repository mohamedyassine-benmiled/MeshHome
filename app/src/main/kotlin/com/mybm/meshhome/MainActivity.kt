package com.mybm.meshhome

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.VpnService
import android.content.Intent
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import com.mybm.meshhome.core.*

/** Configuration, explicit account operations, and user-initiated VPN lifecycle. */
class MainActivity : Activity() {
    private lateinit var settings: SettingsStore
    private lateinit var vault: TokenVault
    private lateinit var page: LinearLayout
    private lateinit var accounts: AccountRepository
    private var peers = emptyList<MeshDevice>()
    private var busy = false
    private var accountStatus = ""
    private lateinit var connectionStatus: TextView
    private var screen = "home"
    private var tutorialStep = 0
    private var powerButton: Button? = null
    private val ink = Color.rgb(231, 239, 250)
    private val muted = Color.rgb(162, 181, 204)
    private val accent = Color.rgb(91, 221, 190)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun surface(color: Int, radius: Float = 20f) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius.toInt()).toFloat()
    }
    private fun navigate(destination: String) { screen = destination; render() }
    private val handler = Handler(Looper.getMainLooper())
    private val statusListener: (String) -> Unit = { message ->
        handler.post {
            if (!isDestroyed && ::connectionStatus.isInitialized) {
                connectionStatus.text = message
                updatePowerButton()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settings = SettingsStore(this)
        vault = TokenVault(this)
        accounts = AccountRepository(this)
        peers = runCatching { accounts.cachedMap()?.peers.orEmpty() }.getOrDefault(emptyList())
        screen = savedInstanceState?.getString("screen") ?: if (
            getPreferences(MODE_PRIVATE).getBoolean("welcomeSeen", false)
        ) "home" else "welcome"
        tutorialStep = savedInstanceState?.getInt("tutorialStep") ?: 0
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (screen != "home") navigate("home") else finish()
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        peers = runCatching { accounts.cachedMap()?.peers.orEmpty() }.getOrDefault(peers)
        render()
        ConnectionState.observe(statusListener)
        if (ConnectionState.running) startService(Intent(this, MeshVpnService::class.java).setAction(MeshVpnService.RECHECK))
    }
    override fun onPause() {
        ConnectionState.removeObserver(statusListener); handler.removeCallbacksAndMessages(null); super.onPause()
    }

    private fun text(value: String, size: Float = 16f) {
        page.addView(TextView(this).apply {
            text = value; textSize = size; setTextColor(if (size >= 22f) ink else muted)
            setPadding(0, dp(10), 0, dp(10))
            if (size >= 22f) setTypeface(null, Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
    }

    private fun button(label: String, action: () -> Unit) {
        page.addView(Button(this).apply {
            text = label; isAllCaps = false; textSize = 16f; setTextColor(ink)
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 91, 221, 190)),
                surface(Color.rgb(32, 51, 73)), null
            ); minHeight = dp(52)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isEnabled = !busy; alpha = if (busy) 0.5f else 1f
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) })
    }

    private fun render() {
        powerButton = null
        val spacing = (16 * resources.displayMetrics.density).toInt()
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(spacing, spacing, spacing, spacing * 2) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(12, 23, 38))
            isFillViewport = true
            addView(page)
            setOnApplyWindowInsetsListener { view, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
            requestApplyInsets()
        })
        if (screen == "welcome") { renderWelcome(); return }
        text(if (screen == "settings") "Settings" else "MeshHome", 30f)
        if (screen == "settings") button("‹ Back to home") { navigate("home") }
        connectionStatus = TextView(this).apply {
            text = ConnectionState.message; textSize = 20f; setTextColor(accent)
            gravity = Gravity.CENTER; setPadding(dp(16), dp(20), dp(16), dp(20))
            background = surface(Color.rgb(22, 39, 57))
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        page.addView(connectionStatus)
        if (screen == "home") { renderHome(); return }
        text(if (NativeTelio.available) "Native Meshnet engine installed (relay mode). Peer connectivity is shown separately from tunnel readiness."
            else "Native library is not installed for this CPU. Sign-in/settings work, but VPN connection is unavailable until a native build is installed.")
        text("Nord account", 22f)
        text(if (vault.hasToken) "Token stored encrypted. ${peers.size} devices in cached map." else "Not signed in.")
        if (accountStatus.isNotBlank()) text(accountStatus)
        button("Sign in with Nord token") { tokenDialog() }
        if (vault.hasToken) {
            button("Refresh Meshnet devices") { accountOperation { accounts.refresh(Networks.accountNetwork(this, settings)) } }
            button("Log out / remove this device") { logoutDialog() }
        }
        peers.forEach { text("${it.name} · ${(it.ipv4 + it.ipv6).joinToString()}") }
        text("Network policy", 22f)
        page.addView(Switch(this).apply {
            setTextColor(ink)
            minHeight = dp(56)
            text = getString(R.string.allow_mobile_data)
            isChecked = settings.mode == NetworkMode.ALL_NETWORKS
            setOnCheckedChangeListener { _, checked -> settings.mode = if (checked) NetworkMode.ALL_NETWORKS else NetworkMode.WIFI_ONLY }
        })
        text("Home Wi-Fi bypass", 22f)
        text("Meshnet pauses on these networks, restoring normal LAN routing and Pi-hole DNS. Wi-Fi names can be spoofed; this is not network authentication. If Android hides the Wi-Fi name, bypass is not applied.")
        settings.homeSsids.sorted().forEach { ssid -> button("Remove home network: $ssid") { settings.homeSsids = settings.homeSsids - ssid; render() } }
        button("Add current Wi-Fi as home") { addHomeWifi() }
        if (settings.homeSsids.isNotEmpty()) {
            val backgroundAllowed = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            text(if (backgroundAllowed) "Background Wi-Fi identification permission granted. Location services must also stay enabled."
                else "For automatic home bypass while this screen is closed, Android may require location access set to 'Allow all the time'. Without it, an unreadable SSID keeps Meshnet enabled.")
            button("Background home Wi-Fi permission") { backgroundWifiPermission() }
        }
        text("Selected apps (${settings.apps.size})", 22f)
        text("Only selected apps will use Meshnet. An empty selection will never connect. This picker currently lists launchable apps.")
        button("Choose apps") { appPicker() }
        text("Domain → Meshnet device", 22f)
        text("example.org matches only that name. *.example.org matches subdomains, not example.org. You can add multiple rules targeting different devices.")
        settings.rules.forEachIndexed { index, rule ->
            val device = peers.find { it.id == rule.deviceId }?.name ?: "Unavailable device (${rule.deviceId})"
            page.addView(CheckBox(this).apply {
                setTextColor(ink)
                buttonTintList = ColorStateList.valueOf(accent)
                text = getString(R.string.domain_target, rule.pattern.value, device)
                isChecked = rule.enabled
                setOnCheckedChangeListener { _, enabled -> safely {
                    settings.rules = settings.rules.mapIndexed { i, item -> if (i == index) item.copy(enabled = enabled) else item }
                } }
            })
            button("Edit ${rule.pattern.value}") { ruleDialog(index) }
            button("Remove ${rule.pattern.value}") { settings.rules = settings.rules.filterIndexed { i, _ -> i != index }; render() }
        }
        button("Add domain rule") { ruleDialog() }
        text("Non-matching DNS is forwarded as plaintext to the current network's DNS resolver unless overridden below. Apps with their own DoH/DoT can bypass overrides. Strict Android Private DNS must be disabled or set to automatic before connecting.")
        button("Upstream DNS: ${settings.upstreamDns.ifBlank { "network default" }}") { upstreamDialog() }
        text("MeshHome replaces other VPN apps in this Android profile. Always-on lockdown is not supported. DNS caches/existing connections may need time or an app restart after switching between home and away.")
        button("Open setup guide") { tutorialStep = 0; navigate("welcome") }
    }

    private fun renderHome() {
        text("Your services. One connection.", 22f)
        powerButton = Button(this).apply {
            textSize = 26f; isAllCaps = false; setTextColor(Color.rgb(12, 23, 38))
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 12, 23, 38)), surface(accent, 120f), null
            )
            setOnClickListener {
                if (ConnectionState.running) {
                    startService(Intent(this@MainActivity, MeshVpnService::class.java).setAction(MeshVpnService.STOP))
                } else connectVpn()
            }
        }
        page.addView(powerButton, LinearLayout.LayoutParams(dp(208), dp(208)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(24); bottomMargin = dp(24)
        })
        updatePowerButton()
        text("Only your selected apps and configured Meshnet targets use this connection. Peer status does not guarantee that a service is reachable.")
        text("Your setup", 22f)
        text("${if (vault.hasToken) "✓ Account signed in" else "○ Sign in to Nord"}\n" +
            "${settings.apps.size} selected apps · ${settings.rules.count { it.enabled }} enabled domain rules\n" +
            if (settings.mode == NetworkMode.WIFI_ONLY) "Wi-Fi only" else "Wi-Fi and cellular")
        if (!NativeTelio.available) text("Native engine missing: install a native-enabled build to connect.")
        if (!vault.hasToken || settings.apps.isEmpty() || settings.rules.none { it.enabled }) {
            text("Finish your account, app selection, and domain rules in Settings before connecting.")
        }
        button("Settings · apps, domains & account") { navigate("settings") }
        button("How to set up MeshHome") { tutorialStep = 0; navigate("welcome") }
    }

    private fun updatePowerButton() {
        powerButton?.apply {
            text = if (ConnectionState.running) "⏻\nDisconnect" else "⏻\nConnect"
            contentDescription = if (ConnectionState.running) "Disconnect Meshnet" else "Connect Meshnet"
            isEnabled = !busy || ConnectionState.running
        }
    }

    private fun renderWelcome() {
        val titles = listOf("Welcome to MeshHome", "Choose what connects", "Give services a name", "You're ready to begin")
        val details = listOf(
            "Reach your self-hosted services through Nord Meshnet. Start by signing in with a Nord Account access token in Settings. Your server must be online and allow incoming connections from this phone.",
            "Choose the apps that should use Meshnet, then choose Wi-Fi only or Wi-Fi and cellular. Other apps keep their normal network connection. Optional home Wi-Fi bypass pauses Meshnet at home.",
            "Add a domain such as photos.example.org and select its Meshnet device. Enter a hostname, not https:// or a URL path. *.example.org covers subdomains; add example.org separately for the root domain.",
            "Set Android Private DNS to Off or Automatic, and disable your selected browser's Secure DNS for overrides. Finish setup in Settings, return home, and tap the power button. Android will ask permission to create a VPN."
        )
        text("GETTING STARTED · ${tutorialStep + 1} OF 4")
        text(titles[tutorialStep], 30f)
        text(details[tutorialStep], 18f)
        text("MeshHome is experimental. It replaces any other VPN in this profile and routes only selected traffic.")
        button(if (tutorialStep == 3) "Open settings" else "Next →") {
            if (tutorialStep == 3) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("welcomeSeen", true).apply()
                navigate("settings")
            } else { tutorialStep++; render() }
        }
        if (tutorialStep > 0) button("Previous") { tutorialStep--; render() }
        button("Skip to home") {
            getPreferences(MODE_PRIVATE).edit().putBoolean("welcomeSeen", true).apply()
            navigate("home")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen)
        outState.putInt("tutorialStep", tutorialStep)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Platform back navigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (screen != "home") navigate("home") else super.onBackPressed()
    }

    private fun tokenDialog() {
        val input = EditText(this).apply {
            hint = "Nord account access token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
        }
        val dialog = AlertDialog.Builder(this).setTitle("Sign in to Nord Meshnet")
            .setMessage("Generate a token in Nord Account. Sign-in sends it over HTTPS to api.nordvpn.com and registers this phone. Renewing a token reuses the registration only if the new token can access it; log out to switch accounts. Credentials are stored encrypted locally.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Sign in", null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = input.text.toString().trim()
                if (token.isBlank()) { input.error = "Enter a token"; return@setOnClickListener }
                input.text.clear(); dialog.dismiss()
                accountOperation { accounts.login(token, Networks.accountNetwork(this, settings)) }
            }
        }
        dialog.setOnDismissListener { input.text.clear() }
        dialog.show()
    }

    private fun appPicker() {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val selected = settings.apps.toMutableSet()
        AlertDialog.Builder(this).setTitle("Apps allowed to use Meshnet")
            .setMultiChoiceItems(apps.map { "${it.loadLabel(packageManager)}\n${it.activityInfo.packageName}" }.toTypedArray(),
                apps.map { it.activityInfo.packageName in selected }.toBooleanArray()) { _, index, checked ->
                val id = apps[index].activityInfo.packageName
                if (checked) selected.add(id) else selected.remove(id)
            }.setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> settings.apps = selected; render() }.show()
    }

    private fun ruleDialog(index: Int? = null) {
        if (peers.isEmpty()) {
            AlertDialog.Builder(this).setTitle("No Meshnet devices yet")
                .setMessage("Sign in and refresh the device list before adding a domain mapping.")
                .setPositiveButton("OK", null).show()
            return
        }
        val old = index?.let { settings.rules[it] }
        val input = EditText(this).apply {
            hint = "photos.example.org"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(old?.pattern?.value ?: "")
        }
        val picker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, peers.map { "${it.name} (${it.id})" })
            setSelection(peers.indexOfFirst { it.id == old?.deviceId }.coerceAtLeast(0))
        }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(input); addView(picker) }
        val dialog = AlertDialog.Builder(this).setTitle("Domain → device")
            .setMessage("Enter a domain only, such as photos.example.org or *.example.org. Do not include https://, a port, or a path. Then choose the device hosting your service.")
            .setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { safely {
                val rule = DnsRule(DomainPattern.parse(input.text.toString()), peers[picker.selectedItemPosition].id, old?.enabled ?: true)
                val updated = settings.rules.toMutableList()
                if (index == null) updated.add(rule) else updated[index] = rule
                settings.rules = updated
                dialog.dismiss()
                render()
            } }
        }
        dialog.show()
    }

    private fun addHomeWifi() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1)
            toast("Grant precise location, enable location services, then try again. Used only to read the current Wi-Fi name.")
            return
        }
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            toast("Connect directly to your home Wi-Fi first (without another VPN).")
            return
        }
        @Suppress("DEPRECATION")
        val info = (capabilities.transportInfo as? WifiInfo)
            ?: (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo
        val ssid = info.ssid?.removeSurrounding("\"")
        if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") {
            toast("Wi-Fi name unavailable. Check precise location permission and location services.")
            return
        }
        AlertDialog.Builder(this).setTitle("Bypass Meshnet on $ssid?")
            .setMessage("Only add networks you control. Another access point can advertise the same Wi-Fi name.")
            .setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ -> settings.homeSsids = settings.homeSsids + ssid; render() }.show()
    }

    private fun safely(action: () -> Unit) {
        try { action() } catch (e: Exception) { toast(MeshVpnService.safeMessage(e)) }
    }

    private fun accountOperation(operation: () -> MeshMap) {
        if (busy) return
        busy = true; accountStatus = "Contacting Nord…"; render()
        Thread {
            val result = runCatching(operation)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess { peers = it.peers; accountStatus = "Device registered; peer map refreshed." }
                    .onFailure { accountStatus = MeshVpnService.safeMessage(it as? Exception ?: Exception()) }
                render()
            }
        }.start()
    }

    private fun logoutDialog() {
        AlertDialog.Builder(this).setTitle("Log out")
            .setMessage("Remove this phone from Nord Meshnet as well? Local-only logout leaves a device entry in your Nord account. Domain rules will keep their target IDs until you edit/remove them.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Local only") { _, _ -> logout(false) }
            .setPositiveButton("Remove device + log out") { _, _ -> logout(true) }.show()
    }

    private fun logout(remove: Boolean) {
        stopService(Intent(this, MeshVpnService::class.java))
        busy = true; accountStatus = "Logging out…"; render()
        Thread {
            val result = runCatching { accounts.logout(remove, if (remove) Networks.accountNetwork(this, settings) else null) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess { peers = emptyList(); accountStatus = "Logged out." }
                    .onFailure { accountStatus = MeshVpnService.safeMessage(it as? Exception ?: Exception()) }
                render()
            }
        }.start()
    }

    private fun connectVpn() = safely {
        check(NativeTelio.available) { "Build/install the native library first. See README.md." }
        check(vault.hasToken && peers.isNotEmpty()) { "Sign in and refresh Meshnet devices first." }
        check(settings.apps.isNotEmpty()) { "Select at least one app." }
        check(settings.rules.any { it.enabled }) { "Add and enable a domain-to-device rule." }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            toast("Allow notifications, then connect again for visible connection controls.")
            return@safely
        }
        val consent = VpnService.prepare(this)
        if (consent != null) @Suppress("DEPRECATION") startActivityForResult(consent, 100)
        else startForegroundService(Intent(this, MeshVpnService::class.java))
    }

    @Deprecated("Platform consent callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) startForegroundService(Intent(this, MeshVpnService::class.java))
    }

    private fun upstreamDialog() {
        val input = EditText(this).apply { hint = "Blank = network DNS; or enter a resolver IP"; setText(settings.upstreamDns) }
        val dialog = AlertDialog.Builder(this).setTitle("Non-matching domain resolver")
            .setMessage("Unmatched queries are sent directly over your permitted physical network. This is plaintext DNS; never enter a Meshnet-only DNS address here.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { safely {
            settings.upstreamDns = input.text.toString().trim(); dialog.dismiss(); render()
        } } }
        dialog.show()
    }

    private fun backgroundWifiPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1)
            toast("Grant precise location first, then use this option again.")
            return
        }
        AlertDialog.Builder(this).setTitle("Background Wi-Fi name access")
            .setMessage("Android treats Wi-Fi names as location-sensitive. Optional 'Allow all the time' access lets MeshHome recognize your configured home Wi-Fi while its screen is closed. MeshHome does not request GPS coordinates or send Wi-Fi names to Nord. You can decline; unreadable networks will never trigger home bypass.\n\nOn the next screen, choose Permissions → Location → Allow all the time. Keep device location services enabled.")
            .setNegativeButton("Not now", null).setPositiveButton("Continue") { _, _ ->
                if (android.os.Build.VERSION.SDK_INT == 29) requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 3)
                else startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }.show()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
