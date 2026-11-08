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
    private var connectionHint: TextView? = null
    private val ink = Color.rgb(231, 239, 250)
    private val muted = Color.rgb(162, 181, 204)
    private val accent = Color.rgb(91, 221, 190)
    private val base = Color.rgb(11, 18, 30)
    private val panel = Color.rgb(22, 33, 49)
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

    private fun button(label: String, primary: Boolean = false, action: () -> Unit) {
        page.addView(Button(this).apply {
            text = label; isAllCaps = false; textSize = 15f; setTextColor(if (primary) base else ink)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 91, 221, 190)),
                surface(if (primary) accent else Color.rgb(35, 49, 68), 14f), null
            ); minHeight = dp(52)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isEnabled = !busy; alpha = if (busy) 0.5f else 1f
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) })
    }

    private fun card(title: String? = null, content: () -> Unit) {
        val parent = page
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(panel)
            setPadding(dp(18), dp(12), dp(18), dp(18))
        }
        parent.addView(container, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        page = container
        if (title != null) text(title, 22f)
        content()
        page = parent
    }

    private fun shortcut(title: String, detail: String, destination: String) {
        page.addView(TextView(this).apply {
            text = "$title  ›\n$detail"
            textSize = 16f; setTextColor(ink); setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x225bddbe), surface(panel), null)
            isFocusable = true
            setOnClickListener { navigate(destination) }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun navigation(): LinearLayout = LinearLayout(this).apply {
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(panel)
        listOf("home" to "Connect", "devices" to "Devices", "routing" to "Routing", "settings" to "Settings").forEach { (route, label) ->
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER
                val selected = screen == route
                setTextColor(if (selected) accent else muted)
                setCompoundDrawablesWithIntrinsicBounds(null, MeshIcon(route, if (selected) accent else muted, dp(23)), null, null)
                compoundDrawablePadding = dp(5)
                setPadding(dp(2), dp(10), dp(2), dp(10))
                background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x225bddbe), surface(if (selected) Color.rgb(30, 59, 62) else panel, 14f), null)
                isSelected = selected; isFocusable = true
                contentDescription = "$label${if (selected) ", selected" else ""}"
                setOnClickListener { if (screen != route) navigate(route) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun render() {
        powerButton = null
        connectionHint = null
        val spacing = dp(20)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(spacing, spacing, spacing, spacing * 2) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(base)
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
        }
        root.addView(ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (screen != "welcome") root.addView(navigation())
        setContentView(root)
        root.requestApplyInsets()
        if (screen == "welcome") { renderWelcome(); return }
        text("M E S H H O M E", 12f)
        text(when (screen) { "devices" -> "Your devices"; "routing" -> "Your traffic"; "settings" -> "Settings"; else -> "Closer to home." }, 32f)
        connectionStatus = TextView(this).apply {
            text = ConnectionState.message; textSize = 15f; setTextColor(accent)
            gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12))
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        if (screen == "home") { renderHome(); return }
        when (screen) {
            "devices" -> renderDevices()
            "routing" -> renderRouting()
            else -> renderSettings()
        }
    }

    private fun renderDevices() {
        text("Your private network, in one place.")
        card("Nord account") {
        text(if (NativeTelio.available) "Native Meshnet engine installed (relay mode). Peer connectivity is shown separately from tunnel readiness."
            else "Native library is not installed for this CPU. Sign-in/settings work, but VPN connection is unavailable until a native build is installed.")
        text(if (vault.hasToken) "Token stored encrypted. ${peers.size} devices in cached map." else "Not signed in.")
        if (accountStatus.isNotBlank()) text(accountStatus)
        button(if (vault.hasToken) "Update Nord token" else "Sign in with Nord token", primary = !vault.hasToken) { tokenDialog() }
        if (vault.hasToken) {
            button("Refresh Meshnet devices") { accountOperation { accounts.refresh(Networks.accountNetwork(this, settings)) } }
            button("Log out / remove this device") { logoutDialog() }
        }
        }
        text("Meshnet devices · ${peers.size}", 22f)
        if (peers.isEmpty()) card {
            text("No devices yet", 22f)
            text("Sign in, then refresh to find the devices in your Meshnet account.")
        }
        peers.forEach { device -> card(device.name) {
            text((device.ipv4 + device.ipv6).joinToString("\n"))
            text("Saved in your Meshnet account", 12f)
        } }
    }

    private fun renderSettings() {
        text("Make MeshHome fit your day.")
        shortcut("Nord account", if (vault.hasToken) "Signed in · ${peers.size} saved devices" else "Sign in to get started", "devices")
        card("Network policy") {
        page.addView(Switch(this).apply {
            setTextColor(ink)
            minHeight = dp(56)
            text = getString(R.string.allow_mobile_data)
            isChecked = settings.mode == NetworkMode.ALL_NETWORKS
            setOnCheckedChangeListener { _, checked -> settings.mode = if (checked) NetworkMode.ALL_NETWORKS else NetworkMode.WIFI_ONLY }
        })
        text("Choose whether MeshHome can connect away from Wi-Fi.")
        }
        card("Home Wi-Fi bypass") {
        text("Meshnet pauses on these networks, restoring normal LAN routing and Pi-hole DNS. Wi-Fi names can be spoofed; this is not network authentication. If Android hides the Wi-Fi name, bypass is not applied.")
        settings.homeSsids.sorted().forEach { ssid -> button("Remove home network: $ssid") { settings.homeSsids = settings.homeSsids - ssid; render() } }
        button("Add current Wi-Fi as home") { addHomeWifi() }
        if (settings.homeSsids.isNotEmpty()) {
            val backgroundAllowed = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            text(if (backgroundAllowed) "Background Wi-Fi identification permission granted. Location services must also stay enabled."
                else "For automatic home bypass while this screen is closed, Android may require location access set to 'Allow all the time'. Without it, an unreadable SSID keeps Meshnet enabled.")
            button("Background home Wi-Fi permission") { backgroundWifiPermission() }
        }
        }
        card("Help & setup") {
            text("MeshHome replaces other VPN apps in this Android profile. Always-on lockdown is not supported.")
            button("Open setup guide") { tutorialStep = 0; navigate("welcome") }
        }
        card("Settings backup") {
            text("Export apps, domain routes, network policy, home Wi-Fi and DNS as an editable JSON text file. Nord tokens and private keys are never exported.")
            button("Export settings") {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "meshhome-settings.json")
                }, 201)
            }
            button("Import settings") {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }, 202)
            }
            text("To sign in during import, manually add a top-level \"nordToken\": \"YOUR_TOKEN\" field. Treat that file as a secret and remove the token afterward. Import replaces settings; device IDs must match your Meshnet account.", 13f)
        }
    }

    private fun renderRouting() {
        text("Choose the apps and services that travel through Meshnet.")
        card("Selected apps · ${settings.apps.size}") {
        text("Only selected apps will use Meshnet. An empty selection will never connect. This picker currently lists launchable apps.")
        button("Choose apps") { appPicker() }
        }
        text("Domain routes", 22f)
        text("example.org matches only that name. *.example.org matches subdomains, not example.org. You can add multiple rules targeting different devices.")
        settings.rules.forEachIndexed { index, rule ->
            card {
            val device = peers.find { it.id == rule.deviceId }?.name ?: "Unavailable device (${rule.deviceId})"
            page.addView(CheckBox(this).apply {
                setTextColor(ink)
                buttonTintList = ColorStateList.valueOf(accent)
                minHeight = dp(48)
                text = getString(R.string.domain_target, rule.pattern.value, device)
                isChecked = rule.enabled
                setOnCheckedChangeListener { _, enabled -> safely {
                    settings.rules = settings.rules.mapIndexed { i, item -> if (i == index) item.copy(enabled = enabled) else item }
                } }
            })
            button("Edit ${rule.pattern.value}") { ruleDialog(index) }
            button("Remove ${rule.pattern.value}") { settings.rules = settings.rules.filterIndexed { i, _ -> i != index }; render() }
            }
        }
        if (settings.rules.isEmpty()) card {
            text("A shortcut to your services", 22f)
            text("Map a domain like photos.example.org to the Meshnet device hosting it.")
        }
        button("+ Add domain rule", primary = true) { ruleDialog() }
        card("DNS resolver") {
        text("Non-matching DNS is forwarded as plaintext to the current network's DNS resolver unless overridden below. Apps with their own DoH/DoT can bypass overrides. Strict Android Private DNS must be disabled or set to automatic before connecting.")
        button("Upstream DNS: ${settings.upstreamDns.ifBlank { "network default" }}") { upstreamDialog() }
        }
    }

    private fun renderHome() {
        text("Your services. Your private connection.")
        card {
        page.addView(connectionStatus)
        powerButton = Button(this).apply {
            textSize = 18f; isAllCaps = false; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(35), dp(24), dp(24))
            compoundDrawablePadding = dp(12)
            setOnClickListener {
                if (ConnectionState.running) {
                    startService(Intent(this@MainActivity, MeshVpnService::class.java).setAction(MeshVpnService.STOP))
                } else connectVpn()
            }
        }
        page.addView(powerButton, LinearLayout.LayoutParams(dp(184), dp(184)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16); bottomMargin = dp(16)
        })
        connectionHint = TextView(this).apply {
            textSize = 14f; setTextColor(muted); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        page.addView(connectionHint)
        updatePowerButton()
        text(if (settings.mode == NetworkMode.WIFI_ONLY) "WI-FI ONLY  ·  SPLIT TUNNEL" else "WI-FI + CELLULAR  ·  SPLIT TUNNEL", 12f)
        }
        if (!NativeTelio.available) text("Native engine missing: install a native-enabled build to connect.")
        val ready = vault.hasToken && settings.apps.isNotEmpty() && settings.rules.any { it.enabled }
        text(if (ready) "Your connection setup" else "Let's get you connected", 22f)
        shortcut("Meshnet devices", if (vault.hasToken) "${peers.size} saved devices · manage account" else "Start here · sign in to Nord", "devices")
        shortcut("Apps & domain routes", "${settings.apps.size} apps · ${settings.rules.count { it.enabled }} enabled routes", "routing")
        shortcut("Network preferences", if (settings.mode == NetworkMode.WIFI_ONLY) "Wi-Fi only · manage home bypass" else "Wi-Fi and cellular · manage home bypass", "settings")
        text("Only selected apps and configured targets use Meshnet. Peer connectivity does not guarantee service reachability.", 13f)
    }

    private fun updatePowerButton() {
        powerButton?.apply {
            val active = ConnectionState.running
            text = if (active) "Disconnect" else "Connect"
            setTextColor(if (active) accent else base)
            setCompoundDrawablesWithIntrinsicBounds(null, MeshIcon("home", if (active) accent else base, dp(42)), null, null)
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x335bddbe),
                surface(if (active) Color.rgb(27, 62, 61) else accent, 120f).apply { setStroke(dp(3), accent) }, null
            )
            contentDescription = if (ConnectionState.running) "Disconnect Meshnet" else "Connect Meshnet"
            isEnabled = !busy || ConnectionState.running
        }
        connectionHint?.text = if (ConnectionState.running) "Tap to stop your Meshnet session" else "Tap to reach your home services"
    }

    private fun renderWelcome() {
        val titles = listOf("Welcome to MeshHome", "Choose what connects", "Give services a name", "You're ready to begin")
        val details = listOf(
            "Reach your self-hosted services through Nord Meshnet. Start by signing in with a Nord Account access token in Devices. Your server must be online and allow incoming connections from this phone.",
            "Choose the apps that should use Meshnet, then choose Wi-Fi only or Wi-Fi and cellular. Other apps keep their normal network connection. Optional home Wi-Fi bypass pauses Meshnet at home.",
            "Add a domain such as photos.example.org and select its Meshnet device. Enter a hostname, not https:// or a URL path. *.example.org covers subdomains; add example.org separately for the root domain.",
            "Set Android Private DNS to Off or Automatic, and disable your selected browser's Secure DNS for overrides. Choose your apps and domain routes in Routing, then open Connect and tap the power button. Android will ask permission to create a VPN."
        )
        text("GETTING STARTED · ${tutorialStep + 1} OF 4")
        page.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 4; progress = tutorialStep + 1
            progressTintList = ColorStateList.valueOf(accent)
        }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(16); bottomMargin = dp(24) })
        card {
        text(titles[tutorialStep], 30f)
        text(details[tutorialStep], 18f)
        }
        text("MeshHome is experimental. It replaces any other VPN in this profile and routes only selected traffic.")
        button(if (tutorialStep == 3) "Set up my account" else "Next →", primary = true) {
            if (tutorialStep == 3) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("welcomeSeen", true).apply()
                navigate("devices")
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
    // API 33+ uses OnBackInvokedDispatcher registered in onCreate; this is
    // retained only for Android 10–12, which do not expose that dispatcher.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (android.os.Build.VERSION.SDK_INT >= 33) return
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
        val labels = apps.associate { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
        var visibleApps = apps
        val count = TextView(this).apply {
            setTextColor(accent); textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun updateCount() { count.text = "${selected.size} selected · ${visibleApps.size} apps shown" }
        val search = EditText(this).apply {
            hint = "Search apps"; textSize = 16f
            setSingleLine(true); setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT
            background = surface(panel, 14f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            contentDescription = "Search apps by name or package"
        }
        class AppRow : LinearLayout(this@MainActivity) {
            val icon = ImageView(this@MainActivity).apply { importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val name = TextView(this@MainActivity).apply { textSize = 16f; setTextColor(ink); setTypeface(null, Typeface.BOLD) }
            val packageLabel = TextView(this@MainActivity).apply {
                textSize = 12f; setTextColor(muted); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val check = CheckBox(this@MainActivity).apply {
                buttonTintList = ColorStateList.valueOf(accent)
                isClickable = false; isFocusable = false
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            init {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(14), dp(8), dp(14))
                minimumHeight = dp(80)
                addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(name); addView(packageLabel)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14); rightMargin = dp(8) })
                addView(check)
            }
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = visibleApps.size
            override fun getItem(position: Int) = visibleApps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                val app = getItem(position)
                val packageId = app.activityInfo.packageName
                return ((convertView as? AppRow) ?: AppRow()).apply {
                    name.text = labels[packageId]; packageLabel.text = packageId
                    icon.setImageDrawable(runCatching { app.loadIcon(packageManager) }.getOrElse { packageManager.defaultActivityIcon })
                    check.isChecked = packageId in selected
                    background = android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(0x225bddbe),
                        surface(if (check.isChecked) Color.rgb(27, 55, 57) else panel, 14f), null
                    )
                    contentDescription = "${labels[packageId]}, ${if (check.isChecked) "selected" else "not selected"}"
                }
            }
        }
        val empty = TextView(this).apply {
            text = "No apps found. Try another name."
            setTextColor(muted); gravity = Gravity.CENTER; setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        val list = ListView(this).apply {
            this.adapter = adapter
            divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT); dividerHeight = dp(8)
            setOnItemClickListener { _, _, position, _ ->
                val id = visibleApps[position].activityInfo.packageName
                if (!selected.add(id)) selected.remove(id)
                adapter.notifyDataSetChanged(); updateCount()
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = "Only selected apps use Meshnet. Everything else keeps its normal connection."
                textSize = 14f; setTextColor(muted); setPadding(0, 0, 0, dp(16))
            })
            addView(search); addView(count)
            addView(FrameLayout(this@MainActivity).apply {
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(empty, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        list.emptyView = empty
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                visibleApps = apps.filter {
                    it.activityInfo.packageName.contains(query, ignoreCase = true) ||
                        labels.getValue(it.activityInfo.packageName).contains(query, ignoreCase = true)
                }
                adapter.notifyDataSetChanged(); updateCount()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        updateCount()
        val dialog = AlertDialog.Builder(this).setTitle("Choose your apps")
            .setView(content).setNegativeButton("Cancel", null)
            .setPositiveButton("Save selection") { _, _ -> settings.apps = selected; render() }.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(surface(base, 24f))
            setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), (resources.displayMetrics.heightPixels * 0.85f).toInt())
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
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
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 4)
            return
        }
        if (!getSystemService(android.location.LocationManager::class.java).isLocationEnabled) {
            AlertDialog.Builder(this).setTitle("Turn on Location to read Wi-Fi")
                .setMessage("Precise location permission is granted, but Android also requires the phone's Location setting to be on to reveal the Wi-Fi name. Enable it, return here, and tap Add current Wi-Fi as home again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open Location settings") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .show()
            return
        }
        val manager = getSystemService(ConnectivityManager::class.java)
        // The default network may be our VPN. Find the physical Wi-Fi instead.
        @Suppress("DEPRECATION")
        val capabilities = manager.allNetworks.mapNotNull { manager.getNetworkCapabilities(it) }
            .firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        if (capabilities == null) {
            toast("Connect to your home Wi-Fi, then try again.")
            return
        }
        // Capability snapshots can contain a non-null but redacted WifiInfo.
        // Read fresh permission-filtered Wi-Fi data rather than stopping at it.
        @Suppress("DEPRECATION")
        val currentInfo = (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo
        val ssid = listOf(currentInfo, capabilities.transportInfo as? WifiInfo)
            .mapNotNull { it?.ssid?.removeSurrounding("\"") }
            .firstOrNull { it.isNotBlank() && it != android.net.wifi.WifiManager.UNKNOWN_SSID }
        if (ssid == null) {
            toast("Wi-Fi name unavailable. Check precise location permission and location services.")
            return
        }
        if (ssid in settings.homeSsids) {
            toast("This Wi-Fi is already saved as a home network.")
            return
        }
        AlertDialog.Builder(this).setTitle("Bypass Meshnet on $ssid?")
            .setMessage("Only add networks you control. Another access point can advertise the same Wi-Fi name.")
            .setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ ->
                settings.homeSsids = settings.homeSsids + ssid
                render()
                toast("Home Wi-Fi added.")
                if (ConnectionState.running) startService(Intent(this, MeshVpnService::class.java).setAction(MeshVpnService.RECHECK))
            }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 4) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            addHomeWifi()
        } else {
            AlertDialog.Builder(this).setTitle("Precise location is needed")
                .setMessage("Android uses this permission to reveal your Wi-Fi name. Allow precise location in app permissions, then try adding your home Wi-Fi again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("App permissions") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                }.show()
        }
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
        if (resultCode == RESULT_OK && requestCode in 201..202) {
            val uri = data?.data ?: return
            if (requestCode == 201) {
                try {
                    val text = settings.backup().exportText()
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    toast("Settings exported. No credentials included.")
                } catch (_: Exception) { toast("Could not export settings to this file.") }
            } else {
                val backup = try {
                    val bytes = checkNotNull(contentResolver.openInputStream(uri)).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 1_048_576)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    SettingsBackup.parse(bytes.toString(Charsets.UTF_8))
                } catch (_: Exception) {
                    toast("Invalid or unreadable settings file. No settings changed."); return
                }
                AlertDialog.Builder(this).setTitle("Import settings?")
                    .setMessage("Replace your settings with ${backup.apps.size} apps, ${backup.rules.size} domain routes and ${backup.homeSsids.size} home networks? Missing apps and device IDs may need updating. Disconnect first if a VPN session is running." +
                        if (backup.nordToken != null) "\n\nThis file contains a Nord token. Import will send it over HTTPS to Nord to sign in and register this phone. Existing credentials are not exported." else "\n\nYour current account will not change.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Import") { _, _ ->
                        if (ConnectionState.running) {
                            toast("Disconnect Meshnet before importing settings.")
                        } else {
                            settings.restore(backup)
                            render()
                            if (backup.nordToken != null) accountOperation {
                                accounts.login(backup.nordToken, Networks.accountNetwork(this, settings))
                            } else toast("Settings imported.")
                        }
                    }.show()
            }
            return
        }
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
