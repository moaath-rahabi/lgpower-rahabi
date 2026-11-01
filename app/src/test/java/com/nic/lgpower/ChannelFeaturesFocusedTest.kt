package com.nic.lgpower

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChannelFeaturesFocusedTest {

    @Test
    fun tvChannelParsingAndSwitchingUseOnlyReturnedIds() {
        val json = """
            {
                "type": "response",
                "id": "rahabi_channels",
                "payload": {
                    "channelList": [
                        {
                            "channelId": "sat-42",
                            "channelNumber": "42",
                            "channelName": "Yemen TV",
                            "channelType": "Satellite",
                            "isCurrent": true
                        },
                        {
                            "channelId": "cable-101",
                            "channelNumber": "101",
                            "channelName": "News 24",
                            "channelType": "Cable"
                        }
                    ]
                }
            }
        """.trimIndent()

        val parsed = ChannelProtocol.parseChannelList(json)
        assertEquals(2, parsed.channels.size)
        assertFalse(parsed.truncated)

        val ch1 = parsed.channels[0]
        assertEquals("sat-42", ch1.id)
        assertEquals("42", ch1.number)
        assertEquals("Yemen TV", ch1.name)
        assertEquals("Satellite", ch1.source)
        assertTrue(ch1.reportedCurrent)

        val ch2 = parsed.channels[1]
        assertEquals("cable-101", ch2.id)
        assertEquals("101", ch2.number)
        assertEquals("News 24", ch2.name)
        assertEquals("Cable", ch2.source)
        assertFalse(ch2.reportedCurrent)

        // openChannelRequest validation
        val reqString = ChannelProtocol.openChannelRequest(1, ch1, parsed.channels)
        val reqJson = JSONObject(reqString)
        assertEquals(ChannelProtocol.OPEN_CHANNEL_URI, reqJson.getString("uri"))
        assertEquals("sat-42", reqJson.getJSONObject("payload").getString("channelId"))

        // Unlisted channel ID throws
        val fakeChannel = TvChannel("unlisted-999", "999", "Fake Channel", "Satellite")
        assertTrue(runCatching { ChannelProtocol.openChannelRequest(2, fakeChannel, parsed.channels) }.isFailure)
    }

    @Test
    fun currentChannelParsingExtractsVerifiedDetails() {
        val currentJson = """
            {
                "type": "response",
                "id": "rahabi_current",
                "payload": {
                    "channel": {
                        "channelId": "sat-42",
                        "channelNumber": "42",
                        "channelName": "Yemen TV",
                        "channelType": "Satellite"
                    }
                }
            }
        """.trimIndent()

        val current = ChannelProtocol.parseCurrentChannel(currentJson)
        assertNotNull(current)
        assertEquals("sat-42", current?.id)
        assertEquals("42", current?.number)
        assertEquals("Yemen TV", current?.name)
        assertTrue(current?.reportedCurrent == true)
    }

    @Test
    fun numberNormalizationHandlesArabicAndEasternDigits() {
        // Arabic-Indic \u0661\u0662\u0663 -> 123
        assertEquals("123", ChannelProtocol.normalizeNumber("\u0661\u0662\u0663"))
        // Eastern-Arabic-Indic \u06f4\u06f5\u06f6\u06f7\u06f8 -> 45678
        assertEquals("45678", ChannelProtocol.normalizeNumber("\u06f4\u06f5\u06f6\u06f7\u06f8"))
        assertEquals("10", ChannelProtocol.normalizeNumber("10"))
        assertEquals("101", ChannelProtocol.normalizeNumber("101"))
        assertEquals("296", ChannelProtocol.normalizeNumber("296"))
        assertEquals("598", ChannelProtocol.normalizeNumber("598"))
        assertTrue(runCatching { ChannelProtocol.normalizeNumber("abc") }.isFailure)
        assertTrue(runCatching { ChannelProtocol.normalizeNumber("") }.isFailure)
        assertTrue(runCatching { ChannelProtocol.normalizeNumber("123456789") }.isFailure) // exceeds 8 digits
    }

    @Test
    fun channelCustomizationCodecRoundTripsCleanly() {
        val custom = ChannelCustomization(
            sourceKey = "SATELLITE",
            channelId = "sat-42",
            originalNumber = "42",
            originalName = "Yemen TV",
            displayName = "Yemen Official",
            shortcutNumber = "1",
            groups = setOf("Yemen", "News"),
            favorite = true,
            hidden = false,
            order = 0
        )

        val encoded = ChannelCustomizationCodec.encode(listOf(custom))
        val decoded = ChannelCustomizationCodec.decode(encoded)

        assertEquals(1, decoded.size)
        val result = decoded[0]
        assertEquals(custom.sourceKey, result.sourceKey)
        assertEquals(custom.channelId, result.channelId)
        assertEquals(custom.originalNumber, result.originalNumber)
        assertEquals(custom.originalName, result.originalName)
        assertEquals(custom.displayName, result.displayName)
        assertEquals(custom.shortcutNumber, result.shortcutNumber)
        assertEquals(custom.groups, result.groups)
        assertEquals(custom.favorite, result.favorite)
        assertEquals(custom.hidden, result.hidden)
    }

    @Test
    fun tvChannelCodecRoundTripsForLocalCaching() {
        val channels = listOf(
            TvChannel("sat-1", "1", "Channel One", "Satellite", false),
            TvChannel("sat-2", "2", "Channel Two", "Satellite", true)
        )

        val encoded = TvChannelCodec.encode(channels)
        val decoded = TvChannelCodec.decode(encoded)

        assertEquals(2, decoded.size)
        assertEquals("sat-1", decoded[0].id)
        assertEquals("Channel One", decoded[0].name)
        assertEquals("sat-2", decoded[1].id)
        assertTrue(decoded[1].reportedCurrent)
    }

    @Test
    fun favoriteCodecRoundTripsFavorites() {
        val favorites = listOf(
            FavoriteChannel("1", "Channel 1", "Yemen"),
            FavoriteChannel("2", "Channel 2", "News")
        )

        val encoded = FavoriteCodec.encode(favorites)
        val decoded = FavoriteCodec.decode(encoded)

        assertEquals(2, decoded.size)
        assertEquals("Channel 1", decoded[0].name)
        assertEquals("Yemen", decoded[0].group)
    }

    @Test
    fun shortcutResolverResolvesToRealTvChannelAndDetectsDuplicates() {
        val channels = listOf(
            TvChannel("sat-42", "42", "Yemen TV", "SATELLITE"),
            TvChannel("sat-50", "50", "Egypt TV", "SATELLITE")
        )

        val edits = listOf(
            ChannelCustomization("SATELLITE", "sat-42", "42", "Yemen TV", shortcutNumber = "1"),
            ChannelCustomization("SATELLITE", "sat-50", "50", "Egypt TV", shortcutNumber = "2")
        )

        val resolved = ChannelResolver.resolveShortcut("1", channels, edits, "SATELLITE")
        assertTrue(resolved is ShortcutResolution.Found)
        assertEquals("sat-42", (resolved as ShortcutResolution.Found).channel.id)

        // Missing shortcut
        val missing = ChannelResolver.resolveShortcut("99", channels, edits, "SATELLITE")
        assertTrue(missing is ShortcutResolution.Missing)

        // Multi-digit shortcut resolution (101, 296)
        val multiEdits = edits + listOf(
            ChannelCustomization("SATELLITE", "sat-42", "42", "Yemen TV", shortcutNumber = "101"),
            ChannelCustomization("SATELLITE", "sat-50", "50", "Egypt TV", shortcutNumber = "296")
        )
        val resolvedMulti = ChannelResolver.resolveShortcut("101", channels, multiEdits, "SATELLITE")
        assertTrue(resolvedMulti is ShortcutResolution.Found)
        assertEquals("sat-42", (resolvedMulti as ShortcutResolution.Found).channel.id)

        // Duplicate shortcut check
        val duplicateCandidate = ChannelCustomization("SATELLITE", "sat-99", "99", "Other", shortcutNumber = "1")
        assertTrue(ChannelResolver.hasDuplicateShortcut(duplicateCandidate, edits))

        val duplicateMultiCandidate = ChannelCustomization("SATELLITE", "sat-99", "99", "Other", shortcutNumber = "101")
        assertTrue(ChannelResolver.hasDuplicateShortcut(duplicateMultiCandidate, multiEdits))

        val uniqueCandidate = ChannelCustomization("SATELLITE", "sat-99", "99", "Other", shortcutNumber = "3")
        assertFalse(ChannelResolver.hasDuplicateShortcut(uniqueCandidate, edits))

        val uniqueMultiCandidate = ChannelCustomization("SATELLITE", "sat-99", "99", "Other", shortcutNumber = "598")
        assertFalse(ChannelResolver.hasDuplicateShortcut(uniqueMultiCandidate, multiEdits))
    }

    @Test
    fun presetGroupsIsEmptyByDefault() {
        assertTrue("Preset groups must be empty by default", ChannelProtocol.presetGroups.isEmpty())
    }

    @Test
    fun groupManagementSupportsCreateRenameDeleteAndUpdatesMemberships() {
        val fakePrefs = FakeSharedPreferences()
        val store = TvChannelStore(tvBinding = "test-tv", prefsOverride = fakePrefs)

        // Empty by default
        assertTrue(store.customGroups().isEmpty())

        // Create groups
        assertTrue(store.createGroup("News HD"))
        assertTrue(store.createGroup("Cinema"))
        assertEquals(listOf("News HD", "Cinema"), store.customGroups())

        // Duplicate rejection (case-insensitive and reserved)
        assertFalse(store.createGroup("news hd"))
        assertFalse(store.createGroup("ALL"))
        assertFalse(store.createGroup("FAVORITES"))
        assertFalse(store.createGroup(""))

        // Assign to channel customization
        val custom = ChannelCustomization(
            sourceKey = "SATELLITE",
            channelId = "sat-1",
            originalNumber = "1",
            originalName = "BBC",
            displayName = "BBC News",
            shortcutNumber = "1",
            groups = setOf("News HD", "Cinema")
        )
        store.saveChannelCustomizations(listOf(custom))

        // Rename group
        assertTrue(store.renameGroup("News HD", "World News"))
        assertEquals(listOf("World News", "Cinema"), store.customGroups())

        // Verify channel customization was updated with new group name
        val updatedCustoms = store.channelCustomizations()
        val ch1 = updatedCustoms["SATELLITE\u0000sat-1"]
        assertNotNull(ch1)
        assertEquals(setOf("World News", "Cinema"), ch1?.groups)
        assertEquals("BBC News", ch1?.displayName)

        // Delete group
        assertTrue(store.deleteGroup("World News"))
        assertEquals(listOf("Cinema"), store.customGroups())

        // Verify group was stripped from channel, but channel customization preserved
        val afterDeleteCustoms = store.channelCustomizations()
        val ch1After = afterDeleteCustoms["SATELLITE\u0000sat-1"]
        assertNotNull(ch1After)
        assertEquals(setOf("Cinema"), ch1After?.groups)
        assertEquals("BBC News", ch1After?.displayName)
    }

    @Test
    fun legacyPresetPruningRemovesUnusedAndKeepsActive() {
        val fakePrefs = FakeSharedPreferences()
        val store = TvChannelStore(tvBinding = "test-tv", prefsOverride = fakePrefs)

        // Legacy state with preset groups
        store.saveCustomGroups(listOf("Yemen", "Egypt", "Sports", "CustomGroup"))

        // Assign channel to "Yemen" only
        val custom = ChannelCustomization(
            sourceKey = "SATELLITE",
            channelId = "sat-1",
            originalNumber = "1",
            originalName = "Yemen TV",
            groups = setOf("Yemen")
        )
        store.saveChannelCustomizations(listOf(custom))

        // Run pruning
        val pruned = store.pruneLegacyPresets()
        assertTrue(pruned)

        // "Sports" and "Egypt" (unused presets) pruned, "Yemen" (used) and "CustomGroup" (user) kept
        val remaining = store.customGroups()
        assertEquals(listOf("Yemen", "CustomGroup"), remaining)
    }

    @Test
    fun perChannelResetAndGlobalResetWorkSafely() {
        val fakePrefs = FakeSharedPreferences()
        val store = TvChannelStore(tvBinding = "test-tv", prefsOverride = fakePrefs)

        val ch1 = ChannelCustomization("SATELLITE", "sat-1", "1", "CH1", displayName = "Custom 1", groups = setOf("G1"))
        val ch2 = ChannelCustomization("SATELLITE", "sat-2", "2", "CH2", displayName = "Custom 2", groups = setOf("G1"))
        store.saveChannelCustomizations(listOf(ch1, ch2))
        store.saveCustomGroups(listOf("G1"))

        // Reset channel 1
        assertTrue(store.resetChannel("SATELLITE", "sat-1"))
        val customsAfterResetOne = store.channelCustomizations()
        assertNull(customsAfterResetOne["SATELLITE\u0000sat-1"])
        assertNotNull(customsAfterResetOne["SATELLITE\u0000sat-2"])

        // Reset all customizations without deleting group definitions
        assertTrue(store.resetAllCustomizations(deleteGroupDefinitions = false))
        assertTrue(store.channelCustomizations().isEmpty())
        assertEquals(listOf("G1"), store.customGroups())

        // Reset all including group definitions
        assertTrue(store.resetAllCustomizations(deleteGroupDefinitions = true))
        assertTrue(store.customGroups().isEmpty())
    }

    private class FakeSharedPreferences : android.content.SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (data[key] as? Set<*>)?.map { it.toString() }?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val backing: MutableMap<String, Any?>) : android.content.SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearPending = false

            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { if (key != null) pending[key] = values; return this }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { if (key != null) removed.add(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { clearPending = true; return this }
            override fun commit(): Boolean {
                if (clearPending) backing.clear()
                removed.forEach { backing.remove(it) }
                backing.putAll(pending)
                return true
            }
            override fun apply() { commit() }
        }
    }
}
