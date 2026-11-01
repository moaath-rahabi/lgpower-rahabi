package com.nic.lgpower

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class TvChannel(
    val id: String?,
    val number: String,
    val name: String,
    val source: String? = null,
    val reportedCurrent: Boolean = false
)

data class FavoriteChannel(
    val number: String,
    val name: String,
    val group: String
)

data class ParsedChannelList(
    val channels: List<TvChannel>,
    val truncated: Boolean
)

data class ChannelCustomization(
    val sourceKey: String,
    val channelId: String,
    val originalNumber: String,
    val originalName: String,
    val displayName: String = "",
    val shortcutNumber: String = "",
    val groups: Set<String> = emptySet(),
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    val order: Int = 0
) {
    val stableKey: String get() = "$sourceKey\u0000$channelId"
}

object FavoriteCodec {
    fun encode(favorites: List<FavoriteChannel>): String {
        require(favorites.size <= 200)
        val array = JSONArray()
        favorites.forEach { favorite ->
            require(ChannelProtocol.normalizeNumber(favorite.number).isNotEmpty())
            require(favorite.name.isNotBlank() && favorite.name.length <= 80)
            require(favorite.group.isNotBlank() && favorite.group.length <= 40)
            array.put(JSONObject().put("number", favorite.number).put("name", favorite.name).put("group", favorite.group))
        }
        return array.toString()
    }

    fun decode(source: String): List<FavoriteChannel> = runCatching {
        val array = JSONArray(source)
        buildList {
            for (index in 0 until minOf(array.length(), 200)) {
                val item = array.optJSONObject(index) ?: continue
                val number = item.optString("number")
                val name = item.optString("name").take(80)
                val group = item.optString("group").take(40)
                if (runCatching { ChannelProtocol.normalizeNumber(number) }.isSuccess && name.isNotBlank() && group.isNotBlank()) {
                    add(FavoriteChannel(number, name, group))
                }
            }
        }
    }.getOrDefault(emptyList())
}

object ChannelProtocol {
    const val CHANNEL_LIST_URI = "ssap://tv/getChannelList"
    const val OPEN_CHANNEL_URI = "ssap://tv/openChannel"
    const val CURRENT_CHANNEL_URI = "ssap://tv/getCurrentChannel"
    const val CHANNEL_LIST_REQUEST_ID = "rahabi_channel_list_once"
    const val CURRENT_CHANNEL_REQUEST_ID = "rahabi_current_channel_once"
    const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    const val MAX_CHANNELS = 10000
    val presetGroups = emptyList<String>()

    fun parseChannelList(json: String): ParsedChannelList {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES)
        val payload = JSONObject(json).optJSONObject("payload") ?: JSONObject(json)
        val rows = payload.optJSONArray("channelList") ?: payload.optJSONArray("channels") ?: JSONArray()
        val seen = mutableSetOf<String>()
        val channels = buildList {
            for (index in 0 until minOf(rows.length(), MAX_CHANNELS)) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("channelId").ifBlank { row.optString("channelID").ifBlank { row.optString("id") } }
                    .takeIf { it.isNotBlank() && it.length <= 200 }
                val number = row.optString("channelNumber").ifBlank { row.optString("number") }.trim().take(24)
                val name = row.optString("channelName").ifBlank { row.optString("name") }.trim().take(100)
                val stableKey = id ?: "$number\u0000$name"
                if ((id != null || number.isNotBlank()) && stableKey !in seen) {
                    seen += stableKey
                    val source = row.optString("channelType").ifBlank { row.optString("source") }.takeIf(String::isNotBlank)
                    add(TvChannel(id, number, name.ifBlank { number.ifBlank { "Channel" } }, source,
                        if (row.has("isCurrent")) row.optBoolean("isCurrent") else false))
                }
            }
        }
        return ParsedChannelList(channels, rows.length() > MAX_CHANNELS)
    }

    fun openChannelRequest(requestId: Int, channel: TvChannel, currentList: List<TvChannel>): String {
        require(requestId > 0)
        val id = channel.id?.takeIf { it.length in 1..200 && currentList.any { current -> current.id == it } }
            ?: throw IllegalArgumentException("Channel ID was not returned in the current channel list")
        return JSONObject().put("type", "request").put("id", requestId).put("uri", OPEN_CHANNEL_URI)
            .put("payload", JSONObject().put("channelId", id)).toString()
    }

    fun channelListRequest(requestId: String = CHANNEL_LIST_REQUEST_ID): String = JSONObject()
        .put("type", "request").put("id", requestId)
        .put("uri", CHANNEL_LIST_URI).put("payload", JSONObject()).toString()

    fun currentChannelRequest(requestId: String = CURRENT_CHANNEL_REQUEST_ID): String = JSONObject()
        .put("type", "request").put("id", requestId)
        .put("uri", CURRENT_CHANNEL_URI).put("payload", JSONObject()).toString()

    fun parseCurrentChannel(json: String): TvChannel? {
        require(json.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        val payload = JSONObject(json).optJSONObject("payload") ?: JSONObject(json)
        val row = payload.optJSONObject("channel") ?: payload.optJSONObject("currentChannel") ?: payload
        val id = row.optString("channelId").ifBlank { row.optString("channelID").ifBlank { row.optString("id") } }
            .takeIf { it.isNotBlank() && it.length <= 200 }
        val number = row.optString("channelNumber").ifBlank { row.optString("number") }.trim().take(24)
        val name = row.optString("channelName").ifBlank { row.optString("name") }.trim().take(100)
        if (number.isBlank() && name.isBlank() && id.isNullOrBlank()) return null
        return TvChannel(id, number, name.ifBlank { number.ifBlank { "Channel" } }, row.optString("channelType").takeIf(String::isNotBlank), true)
    }

    fun normalizeNumber(number: String): String {
        val normalized = buildString {
            number.forEach { char -> append(when (char) {
                in '\u0660'..'\u0669' -> ('0'.code + char.code - '\u0660'.code).toChar()
                in '\u06f0'..'\u06f9' -> ('0'.code + char.code - '\u06f0'.code).toChar()
                else -> char
            }) }
        }
        require(normalized.length in 1..8 && Regex("^\\d{1,8}$").matches(normalized))
        return normalized
    }
}

object ChannelCustomizationCodec {
    private const val MAX_EDIT_BYTES = 8 * 1024 * 1024
    fun encode(values: Collection<ChannelCustomization>): String {
        require(values.size <= ChannelProtocol.MAX_CHANNELS)
        val array = JSONArray()
        values.forEach { value ->
            require(value.sourceKey.length in 1..80 && value.channelId.length in 1..200)
            require(value.originalNumber.length <= 24 && value.originalName.length <= 100)
            require(value.displayName.length <= 100 && value.shortcutNumber.length <= 8)
            require(value.groups.size <= 40 && value.groups.all { it.isNotBlank() && it.length <= 40 })
            if (value.shortcutNumber.isNotBlank()) ChannelProtocol.normalizeNumber(value.shortcutNumber)
            array.put(JSONObject().put("source", value.sourceKey).put("id", value.channelId)
                .put("number", value.originalNumber).put("name", value.originalName)
                .put("alias", value.displayName).put("shortcut", value.shortcutNumber)
                .put("groups", JSONArray(value.groups.sorted())).put("favorite", value.favorite)
                .put("hidden", value.hidden).put("order", value.order))
        }
        return array.toString()
    }

    fun decode(source: String): List<ChannelCustomization> = runCatching {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_EDIT_BYTES)
        val array = JSONArray(source)
        buildList {
            for (i in 0 until minOf(array.length(), ChannelProtocol.MAX_CHANNELS)) {
                val row = array.optJSONObject(i) ?: continue
                val sourceKey = row.optString("source").take(80)
                val id = row.optString("id").take(200)
                if (sourceKey.isBlank() || id.isBlank()) continue
                val groupsJson = row.optJSONArray("groups") ?: JSONArray()
                val groups = buildSet { for (g in 0 until minOf(groupsJson.length(), 40)) groupsJson.optString(g).take(40).takeIf(String::isNotBlank)?.let(::add) }
                val shortcut = row.optString("shortcut").take(8)
                if (shortcut.isNotBlank() && runCatching { ChannelProtocol.normalizeNumber(shortcut) }.isFailure) continue
                add(ChannelCustomization(sourceKey, id, row.optString("number").take(24), row.optString("name").take(100),
                    row.optString("alias").take(100), shortcut, groups, row.optBoolean("favorite"), row.optBoolean("hidden"), row.optInt("order")))
            }
        }
    }.getOrDefault(emptyList())
}

object TvChannelCodec {
    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
    fun encode(channels: List<TvChannel>): String {
        require(channels.size <= ChannelProtocol.MAX_CHANNELS)
        val rows = JSONArray()
        channels.forEach { channel ->
            val id = channel.id ?: return@forEach
            require(id.length in 1..200)
            rows.put(JSONObject().put("id", id).put("number", channel.number.take(24)).put("name", channel.name.take(100))
                .put("source", channel.source?.take(80) ?: JSONObject.NULL).put("current", channel.reportedCurrent))
        }
        return rows.toString()
    }

    fun decode(json: String): List<TvChannel> = runCatching {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_CACHE_BYTES)
        val rows = JSONArray(json)
        buildList {
            val seen = mutableSetOf<String>()
            for (i in 0 until minOf(rows.length(), ChannelProtocol.MAX_CHANNELS)) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optString("id").take(200)
                if (id.isNotBlank() && seen.add(id)) {
                    add(TvChannel(id, row.optString("number").take(24), row.optString("name").take(100),
                        row.optString("source").takeIf { it.isNotBlank() && it != "null" }?.take(80),
                        row.optBoolean("current")))
                }
            }
        }
    }.getOrDefault(emptyList())
}

sealed class ShortcutResolution {
    data class Found(val channel: TvChannel) : ShortcutResolution()
    object Missing : ShortcutResolution()
    object Ambiguous : ShortcutResolution()
}

object ChannelResolver {
    fun resolveShortcut(number: String, currentChannels: List<TvChannel>, edits: Collection<ChannelCustomization>, sourceKey: String = "*"): ShortcutResolution {
        val normalized = runCatching { ChannelProtocol.normalizeNumber(number) }.getOrNull() ?: return ShortcutResolution.Missing
        val matches = edits.filter { (sourceKey == "*" || it.sourceKey == sourceKey) && it.shortcutNumber == normalized }
        if (matches.isEmpty()) return ShortcutResolution.Missing
        if (matches.size > 1) return ShortcutResolution.Ambiguous
        val edit = matches.single()
        val liveChannel = currentChannels.singleOrNull { it.id == edit.channelId && (it.source ?: "LIVE_TV").trim().uppercase().take(80) == edit.sourceKey }
            ?: return ShortcutResolution.Missing
        return ShortcutResolution.Found(liveChannel)
    }

    fun hasDuplicateShortcut(candidate: ChannelCustomization, edits: Collection<ChannelCustomization>): Boolean =
        candidate.shortcutNumber.isNotBlank() && edits.any { it.stableKey != candidate.stableKey && it.sourceKey == candidate.sourceKey && it.shortcutNumber == candidate.shortcutNumber }
}

class TvChannelStore(context: Context? = null, val tvBinding: String, prefsOverride: SharedPreferences? = null) {
    private val preferences = prefsOverride ?: context!!.applicationContext.getSharedPreferences("rahabi_tv_channels_v1", Context.MODE_PRIVATE)
    private val prefix = MessageDigest.getInstance("SHA-256")
        .digest(tvBinding.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun cachedChannels(): List<TvChannel> =
        TvChannelCodec.decode(preferences.getString("channel_cache_$prefix", "[]") ?: "[]")

    fun saveCachedChannels(channels: List<TvChannel>): Boolean =
        preferences.edit().putString("channel_cache_$prefix", TvChannelCodec.encode(channels)).commit()

    fun channelCustomizations(): Map<String, ChannelCustomization> =
        ChannelCustomizationCodec.decode(
            preferences.getString("channel_edits_$prefix", "[]") ?: "[]"
        ).associateBy { it.stableKey }

    fun saveChannelCustomizations(values: Collection<ChannelCustomization>): Boolean =
        preferences.edit().putString("channel_edits_$prefix", ChannelCustomizationCodec.encode(values)).commit()

    fun customGroups(): List<String> = runCatching {
        val array = JSONArray(preferences.getString("groups_$prefix", "[]"))
        buildList {
            for (i in 0 until minOf(array.length(), MAX_GROUPS)) {
                array.optString(i).trim().take(40).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    fun saveCustomGroups(groups: List<String>): Boolean {
        val normalized = groups.map(String::trim).filter(String::isNotBlank).distinct().take(MAX_GROUPS)
        require(normalized.all { it.length <= 40 })
        return preferences.edit().putString("groups_$prefix", JSONArray(normalized).toString()).commit()
    }

    fun createGroup(name: String): Boolean {
        val trimmed = name.trim().take(40)
        if (trimmed.isBlank()) return false
        if (trimmed.equals("ALL", ignoreCase = true) || trimmed.equals("FAVORITES", ignoreCase = true)) return false
        val current = customGroups().toMutableList()
        if (current.any { it.equals(trimmed, ignoreCase = true) }) return false
        if (current.size >= MAX_GROUPS) return false
        current.add(trimmed)
        return saveCustomGroups(current)
    }

    fun renameGroup(oldName: String, newName: String): Boolean {
        val trimmedNew = newName.trim().take(40)
        if (trimmedNew.isBlank()) return false
        if (trimmedNew.equals("ALL", ignoreCase = true) || trimmedNew.equals("FAVORITES", ignoreCase = true)) return false
        val current = customGroups().toMutableList()
        val index = current.indexOfFirst { it.equals(oldName, ignoreCase = true) }
        if (index == -1) return false
        if (!oldName.equals(trimmedNew, ignoreCase = true) && current.any { it.equals(trimmedNew, ignoreCase = true) }) return false
        current[index] = trimmedNew
        val groupsSaved = saveCustomGroups(current)

        val edits = channelCustomizations().values.map { custom ->
            if (oldName in custom.groups || custom.groups.any { it.equals(oldName, ignoreCase = true) }) {
                val updatedGroups = custom.groups.map { if (it.equals(oldName, ignoreCase = true)) trimmedNew else it }.toSet()
                custom.copy(groups = updatedGroups)
            } else {
                custom
            }
        }
        val editsSaved = saveChannelCustomizations(edits)
        return groupsSaved && editsSaved
    }

    fun deleteGroup(name: String): Boolean {
        val current = customGroups().toMutableList()
        val removed = current.removeAll { it.equals(name, ignoreCase = true) }
        if (!removed) return false
        val groupsSaved = saveCustomGroups(current)

        val edits = channelCustomizations().values.map { custom ->
            if (name in custom.groups || custom.groups.any { it.equals(name, ignoreCase = true) }) {
                val updatedGroups = custom.groups.filterNot { it.equals(name, ignoreCase = true) }.toSet()
                custom.copy(groups = updatedGroups)
            } else {
                custom
            }
        }
        val editsSaved = saveChannelCustomizations(edits)
        return groupsSaved && editsSaved
    }

    fun pruneLegacyPresets(): Boolean {
        val legacyPresets = setOf("Yemen", "Egypt", "News", "Islamic", "Sports", "Kids")
        val currentGroups = customGroups()
        if (currentGroups.none { it in legacyPresets }) return false

        val customizations = channelCustomizations().values
        val activelyUsedGroups = customizations.flatMap { it.groups }.toSet()

        val toKeep = currentGroups.filter { group ->
            group !in legacyPresets || group in activelyUsedGroups
        }
        return if (toKeep != currentGroups) {
            saveCustomGroups(toKeep)
        } else {
            false
        }
    }

    fun resetChannel(sourceKey: String, channelId: String): Boolean {
        val key = "$sourceKey\u0000$channelId"
        val altKey = "*\u0000$channelId"
        val current = channelCustomizations().toMutableMap()
        val removed = current.remove(key) != null || current.remove(altKey) != null
        return if (removed) {
            saveChannelCustomizations(current.values)
        } else {
            true
        }
    }

    fun resetAllCustomizations(deleteGroupDefinitions: Boolean = false): Boolean {
        val editsCleared = preferences.edit().putString("channel_edits_$prefix", "[]").commit()
        val groupsCleared = if (deleteGroupDefinitions) {
            preferences.edit().putString("groups_$prefix", "[]").commit()
        } else {
            true
        }
        return editsCleared && groupsCleared
    }

    private companion object {
        const val MAX_GROUPS = 40
    }
}
