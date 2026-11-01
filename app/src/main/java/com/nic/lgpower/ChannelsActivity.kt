package com.nic.lgpower

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ChannelsActivity : AppCompatActivity() {

    private lateinit var client: WebOsClient
    private lateinit var store: TvChannelStore
    private lateinit var adapter: ChannelsAdapter

    private var allChannels: MutableList<TvChannel> = mutableListOf()
    private var customizations: MutableMap<String, ChannelCustomization> = mutableMapOf()
    private var customGroups: MutableList<String> = mutableListOf()

    private var selectedGroup: String = "ALL"
    private var searchQuery: String = ""
    private var currentChannelId: String? = null

    private lateinit var loadingBar: ProgressBar
    private lateinit var countText: TextView
    private lateinit var emptyText: TextView
    private lateinit var chipsContainer: LinearLayout
    private lateinit var emptyGroupsBanner: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var clearSearchBtn: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channels)

        client = WebOsClient(this)
        val tvKey = client.tvMac.ifBlank { client.tvIp }.ifBlank { "default_tv" }
        store = TvChannelStore(this, tvKey)

        initViews()
        applyTheme()

        loadLocalData()
        buildGroupChips()
        applyFilters()

        checkCurrentChannel()
        if (allChannels.isEmpty()) {
            fetchChannels(forced = false)
        }

        if (intent.getBooleanExtra("focus_search", false)) {
            searchInput.post {
                searchInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btn_back_channels).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_refresh_channels).setOnClickListener { fetchChannels(forced = true) }
        findViewById<ImageButton>(R.id.btn_manage_channels).setOnClickListener { showManageMenu() }

        loadingBar = findViewById(R.id.channels_loading)
        countText = findViewById(R.id.tv_channels_count)
        emptyText = findViewById(R.id.channels_empty)
        chipsContainer = findViewById(R.id.group_chips_container)
        emptyGroupsBanner = findViewById(R.id.empty_groups_banner)
        searchInput = findViewById(R.id.edit_channel_search)
        clearSearchBtn = findViewById(R.id.btn_clear_search)

        findViewById<Button>(R.id.btn_create_first_group)?.setOnClickListener {
            promptNewGroup()
        }

        clearSearchBtn.setOnClickListener {
            searchInput.setText("")
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                clearSearchBtn.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val recycler = findViewById<RecyclerView>(R.id.channels_recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ChannelsAdapter(
            onChannelClick = { channel -> tuneToChannel(channel) },
            onFavoriteToggle = { channel -> toggleFavorite(channel) },
            onMenuClick = { channel -> showChannelEditor(channel) }
        )
        recycler.adapter = adapter
    }

    private fun applyTheme() {
        val theme = ThemeManager.getActiveTheme(this)
        ThemeManager.applyToRoot(findViewById(R.id.channels_root), theme)
        window.statusBarColor = theme.windowBg
        window.navigationBarColor = theme.windowBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val flags = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (theme.statusBarLightIcons)
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    private fun loadLocalData() {
        store.pruneLegacyPresets()
        allChannels = store.cachedChannels().toMutableList()
        customizations = store.channelCustomizations().toMutableMap()
        customGroups = store.customGroups().toMutableList()
    }

    private fun checkCurrentChannel() {
        Thread {
            val current = runCatching { client.getCurrentChannel() }.getOrNull()
            if (current != null && !current.id.isNullOrBlank()) {
                runOnUiThread {
                    currentChannelId = current.id
                    adapter.setCurrentChannel(current.id)
                }
            }
        }.start()
    }

    private fun fetchChannels(forced: Boolean) {
        loadingBar.visibility = View.VISIBLE
        countText.text = if (forced) "Syncing from TV…" else "Connecting to TV…"

        Thread {
            val list = runCatching { client.getChannelList(timeoutSecs = 12) }.getOrNull()
            val current = runCatching { client.getCurrentChannel(timeoutSecs = 4) }.getOrNull()

            runOnUiThread {
                loadingBar.visibility = View.GONE
                if (!list.isNullOrEmpty()) {
                    allChannels = list.toMutableList()
                    store.saveCachedChannels(allChannels)
                    if (current != null && !current.id.isNullOrBlank()) {
                        currentChannelId = current.id
                    }
                    buildGroupChips()
                    applyFilters()
                    Toast.makeText(this, "Loaded ${list.size} channels", Toast.LENGTH_SHORT).show()
                } else {
                    if (allChannels.isEmpty()) {
                        countText.text = "Could not reach TV"
                        Toast.makeText(this, "Could not retrieve channel list. Is TV on?", Toast.LENGTH_LONG).show()
                    } else {
                        countText.text = "${allChannels.size} cached channels (TV unreachable)"
                        Toast.makeText(this, "TV unreachable, using cached channels", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun applyFilters() {
        val query = searchQuery.lowercase()
        val normalizedQuery = runCatching { ChannelProtocol.normalizeNumber(searchQuery) }.getOrNull()

        val filtered = allChannels.filter { channel ->
            val key = "${(channel.source ?: "LIVE_TV").trim().uppercase()}\u0000${channel.id ?: ""}"
            val custom = customizations[key] ?: customizations["*\u0000${channel.id ?: ""}"]

            if (query.isEmpty() && custom?.hidden == true) {
                return@filter false
            }

            when (selectedGroup) {
                "ALL" -> true
                "FAVORITES" -> custom?.favorite == true
                else -> custom != null && selectedGroup in custom.groups
            }.takeIf { it } ?: return@filter false

            if (query.isNotEmpty()) {
                val nameMatches = (custom?.displayName?.lowercase()?.contains(query) == true) ||
                        channel.name.lowercase().contains(query)
                val numberMatches = channel.number.contains(query) ||
                        (normalizedQuery != null && channel.number.contains(normalizedQuery))
                val shortcutMatches = custom?.shortcutNumber?.contains(query) == true ||
                        (normalizedQuery != null && custom?.shortcutNumber?.contains(normalizedQuery) == true)
                nameMatches || numberMatches || shortcutMatches
            } else {
                true
            }
        }

        adapter.updateData(filtered, customizations, currentChannelId)

        countText.text = if (filtered.size == allChannels.size) {
            "${allChannels.size} channels"
        } else {
            "${filtered.size} of ${allChannels.size} channels"
        }

        emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun tuneToChannel(channel: TvChannel) {
        val channelId = channel.id
        if (channelId.isNullOrBlank()) {
            Toast.makeText(this, "Channel identifier missing", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Tuning to ${channel.number} ${channel.name}…", Toast.LENGTH_SHORT).show()
        Thread {
            val res = runCatching { client.openChannel(channelId) }.getOrNull()
            runOnUiThread {
                if (res is WebOsClient.Result.Success) {
                    currentChannelId = channelId
                    adapter.setCurrentChannel(channelId)
                } else if (res is WebOsClient.Result.Error) {
                    Toast.makeText(this, "Tune error: ${res.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun toggleFavorite(channel: TvChannel) {
        val sourceKey = (channel.source ?: "LIVE_TV").trim().uppercase()
        val channelId = channel.id ?: return
        val key = "$sourceKey\u0000$channelId"

        val current = customizations[key] ?: ChannelCustomization(
            sourceKey = sourceKey,
            channelId = channelId,
            originalNumber = channel.number,
            originalName = channel.name
        )

        val updated = current.copy(favorite = !current.favorite)
        customizations[key] = updated
        store.saveChannelCustomizations(customizations.values)
        buildGroupChips()
        applyFilters()
    }

    private fun buildGroupChips() {
        chipsContainer.removeAllViews()
        val theme = ThemeManager.getActiveTheme(this)
        val density = resources.displayMetrics.density

        val favCount = customizations.values.count { it.favorite }
        val allGroups = mutableListOf(
            "ALL" to "All (${allChannels.size})",
            "FAVORITES" to "★ Favorites ($favCount)"
        )

        customGroups.forEach { groupName ->
            val count = customizations.values.count { groupName in it.groups }
            allGroups.add(groupName to "$groupName ($count)")
        }

        allGroups.forEach { (groupId, label) ->
            val isSelected = selectedGroup == groupId
            val button = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                val hPad = (14 * density).toInt()
                val vPad = (6 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                minHeight = (34 * density).toInt()
                minWidth = 0

                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 17f * density
                    if (isSelected) {
                        setColor(theme.btnAccentBg)
                    } else {
                        setColor(theme.surfaceBg)
                        setStroke((1 * density).toInt(), theme.btnGhostBorder)
                    }
                }
                background = bg
                setTextColor(if (isSelected) theme.btnAccentText else theme.primaryText)

                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                }
                layoutParams = lp

                setOnClickListener {
                    selectedGroup = groupId
                    buildGroupChips()
                    applyFilters()
                }
            }
            chipsContainer.addView(button)
        }

        // "+ Group" chip
        val addBtn = Button(this).apply {
            text = "+ Group"
            textSize = 12f
            isAllCaps = false
            val hPad = (12 * density).toInt()
            val vPad = (6 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            minHeight = (34 * density).toInt()
            minWidth = 0

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 17f * density
                setColor(0)
                setStroke((1 * density).toInt(), theme.btnGhostBorder)
            }
            setTextColor(theme.secondaryText)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            layoutParams = lp

            setOnClickListener { promptNewGroup() }
        }
        chipsContainer.addView(addBtn)

        emptyGroupsBanner.visibility = if (customGroups.isEmpty() && searchQuery.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun promptNewGroup(onCreated: ((String) -> Unit)? = null) {
        val theme = ThemeManager.getActiveTheme(this)
        val density = resources.displayMetrics.density

        val dialog = Dialog(this)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        dialog.setOnShowListener {
            val metrics = resources.displayMetrics
            val dialogWidth = (metrics.widthPixels * 0.90).toInt().coerceAtMost((460 * density).toInt())
            dialog.window?.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * density).toInt(), (20 * density).toInt(), (22 * density).toInt(), (18 * density).toInt())
            background = GradientDrawable().apply {
                setColor(theme.surfaceBg)
                cornerRadius = 20f * density
                setStroke((1 * density).toInt(), theme.btnGhostBorder)
            }
        }

        root.addView(TextView(this).apply {
            text = "Create Channel Group"
            textSize = 18f
            setTextColor(theme.primaryText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (12 * density).toInt()
            }
        })

        val input = EditText(this).apply {
            hint = "e.g. Cinema, Sports, News"
            setHintTextColor(theme.secondaryText)
            setTextColor(theme.primaryText)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = GradientDrawable().apply {
                setColor(theme.windowBg)
                cornerRadius = 10f * density
            }
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (18 * density).toInt()
            }
        }
        root.addView(input)

        val doCreate = {
            val name = input.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(this@ChannelsActivity, "Group name cannot be empty", Toast.LENGTH_SHORT).show()
            } else if (store.createGroup(name)) {
                loadLocalData()
                buildGroupChips()
                onCreated?.invoke(name)
                dialog.dismiss()
                Toast.makeText(this@ChannelsActivity, "Group \"$name\" created", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ChannelsActivity, "Group already exists or name invalid", Toast.LENGTH_SHORT).show()
            }
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                doCreate()
                true
            } else false
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        btnRow.addView(Button(this).apply {
            text = "Cancel"
            textSize = 14f
            setTextColor(theme.secondaryText)
            background = null
            setOnClickListener { dialog.dismiss() }
        })

        btnRow.addView(Button(this).apply {
            text = "Create"
            textSize = 14f
            setTextColor(theme.btnAccentText)
            background = GradientDrawable().apply {
                setColor(theme.btnAccentBg)
                cornerRadius = 10f * density
            }
            val hp = (18 * density).toInt()
            val vp = (10 * density).toInt()
            setPadding(hp, vp, hp, vp)
            setOnClickListener { doCreate() }
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun promptRenameGroup(oldName: String, onRenamed: (() -> Unit)? = null) {
        val theme = ThemeManager.getActiveTheme(this)
        val density = resources.displayMetrics.density

        val dialog = Dialog(this)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        dialog.setOnShowListener {
            val metrics = resources.displayMetrics
            val dialogWidth = (metrics.widthPixels * 0.90).toInt().coerceAtMost((460 * density).toInt())
            dialog.window?.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * density).toInt(), (20 * density).toInt(), (22 * density).toInt(), (18 * density).toInt())
            background = GradientDrawable().apply {
                setColor(theme.surfaceBg)
                cornerRadius = 20f * density
                setStroke((1 * density).toInt(), theme.btnGhostBorder)
            }
        }

        root.addView(TextView(this).apply {
            text = "Rename Group"
            textSize = 18f
            setTextColor(theme.primaryText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (12 * density).toInt()
            }
        })

        val input = EditText(this).apply {
            setText(oldName)
            setSelection(oldName.length)
            setHintTextColor(theme.secondaryText)
            setTextColor(theme.primaryText)
            textSize = 15f
            isSingleLine = true
            background = GradientDrawable().apply {
                setColor(theme.windowBg)
                cornerRadius = 10f * density
            }
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (18 * density).toInt()
            }
        }
        root.addView(input)

        val doRename = {
            val newName = input.text.toString().trim()
            if (newName.isNotBlank()) {
                if (store.renameGroup(oldName, newName)) {
                    if (selectedGroup == oldName) selectedGroup = newName
                    loadLocalData()
                    buildGroupChips()
                    applyFilters()
                    onRenamed?.invoke()
                    dialog.dismiss()
                    Toast.makeText(this@ChannelsActivity, "Group renamed to \"$newName\"", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ChannelsActivity, "Group name already exists or invalid", Toast.LENGTH_SHORT).show()
                }
            }
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                doRename()
                true
            } else false
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        btnRow.addView(Button(this).apply {
            text = "Cancel"
            textSize = 14f
            setTextColor(theme.secondaryText)
            background = null
            setOnClickListener { dialog.dismiss() }
        })

        btnRow.addView(Button(this).apply {
            text = "Rename"
            textSize = 14f
            setTextColor(theme.btnAccentText)
            background = GradientDrawable().apply {
                setColor(theme.btnAccentBg)
                cornerRadius = 10f * density
            }
            val hp = (18 * density).toInt()
            val vp = (10 * density).toInt()
            setPadding(hp, vp, hp, vp)
            setOnClickListener { doRename() }
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun showManageMenu() {
        val options = arrayOf(
            "Manage Groups (${customGroups.size})",
            "Reset All Customizations"
        )
        AlertDialog.Builder(this)
            .setTitle("Manage Channels")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showManageGroupsDialog()
                    1 -> showResetAllCustomizationsDialog()
                }
            }
            .show()
    }

    private fun showManageGroupsDialog() {
        val theme = ThemeManager.getActiveTheme(this)
        val density = resources.displayMetrics.density

        val dialog = Dialog(this)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.65f)
        }

        dialog.setOnShowListener {
            val metrics = resources.displayMetrics
            val dialogWidth = (metrics.widthPixels * 0.94).toInt().coerceAtMost((560 * density).toInt())
            dialog.window?.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (20 * density).toInt(), (20 * density).toInt(), (20 * density).toInt())
            background = GradientDrawable().apply {
                setColor(theme.surfaceBg)
                cornerRadius = 20f * density
                setStroke((1 * density).toInt(), theme.btnGhostBorder)
            }
        }

        // Header: Title + Close Button
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (14 * density).toInt()
            }
        }

        header.addView(TextView(this).apply {
            text = "Manage Groups"
            textSize = 18f
            setTextColor(theme.primaryText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        header.addView(ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt())
            setImageResource(R.drawable.ic_close)
            background = null
            setColorFilter(theme.secondaryText)
            contentDescription = "Close"
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)

        // Groups container scrollable
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun populateList() {
            listContainer.removeAllViews()
            if (customGroups.isEmpty()) {
                listContainer.addView(TextView(this).apply {
                    text = "No groups yet. Create a group to organize your channels."
                    setTextColor(theme.secondaryText)
                    textSize = 14f
                    val pad = (16 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                    gravity = Gravity.CENTER
                })
            } else {
                customGroups.forEach { groupName ->
                    val count = customizations.values.count { groupName in it.groups }
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val vPad = (8 * density).toInt()
                        setPadding(0, vPad, 0, vPad)
                    }

                    val infoCol = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    infoCol.addView(TextView(this).apply {
                        text = groupName
                        setTextColor(theme.primaryText)
                        textSize = 15f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    infoCol.addView(TextView(this).apply {
                        text = "$count channels"
                        setTextColor(theme.secondaryText)
                        textSize = 12f
                    })
                    row.addView(infoCol)

                    // Rename button
                    row.addView(Button(this).apply {
                        text = "Rename"
                        textSize = 12f
                        setTextColor(theme.secondaryText)
                        background = null
                        minHeight = (44 * density).toInt()
                        setOnClickListener {
                            promptRenameGroup(groupName) {
                                populateList()
                            }
                        }
                    })

                    // Delete button
                    row.addView(Button(this).apply {
                        text = "Delete"
                        textSize = 12f
                        setTextColor(Color.parseColor("#FF453A"))
                        background = null
                        minHeight = (44 * density).toInt()
                        setOnClickListener {
                            AlertDialog.Builder(this@ChannelsActivity)
                                .setTitle("Delete Group")
                                .setMessage("Delete group \"$groupName\"? Channels in this group will not be deleted.")
                                .setPositiveButton("Delete") { _, _ ->
                                    store.deleteGroup(groupName)
                                    if (selectedGroup == groupName) selectedGroup = "ALL"
                                    loadLocalData()
                                    buildGroupChips()
                                    applyFilters()
                                    populateList()
                                    Toast.makeText(this@ChannelsActivity, "Group \"$groupName\" deleted", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    })

                    listContainer.addView(row)
                }
            }
        }

        populateList()
        scroll.addView(listContainer)
        root.addView(scroll)

        // Footer "+ Create Group"
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (16 * density).toInt()
            }
        }

        footer.addView(Button(this).apply {
            text = "+ Create Group"
            textSize = 14f
            setTextColor(theme.btnAccentText)
            background = GradientDrawable().apply {
                setColor(theme.btnAccentBg)
                cornerRadius = 10f * density
            }
            val hp = (18 * density).toInt()
            val vp = (10 * density).toInt()
            setPadding(hp, vp, hp, vp)
            minHeight = (44 * density).toInt()
            setOnClickListener {
                promptNewGroup {
                    populateList()
                }
            }
        })
        root.addView(footer)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun showResetAllCustomizationsDialog() {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, (10 * density).toInt(), pad, 0)
        }

        val message = TextView(this).apply {
            text = "This will clear all custom channel names, shortcut numbers, favorites, hidden states, and channel-group assignments.\n\nYour TV channel list and remote pairing will remain untouched.\n\n(Local Rahabi settings only — does not affect your TV database)."
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (14 * density).toInt()
            }
        }
        container.addView(message)

        val chkDeleteGroups = CheckBox(this).apply {
            text = "Also delete all custom group definitions"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = false
            minHeight = (48 * density).toInt()
        }
        container.addView(chkDeleteGroups)

        AlertDialog.Builder(this)
            .setTitle("Reset All Customizations?")
            .setView(container)
            .setPositiveButton("Reset All") { _, _ ->
                val deleteGroups = chkDeleteGroups.isChecked
                store.resetAllCustomizations(deleteGroupDefinitions = deleteGroups)
                selectedGroup = "ALL"
                loadLocalData()
                buildGroupChips()
                applyFilters()
                Toast.makeText(this, "All channel customizations have been reset", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChannelEditor(channel: TvChannel) {
        val theme = ThemeManager.getActiveTheme(this)
        val density = resources.displayMetrics.density
        val sourceKey = (channel.source ?: "LIVE_TV").trim().uppercase()
        val channelId = channel.id ?: return
        val key = "$sourceKey\u0000$channelId"

        val current = customizations[key] ?: ChannelCustomization(
            sourceKey = sourceKey,
            channelId = channelId,
            originalNumber = channel.number,
            originalName = channel.name
        )

        val dialog = Dialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_channel_editor, null)
        ThemeManager.applyToRoot(view, theme)

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.65f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        dialog.setOnShowListener {
            val metrics = resources.displayMetrics
            val dialogWidth = (metrics.widthPixels * 0.94).toInt().coerceAtMost((560 * metrics.density).toInt())
            dialog.window?.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val btnClose = view.findViewById<ImageButton>(R.id.btn_editor_close)
        val txtSourceBadge = view.findViewById<TextView>(R.id.txt_edit_source_badge)
        val txtOrigNumber = view.findViewById<TextView>(R.id.txt_edit_orig_number)
        val txtOrigName = view.findViewById<TextView>(R.id.txt_edit_orig_name)
        val editCustomName = view.findViewById<EditText>(R.id.edit_custom_name)
        val editCustomShortcut = view.findViewById<EditText>(R.id.edit_custom_shortcut)
        val txtShortcutWarn = view.findViewById<TextView>(R.id.txt_shortcut_warning)
        val chkFav = view.findViewById<CheckBox>(R.id.chk_favorite)
        val chkHidden = view.findViewById<CheckBox>(R.id.chk_hidden)
        val btnAddGroup = view.findViewById<Button>(R.id.btn_editor_add_group)
        val txtNoGroups = view.findViewById<TextView>(R.id.txt_no_groups_editor)
        val groupsContainer = view.findViewById<LinearLayout>(R.id.groups_checkbox_container)
        val btnReset = view.findViewById<Button>(R.id.btn_editor_reset)
        val btnCancel = view.findViewById<Button>(R.id.btn_editor_cancel)
        val btnSave = view.findViewById<Button>(R.id.btn_editor_save)

        txtSourceBadge.text = (channel.source ?: "TV").trim().uppercase()
        txtOrigNumber.text = "CH ${channel.number}"
        txtOrigName.text = channel.name
        editCustomName.setText(current.displayName)
        editCustomShortcut.setText(current.shortcutNumber)
        chkFav.isChecked = current.favorite
        chkHidden.isChecked = current.hidden

        btnClose.setOnClickListener { dialog.dismiss() }

        // Live duplicate shortcut check
        editCustomShortcut.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val candidate = current.copy(shortcutNumber = s?.toString()?.trim() ?: "")
                val isDup = ChannelResolver.hasDuplicateShortcut(candidate, customizations.values)
                txtShortcutWarn.visibility = if (isDup) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val selectedGroups = current.groups.toMutableSet()

        fun refreshGroupsList() {
            groupsContainer.removeAllViews()
            val groupsToShow = (customGroups + selectedGroups).distinct()
            if (groupsToShow.isEmpty()) {
                txtNoGroups.visibility = View.VISIBLE
            } else {
                txtNoGroups.visibility = View.GONE
                groupsToShow.forEach { groupName ->
                    val cb = CheckBox(this).apply {
                        text = groupName
                        isChecked = groupName in selectedGroups
                        setTextColor(theme.primaryText)
                        textSize = 14f
                        minHeight = (48 * density).toInt()
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) selectedGroups.add(groupName) else selectedGroups.remove(groupName)
                        }
                    }
                    groupsContainer.addView(cb)
                }
            }
        }

        refreshGroupsList()

        btnAddGroup.setOnClickListener {
            promptNewGroup { newGroup ->
                selectedGroups.add(newGroup)
                refreshGroupsList()
            }
        }

        btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Channel Customizations?")
                .setMessage("Reset customizations for CH ${channel.number} (${channel.name}) back to TV defaults? TV data will not be changed.")
                .setPositiveButton("Reset") { _, _ ->
                    store.resetChannel(sourceKey, channelId)
                    loadLocalData()
                    buildGroupChips()
                    applyFilters()
                    dialog.dismiss()
                    Toast.makeText(this, "Channel reset to TV defaults", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val alias = editCustomName.text.toString().trim()
            val rawShortcut = editCustomShortcut.text.toString().trim()
            val shortcut = if (rawShortcut.isNotEmpty()) {
                runCatching { ChannelProtocol.normalizeNumber(rawShortcut) }.getOrDefault(rawShortcut)
            } else ""

            val updated = current.copy(
                displayName = alias,
                shortcutNumber = shortcut,
                favorite = chkFav.isChecked,
                hidden = chkHidden.isChecked,
                groups = selectedGroups
            )

            customizations[key] = updated
            store.saveChannelCustomizations(customizations.values)
            buildGroupChips()
            applyFilters()
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }
}
