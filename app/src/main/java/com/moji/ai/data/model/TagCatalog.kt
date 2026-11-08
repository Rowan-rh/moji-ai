package com.moji.ai.data.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
data class TagDef(
    val name: String,
    val colorHex: Long = TagCatalog.DEFAULT_TAG_COLOR_HEX,
    val groupName: String = "日常"
)

data class TagColorOption(
    val colorHex: Long,
    val label: String
)

data class TagRemapRecord(
    val seq: Long,
    val oldTag: String,
    val newTag: String?
)

/**
 * 响应式持久化标签中心（对标 iCost 标签管理体系）：
 * - 内置 10 种 iCost 莫奈柔和色卡与 8 个常用預設标签；
 * - 支持自定义标签名称、主题色、所属分组（日常 / 差旅报销 / 生活事件 / 自定义）与排序；
 * - 提供多标签精确 Token 解析、拼接、重映射及表单反例校验；
 * - 支持自动扫描并合并存量历史账单与智能规则中的标签，同时支持对「仅从列表移除但保留在历史账单中」的标签抑制自动重扫。
 */
object TagCatalog {
    private const val PREFS_NAME = "moji_tag_catalog_prefs"
    private const val KEY_CUSTOM_TAGS_JSON = "custom_tags_json"
    private const val KEY_MUTED_DISCOVERED_TAGS_JSON = "muted_discovered_tags_json"

    const val DEFAULT_TAG_COLOR_HEX: Long = 0xFFE65540L
    const val SYSTEM_BALANCE_ANCHOR_TAG: String = "平账"

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val presetGroups: List<String> = listOf("日常", "差旅报销", "生活事件", "自定义")

    /**
     * 10 种 iCost 莫奈柔和色卡
     */
    val presetColors: List<TagColorOption> = listOf(
        TagColorOption(0xFFE65540L, "珊瑚红"),
        TagColorOption(0xFFF59E0BL, "琥珀金"),
        TagColorOption(0xFF22B573L, "森系绿"),
        TagColorOption(0xFF1677FFL, "晴空蓝"),
        TagColorOption(0xFF6366F1L, "鸢尾紫"),
        TagColorOption(0xFFEC4899L, "山茶粉"),
        TagColorOption(0xFF14B8A6L, "薄荷青"),
        TagColorOption(0xFF8B5CF6L, "莫奈紫"),
        TagColorOption(0xFFD97706L, "暖陶棕"),
        TagColorOption(0xFF64748BL, "岩石灰")
    )

    /**
     * 默认预设标签（覆盖日常餐饮、差旅报销、分期与人情场景）
     */
    val defaultTags: List<TagDef> = listOf(
        TagDef(name = "早餐", colorHex = 0xFFF59E0BL, groupName = "日常"),
        TagDef(name = "夜宵", colorHex = 0xFF8B5CF6L, groupName = "日常"),
        TagDef(name = "差旅交通", colorHex = 0xFF1677FFL, groupName = "差旅报销"),
        TagDef(name = "聚餐", colorHex = 0xFFE65540L, groupName = "日常"),
        TagDef(name = "出差", colorHex = 0xFF14B8A6L, groupName = "差旅报销"),
        TagDef(name = "分期", colorHex = 0xFF6366F1L, groupName = "生活事件"),
        TagDef(name = "固定开支", colorHex = 0xFF22B573L, groupName = "日常"),
        TagDef(name = "人情往来", colorHex = 0xFFEC4899L, groupName = "生活事件")
    )

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _tagsFlow = MutableStateFlow(defaultTags)
    val tagsFlow: StateFlow<List<TagDef>> = _tagsFlow.asStateFlow()

    private val _remapHistoryFlow = MutableStateFlow<List<TagRemapRecord>>(emptyList())
    val remapHistoryFlow: StateFlow<List<TagRemapRecord>> = _remapHistoryFlow.asStateFlow()

    private val _mutedTagNames = mutableSetOf<String>()

    val tags: List<TagDef>
        get() = _tagsFlow.value

    private fun normalizeSingleToken(raw: String): String {
        return raw.trim().trimStart('#').trim()
    }

    private fun isValidSanitizedTagName(trimmed: String): Boolean {
        return trimmed.isNotEmpty() &&
            trimmed != SYSTEM_BALANCE_ANCHOR_TAG &&
            trimmed.length <= 12 &&
            trimmed.none { it == ',' || it == '，' || it == '#' }
    }

    private fun sanitizeTagList(rawList: List<TagDef>): List<TagDef> {
        return rawList
            .mapNotNull { item ->
                val cleanName = normalizeSingleToken(item.name)
                if (!isValidSanitizedTagName(cleanName)) return@mapNotNull null
                val cleanGroup = item.groupName.trim().ifBlank { "日常" }
                val validColor = item.colorHex.takeIf { it > 0L } ?: DEFAULT_TAG_COLOR_HEX
                TagDef(
                    name = cleanName,
                    colorHex = validColor,
                    groupName = cleanGroup
                )
            }
            .distinctBy { it.name }
    }

    @Synchronized
    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp

        _mutedTagNames.clear()
        val mutedRaw = sp.getString(KEY_MUTED_DISCOVERED_TAGS_JSON, null)
        if (!mutedRaw.isNullOrBlank()) {
            runCatching {
                jsonCodec.decodeFromString<List<String>>(mutedRaw)
            }.getOrNull()?.forEach { name ->
                val clean = normalizeSingleToken(name)
                if (clean.isNotEmpty()) _mutedTagNames.add(clean)
            }
        }

        val customRaw = sp.getString(KEY_CUSTOM_TAGS_JSON, null)
        if (!customRaw.isNullOrBlank()) {
            runCatching {
                sanitizeTagList(jsonCodec.decodeFromString<List<TagDef>>(customRaw))
            }.getOrNull()?.let { decoded ->
                _tagsFlow.value = decoded
            }
        }
    }

    @Synchronized
    private fun persist(list: List<TagDef>) {
        _tagsFlow.value = list
        prefs?.edit()
            ?.putString(KEY_CUSTOM_TAGS_JSON, jsonCodec.encodeToString(list))
            ?.putString(KEY_MUTED_DISCOVERED_TAGS_JSON, jsonCodec.encodeToString(_mutedTagNames.toList()))
            ?.apply()
    }

    /**
     * 解析账单或规则中的原始标签字符串：
     * 按英文逗号 `,` 或中文逗号 `，` 拆分，去除首尾空格与前导 `#`，过滤空串并保序去重。
     */
    fun parseTags(rawTags: String): List<String> {
        if (rawTags.isBlank()) return emptyList()
        return rawTags
            .split(",", "，")
            .map { normalizeSingleToken(it) }
            .filter { it.isNotEmpty() && it != SYSTEM_BALANCE_ANCHOR_TAG }
            .distinct()
    }

    /**
     * 将多个标签规范化去重后以英文逗号 `,` 拼接。
     */
    fun joinTags(tags: Iterable<String>): String {
        return tags
            .map { normalizeSingleToken(it) }
            .filter { it.isNotEmpty() && it != SYSTEM_BALANCE_ANCHOR_TAG }
            .distinct()
            .joinToString(",")
    }

    /**
     * 判断原始标签字符串中是否精确包含指定标签 Token（不会将 `#出差补贴` 误判为包含 `出差`）。
     */
    fun containsTag(rawTags: String, targetTag: String): Boolean {
        val cleanTarget = normalizeSingleToken(targetTag)
        if (cleanTarget.isEmpty()) return false
        return parseTags(rawTags).contains(cleanTarget)
    }

    /**
     * 精确按标签 Token 替换或移除并自动去重：
     * - 当 `rawTags` 未包含 `oldTag` 时返回 `null`（表示该记录不受影响，无需更新）；
     * - 当 `newTag` 非空时，将 `oldTag` 替换为 `newTag` 并自动去重（如 `#出差,差旅交通` 将 `出差` 合并为 `差旅交通` 后得到 `差旅交通`）；
     * - 当 `newTag` 为 `null` 或空串时，从标签列表中移除 `oldTag`。
     */
    fun remapTagsString(rawTags: String, oldTag: String, newTag: String?): String? {
        val cleanOld = normalizeSingleToken(oldTag)
        if (cleanOld.isEmpty()) return null
        val tokens = parseTags(rawTags)
        if (cleanOld !in tokens) return null

        val cleanNew = newTag?.let { normalizeSingleToken(it) }.orEmpty()
        val mappedTokens = buildList {
            for (token in tokens) {
                if (token == cleanOld) {
                    if (cleanNew.isNotEmpty() && cleanNew != SYSTEM_BALANCE_ANCHOR_TAG) {
                        add(cleanNew)
                    }
                } else {
                    add(token)
                }
            }
        }.distinct()
        return joinTags(mappedTokens)
    }

    /**
     * 校验标签名称：
     * - 去除首尾空格后不能为空；
     * - 不能包含 `,`、`，` 或 `#` 符号；
     * - 长度不超过 12 个字符；
     * - 不能与已有标签重名。
     */
    fun validateTagName(
        rawName: String,
        existingNames: List<String> = tags.map { it.name },
        ignoreName: String? = null
    ): String? {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return "标签名称不能为空"
        if (trimmed.any { it == ',' || it == '，' || it == '#' }) {
            return "名称不能包含 , 、，或 # 符号"
        }
        val normalized = normalizeSingleToken(trimmed)
        if (normalized.isEmpty()) return "标签名称不能为空"
        if (normalized == SYSTEM_BALANCE_ANCHOR_TAG) {
            return "「$SYSTEM_BALANCE_ANCHOR_TAG」为系统平账保留标签"
        }
        if (normalized.length > 12) return "标签名称最多 12 个字符"
        val cleanIgnore = ignoreName?.let { normalizeSingleToken(it) }
        if (existingNames.any { normalizeSingleToken(it) != cleanIgnore && normalizeSingleToken(it) == normalized }) {
            return "已存在同名标签「$normalized」"
        }
        return null
    }

    /**
     * 根据标签名解析对应的莫奈主题色 Hex（若标签未在当前目录中，则回退默认表或按名称哈希分配稳定莫奈色）。
     */
    fun resolveTagColorHex(tagName: String): Long {
        val clean = normalizeSingleToken(tagName)
        if (clean.isEmpty()) return DEFAULT_TAG_COLOR_HEX
        _tagsFlow.value.firstOrNull { it.name == clean }?.let { return it.colorHex }
        defaultTags.firstOrNull { it.name == clean }?.let { return it.colorHex }
        val idx = abs(clean.hashCode()) % presetColors.size
        return presetColors[idx].colorHex
    }

    /**
     * 自动扫描并合并历史账单及条件规则中已使用的存量标签（跳过已被用户显式隐藏的 `mutedTagNames`）。
     */
    @Synchronized
    fun syncDiscoveredTagsFromHistory(rawTagStrings: Iterable<String>) {
        val current = _tagsFlow.value.toMutableList()
        val existingSet = current.map { it.name }.toMutableSet()
        var changed = false

        for (raw in rawTagStrings) {
            for (token in parseTags(raw)) {
                if (!isValidSanitizedTagName(token)) continue
                if (token in existingSet || token in _mutedTagNames) continue
                val preset = defaultTags.firstOrNull { it.name == token }
                val colorHex = preset?.colorHex ?: presetColors[abs(token.hashCode()) % presetColors.size].colorHex
                val groupName = preset?.groupName ?: "日常"
                current.add(TagDef(name = token, colorHex = colorHex, groupName = groupName))
                existingSet.add(token)
                changed = true
            }
        }

        if (changed) {
            persist(current)
        }
    }

    @Synchronized
    fun addTag(
        name: String,
        colorHex: Long = DEFAULT_TAG_COLOR_HEX,
        groupName: String = "日常"
    ): Result<TagDef> {
        val current = _tagsFlow.value.toMutableList()
        val err = validateTagName(name, current.map { it.name })
        if (err != null) return Result.failure(IllegalArgumentException(err))

        val cleanName = normalizeSingleToken(name)
        _mutedTagNames.remove(cleanName)
        val created = TagDef(
            name = cleanName,
            colorHex = colorHex.takeIf { it > 0L } ?: DEFAULT_TAG_COLOR_HEX,
            groupName = groupName.trim().ifBlank { "日常" }
        )
        current.add(created)
        persist(current)
        return Result.success(created)
    }

    @Synchronized
    fun updateTag(
        oldName: String,
        newName: String,
        colorHex: Long,
        groupName: String = "日常"
    ): Result<TagDef> {
        val cleanOld = normalizeSingleToken(oldName)
        val current = _tagsFlow.value.toMutableList()
        val idx = current.indexOfFirst { it.name == cleanOld }
        if (idx < 0) return Result.failure(IllegalArgumentException("未找到标签「$cleanOld」"))

        val err = validateTagName(newName, current.map { it.name }, ignoreName = cleanOld)
        if (err != null) return Result.failure(IllegalArgumentException(err))

        val cleanNew = normalizeSingleToken(newName)
        _mutedTagNames.remove(cleanNew)
        val updated = current[idx].copy(
            name = cleanNew,
            colorHex = colorHex.takeIf { it > 0L } ?: current[idx].colorHex,
            groupName = groupName.trim().ifBlank { current[idx].groupName }
        )
        current[idx] = updated
        persist(current)
        return Result.success(updated)
    }

    @Synchronized
    fun moveTag(index: Int, delta: Int) {
        val current = _tagsFlow.value.toMutableList()
        val targetIndex = index + delta
        if (index !in current.indices || targetIndex !in current.indices) return
        val item = current.removeAt(index)
        current.add(targetIndex, item)
        persist(current)
    }

    @Synchronized
    fun recordTagRemap(oldTag: String, newTag: String?) {
        val cleanOld = normalizeSingleToken(oldTag)
        if (cleanOld.isEmpty()) return
        val cleanNew = newTag?.let { normalizeSingleToken(it) }?.takeIf { it.isNotEmpty() }
        val nextSeq = (_remapHistoryFlow.value.lastOrNull()?.seq ?: 0L) + 1L
        _remapHistoryFlow.value = (_remapHistoryFlow.value + TagRemapRecord(nextSeq, cleanOld, cleanNew)).takeLast(32)
    }

    @Synchronized
    fun mergeTagInCatalog(sourceName: String, targetName: String): Result<TagDef> {
        val cleanSource = normalizeSingleToken(sourceName)
        val cleanTarget = normalizeSingleToken(targetName)
        if (cleanSource.isEmpty() || cleanTarget.isEmpty()) {
            return Result.failure(IllegalArgumentException("合并标签名称不能为空"))
        }
        if (cleanSource == cleanTarget) {
            return Result.failure(IllegalArgumentException("源标签与目标标签不能相同"))
        }
        val current = _tagsFlow.value.toMutableList()
        val targetDef = current.firstOrNull { it.name == cleanTarget }
            ?: return Result.failure(IllegalArgumentException("未找到目标标签「$cleanTarget」"))
        val removed = current.removeAll { it.name == cleanSource }
        if (!removed) {
            return Result.failure(IllegalArgumentException("未找到源标签「$cleanSource」"))
        }
        _mutedTagNames.remove(cleanSource)
        persist(current)
        return Result.success(targetDef)
    }

    @Synchronized
    fun deleteTagFromCatalog(
        name: String,
        muteDiscoveryIfKeptInHistory: Boolean = false
    ): Result<Unit> {
        val cleanName = normalizeSingleToken(name)
        val current = _tagsFlow.value.toMutableList()
        val removed = current.removeAll { it.name == cleanName }
        if (!removed) {
            return Result.failure(IllegalArgumentException("未找到标签「$cleanName」"))
        }
        if (muteDiscoveryIfKeptInHistory) {
            _mutedTagNames.add(cleanName)
        } else {
            _mutedTagNames.remove(cleanName)
        }
        persist(current)
        return Result.success(Unit)
    }

    @Synchronized
    fun replaceAllTags(customList: List<TagDef>) {
        val clean = sanitizeTagList(customList)
        if (clean.isEmpty()) return
        persist(clean)
    }

    @Synchronized
    fun restoreDefaultTags(mergeOnly: Boolean) {
        if (mergeOnly) {
            val current = _tagsFlow.value.toMutableList()
            val existingNames = current.map { it.name }.toSet()
            val missing = defaultTags.filter { it.name !in existingNames }
            defaultTags.forEach { _mutedTagNames.remove(it.name) }
            if (missing.isNotEmpty()) {
                current.addAll(missing)
            }
            persist(current)
        } else {
            _mutedTagNames.clear()
            persist(defaultTags)
        }
    }
}
