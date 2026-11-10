package com.moji.ai.data.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CategoryDef(
    val name: String,
    val assetFile: String,
    val subCategories: List<SubCategoryDef> = emptyList()
)

@Serializable
data class SubCategoryDef(
    val name: String,
    val assetFile: String
)

data class CategoryIconOption(
    val assetFile: String,
    val label: String
)

data class CategoryIconGroup(
    val title: String,
    val icons: List<CategoryIconOption>
)

object CategoryCatalog {
    private const val PREFS_NAME = "moji_category_catalog_prefs"
    private const val KEY_EXPENSE_JSON = "custom_expense_categories_json"
    private const val KEY_INCOME_JSON = "custom_income_categories_json"
    const val SYSTEM_BALANCE_ANCHOR_CATEGORY = "平账"
    const val PENDING_TRIAGE_CATEGORY = "待梳理"

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val defaultExpenseCategories: List<CategoryDef> = listOf(
        CategoryDef(
            name = "餐饮",
            assetFile = "cat_dining.webp",
            subCategories = listOf(
                SubCategoryDef("三餐", "sub_meals.webp"),
                SubCategoryDef("零食", "sub_snacks.webp"),
                SubCategoryDef("水果", "sub_fruit.webp"),
                SubCategoryDef("蔬菜", "sub_vegetable.webp"),
                SubCategoryDef("咖啡", "sub_coffee.webp"),
                SubCategoryDef("奶茶", "sub_milktea.webp")
            )
        ),
        CategoryDef(
            name = "购物",
            assetFile = "cat_shopping.webp",
            subCategories = listOf(
                SubCategoryDef("淘宝", "sub_taobao.webp"),
                SubCategoryDef("京东", "sub_jd.webp"),
                SubCategoryDef("拼多多", "sub_pdd.webp"),
                SubCategoryDef("超市", "sub_supermarket.webp")
            )
        ),
        CategoryDef("服饰", "cat_dress.webp"),
        CategoryDef("日用", "cat_daily.webp"),
        CategoryDef(
            name = "数码",
            assetFile = "cat_digital.webp",
            subCategories = listOf(
                SubCategoryDef("手机", "sub_phone.webp"),
                SubCategoryDef("电脑", "sub_laptop.webp"),
                SubCategoryDef("耳机", "sub_earphone.webp"),
                SubCategoryDef("配件", "sub_accessories.webp")
            )
        ),
        CategoryDef("美妆", "cat_makeup.webp"),
        CategoryDef("护肤", "cat_skincare.webp"),
        CategoryDef("应用软件", "cat_app_software.webp"),
        CategoryDef(
            name = "住房",
            assetFile = "cat_housing.webp",
            subCategories = listOf(
                SubCategoryDef("房租", "sub_rent.webp"),
                SubCategoryDef("维修", "sub_repair.webp"),
                SubCategoryDef("水电费", "sub_utilities.webp"),
                SubCategoryDef("物业费", "sub_property_fee.webp")
            )
        ),
        CategoryDef(
            name = "交通",
            assetFile = "cat_transit.webp",
            subCategories = listOf(
                SubCategoryDef("公交", "sub_bus.webp"),
                SubCategoryDef("地铁", "sub_subway.webp"),
                SubCategoryDef("打车", "sub_taxi.webp"),
                SubCategoryDef("火车", "sub_train.webp"),
                SubCategoryDef("飞机", "sub_flight.webp")
            )
        ),
        CategoryDef("娱乐", "cat_entertainment.webp"),
        CategoryDef(
            name = "医疗",
            assetFile = "cat_medical.webp",
            subCategories = listOf(
                SubCategoryDef("门诊", "sub_outpatient.webp"),
                SubCategoryDef("买药", "sub_medicine.webp"),
                SubCategoryDef("住院", "sub_hospital.webp"),
                SubCategoryDef("体检", "sub_checkup.webp"),
                SubCategoryDef("牙科", "sub_dental.webp")
            )
        ),
        CategoryDef("通讯", "cat_telecom.webp"),
        CategoryDef(
            name = "汽车",
            assetFile = "cat_car.webp",
            subCategories = listOf(
                SubCategoryDef("加油", "sub_fuel.webp"),
                SubCategoryDef("停车", "sub_parking.webp"),
                SubCategoryDef("维修", "sub_repair.webp")
            )
        ),
        CategoryDef(
            name = "学习",
            assetFile = "cat_study.webp",
            subCategories = listOf(
                SubCategoryDef("书籍", "sub_books.webp"),
                SubCategoryDef("课程", "sub_course.webp"),
                SubCategoryDef("文具", "sub_stationery.webp"),
                SubCategoryDef("考试", "sub_exam.webp")
            )
        ),
        CategoryDef("办公", "cat_office.webp"),
        CategoryDef("运动", "cat_sports.webp"),
        CategoryDef("社交", "cat_social.webp"),
        CategoryDef(
            name = "人情",
            assetFile = "cat_relationship.webp",
            subCategories = listOf(
                SubCategoryDef("红包", "cat_red_packet.webp"),
                SubCategoryDef("礼物", "cat_gift.webp"),
                SubCategoryDef("礼金", "cat_cash_gift.webp")
            )
        ),
        CategoryDef("育儿", "cat_baby.webp"),
        CategoryDef("旅行", "cat_travel.webp"),
        CategoryDef("宠物", "cat_pet.webp"),
        CategoryDef("烟酒", "cat_tobacco_alcohol.webp"),
        CategoryDef("彩票", "cat_lottery.webp"),
        CategoryDef("其他", "cat_other.webp")
    )

    val defaultIncomeCategories: List<CategoryDef> = listOf(
        CategoryDef("工资", "cat_salary.webp"),
        CategoryDef("奖金", "cat_bonus.webp"),
        CategoryDef("兼职", "cat_parttime.webp"),
        CategoryDef("理财", "cat_investment.webp"),
        CategoryDef("红包", "cat_red_packet.webp"),
        CategoryDef("报销", "cat_claim.webp"),
        CategoryDef("礼金", "cat_cash_gift.webp"),
        CategoryDef("福利", "cat_welfare.webp"),
        CategoryDef("退款", "cat_refund.webp"),
        CategoryDef("其他", "cat_other.webp")
    )

    /**
     * 完整收录 assets/icost/ 下全部 48 枚 cat_*.webp 与 33 枚 sub_*.webp（共 81 枚线性分类图标）
     */
    val iconGroups: List<CategoryIconGroup> = listOf(
        CategoryIconGroup(
            title = "餐饮美食",
            icons = listOf(
                CategoryIconOption("cat_dining.webp", "餐饮"),
                CategoryIconOption("sub_meals.webp", "三餐"),
                CategoryIconOption("sub_snacks.webp", "零食"),
                CategoryIconOption("sub_fruit.webp", "水果"),
                CategoryIconOption("cat_fruit.webp", "鲜果"),
                CategoryIconOption("sub_vegetable.webp", "蔬菜"),
                CategoryIconOption("cat_vegetable.webp", "买菜"),
                CategoryIconOption("sub_coffee.webp", "咖啡"),
                CategoryIconOption("sub_milktea.webp", "奶茶"),
                CategoryIconOption("cat_tobacco_alcohol.webp", "烟酒")
            )
        ),
        CategoryIconGroup(
            title = "购物消费",
            icons = listOf(
                CategoryIconOption("cat_shopping.webp", "购物"),
                CategoryIconOption("sub_taobao.webp", "淘宝"),
                CategoryIconOption("sub_jd.webp", "京东"),
                CategoryIconOption("sub_pdd.webp", "拼多多"),
                CategoryIconOption("sub_supermarket.webp", "超市"),
                CategoryIconOption("cat_dress.webp", "服饰"),
                CategoryIconOption("cat_daily.webp", "日用"),
                CategoryIconOption("cat_makeup.webp", "美妆"),
                CategoryIconOption("cat_skincare.webp", "护肤")
            )
        ),
        CategoryIconGroup(
            title = "数码与软件",
            icons = listOf(
                CategoryIconOption("cat_digital.webp", "数码"),
                CategoryIconOption("sub_phone.webp", "手机"),
                CategoryIconOption("sub_laptop.webp", "电脑"),
                CategoryIconOption("sub_earphone.webp", "耳机"),
                CategoryIconOption("sub_accessories.webp", "配件"),
                CategoryIconOption("cat_app_software.webp", "应用软件"),
                CategoryIconOption("cat_telecom.webp", "通讯")
            )
        ),
        CategoryIconGroup(
            title = "居家与生活",
            icons = listOf(
                CategoryIconOption("cat_housing.webp", "住房"),
                CategoryIconOption("sub_rent.webp", "房租"),
                CategoryIconOption("cat_rent.webp", "租房"),
                CategoryIconOption("sub_utilities.webp", "水电费"),
                CategoryIconOption("sub_property_fee.webp", "物业费"),
                CategoryIconOption("sub_repair.webp", "维修"),
                CategoryIconOption("cat_repair.webp", "家修"),
                CategoryIconOption("cat_family.webp", "家庭"),
                CategoryIconOption("cat_baby.webp", "育儿"),
                CategoryIconOption("cat_pet.webp", "宠物")
            )
        ),
        CategoryIconGroup(
            title = "交通与出行",
            icons = listOf(
                CategoryIconOption("cat_transit.webp", "交通"),
                CategoryIconOption("sub_bus.webp", "公交"),
                CategoryIconOption("sub_subway.webp", "地铁"),
                CategoryIconOption("sub_taxi.webp", "打车"),
                CategoryIconOption("sub_train.webp", "火车"),
                CategoryIconOption("sub_flight.webp", "飞机"),
                CategoryIconOption("cat_car.webp", "汽车"),
                CategoryIconOption("sub_fuel.webp", "加油"),
                CategoryIconOption("sub_parking.webp", "停车"),
                CategoryIconOption("cat_travel.webp", "旅行"),
                CategoryIconOption("cat_vacation.webp", "度假")
            )
        ),
        CategoryIconGroup(
            title = "医疗与健康",
            icons = listOf(
                CategoryIconOption("cat_medical.webp", "医疗"),
                CategoryIconOption("sub_outpatient.webp", "门诊"),
                CategoryIconOption("sub_medicine.webp", "买药"),
                CategoryIconOption("sub_hospital.webp", "住院"),
                CategoryIconOption("sub_checkup.webp", "体检"),
                CategoryIconOption("sub_dental.webp", "牙科"),
                CategoryIconOption("cat_sports.webp", "运动")
            )
        ),
        CategoryIconGroup(
            title = "学习与办公",
            icons = listOf(
                CategoryIconOption("cat_study.webp", "学习"),
                CategoryIconOption("cat_education.webp", "教育"),
                CategoryIconOption("sub_books.webp", "书籍"),
                CategoryIconOption("sub_course.webp", "课程"),
                CategoryIconOption("sub_stationery.webp", "文具"),
                CategoryIconOption("sub_exam.webp", "考试"),
                CategoryIconOption("cat_office.webp", "办公")
            )
        ),
        CategoryIconGroup(
            title = "娱乐与人情",
            icons = listOf(
                CategoryIconOption("cat_entertainment.webp", "娱乐"),
                CategoryIconOption("cat_social.webp", "社交"),
                CategoryIconOption("cat_relationship.webp", "人情"),
                CategoryIconOption("cat_red_packet.webp", "红包"),
                CategoryIconOption("cat_gift.webp", "礼物"),
                CategoryIconOption("cat_cash_gift.webp", "礼金"),
                CategoryIconOption("cat_donate.webp", "捐赠"),
                CategoryIconOption("cat_lottery.webp", "彩票")
            )
        ),
        CategoryIconGroup(
            title = "收入与资金往来",
            icons = listOf(
                CategoryIconOption("cat_salary.webp", "工资"),
                CategoryIconOption("cat_bonus.webp", "奖金"),
                CategoryIconOption("cat_parttime.webp", "兼职"),
                CategoryIconOption("cat_investment.webp", "理财"),
                CategoryIconOption("cat_claim.webp", "报销"),
                CategoryIconOption("cat_claim_in.webp", "报销入账"),
                CategoryIconOption("cat_welfare.webp", "福利"),
                CategoryIconOption("cat_refund.webp", "退款"),
                CategoryIconOption("cat_transfer.webp", "转账"),
                CategoryIconOption("cat_borrow_in.webp", "借入"),
                CategoryIconOption("cat_lend_out.webp", "借出"),
                CategoryIconOption("cat_adjust.webp", "调账"),
                CategoryIconOption("cat_other.webp", "其他")
            )
        )
    )

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _expenseCategoriesFlow = MutableStateFlow(defaultExpenseCategories)
    val expenseCategoriesFlow: StateFlow<List<CategoryDef>> = _expenseCategoriesFlow.asStateFlow()

    private val _incomeCategoriesFlow = MutableStateFlow(defaultIncomeCategories)
    val incomeCategoriesFlow: StateFlow<List<CategoryDef>> = _incomeCategoriesFlow.asStateFlow()

    private val _versionFlow = MutableStateFlow(0)
    val versionFlow: StateFlow<Int> = _versionFlow.asStateFlow()

    val expenseCategories: List<CategoryDef>
        get() = _expenseCategoriesFlow.value

    val incomeCategories: List<CategoryDef>
        get() = _incomeCategoriesFlow.value

    private fun isValidSanitizedName(trimmed: String): Boolean {
        return trimmed.isNotEmpty() &&
            trimmed != SYSTEM_BALANCE_ANCHOR_CATEGORY &&
            trimmed != PENDING_TRIAGE_CATEGORY &&
            trimmed.length <= 12 &&
            trimmed.none { it == '-' || it == '/' || it == '·' }
    }

    /**
     * 对外部导入或本地反序列化的分类列表执行清洗与同名去重，防止异常 JSON 引发 UI Key 冲突或保留字污染
     */
    private fun sanitizeCategoryList(rawList: List<CategoryDef>): List<CategoryDef> {
        return rawList
            .mapNotNull { cat ->
                val cleanName = cat.name.trim()
                if (!isValidSanitizedName(cleanName)) return@mapNotNull null
                val cleanAsset = cat.assetFile.trim().ifBlank { "cat_other.webp" }
                val cleanSubs = cat.subCategories
                    .mapNotNull { sub ->
                        val subName = sub.name.trim()
                        if (!isValidSanitizedName(subName)) return@mapNotNull null
                        SubCategoryDef(
                            name = subName,
                            assetFile = sub.assetFile.trim().ifBlank { cleanAsset }
                        )
                    }
                    .distinctBy { it.name }
                CategoryDef(
                    name = cleanName,
                    assetFile = cleanAsset,
                    subCategories = cleanSubs
                )
            }
            .distinctBy { it.name }
    }

    private val cachedAllIcons: List<CategoryIconOption> by lazy {
        iconGroups.flatMap { it.icons }
    }

    @Synchronized
    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        val expRaw = sp.getString(KEY_EXPENSE_JSON, null)
        if (!expRaw.isNullOrBlank()) {
            runCatching {
                sanitizeCategoryList(jsonCodec.decodeFromString<List<CategoryDef>>(expRaw))
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                _expenseCategoriesFlow.value = it
            }
        }
        val incRaw = sp.getString(KEY_INCOME_JSON, null)
        if (!incRaw.isNullOrBlank()) {
            runCatching {
                sanitizeCategoryList(jsonCodec.decodeFromString<List<CategoryDef>>(incRaw))
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                _incomeCategoriesFlow.value = it
            }
        }
        _versionFlow.value += 1
    }

    private fun persist(isExpense: Boolean, list: List<CategoryDef>) {
        if (isExpense) {
            _expenseCategoriesFlow.value = list
            prefs?.edit()?.putString(KEY_EXPENSE_JSON, jsonCodec.encodeToString(list))?.apply()
        } else {
            _incomeCategoriesFlow.value = list
            prefs?.edit()?.putString(KEY_INCOME_JSON, jsonCodec.encodeToString(list))?.apply()
        }
        _versionFlow.value += 1
    }

    /**
     * 校验分类名称（去除首尾空白后不能为空、不能为系统保留名「平账」、不能包含 `-` `/` `·` 分隔符、同级不能重名）
     */
    fun validateCategoryName(
        rawName: String,
        siblingNames: List<String>,
        ignoreName: String? = null
    ): String? {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return "分类名称不能为空"
        if (trimmed == SYSTEM_BALANCE_ANCHOR_CATEGORY) {
            return "「$SYSTEM_BALANCE_ANCHOR_CATEGORY」为系统平账保留分类"
        }
        if (trimmed == PENDING_TRIAGE_CATEGORY) {
            return "「$PENDING_TRIAGE_CATEGORY」为系统待梳理保留状态"
        }
        if (trimmed.any { it == '-' || it == '/' || it == '·' }) {
            return "名称不能包含 -、/ 或 · 分隔符"
        }
        if (trimmed.length > 12) return "分类名称最多 12 个字符"
        if (siblingNames.any { it != ignoreName && it == trimmed }) {
            return "同级下已存在「$trimmed」"
        }
        return null
    }

    /**
     * 判断分类字符串是否命中指定分类范围（支持仅精确匹配或包含 `一级-二级` 子分类前缀）
     */
    fun matchesCategoryScope(
        current: String,
        targetCategory: String,
        includeSubPrefix: Boolean
    ): Boolean = current == targetCategory || (includeSubPrefix && current.startsWith("$targetCategory-"))

    /**
     * 计算级联重命名或删除迁移后的新分类名；若未受影响或名称未变则返回 null
     */
    fun remapCascadedCategory(
        current: String,
        oldCategory: String,
        newCategory: String,
        includeSubPrefix: Boolean,
        preserveSubSuffix: Boolean
    ): String? {
        val prefix = "$oldCategory-"
        val mapped = when {
            current == oldCategory -> newCategory
            includeSubPrefix && current.startsWith(prefix) -> {
                if (preserveSubSuffix) "$newCategory-${current.removePrefix(prefix)}" else newCategory
            }
            else -> null
        }
        return mapped?.takeIf { it != current }
    }

    @Synchronized
    fun replaceAllCategories(
        expenseList: List<CategoryDef>,
        incomeList: List<CategoryDef>
    ) {
        val cleanExpense = sanitizeCategoryList(expenseList)
        val cleanIncome = sanitizeCategoryList(incomeList)
        if (cleanExpense.isEmpty() && cleanIncome.isEmpty()) return
        val editor = prefs?.edit()
        if (cleanExpense.isNotEmpty()) {
            _expenseCategoriesFlow.value = cleanExpense
            editor?.putString(KEY_EXPENSE_JSON, jsonCodec.encodeToString(cleanExpense))
        }
        if (cleanIncome.isNotEmpty()) {
            _incomeCategoriesFlow.value = cleanIncome
            editor?.putString(KEY_INCOME_JSON, jsonCodec.encodeToString(cleanIncome))
        }
        editor?.apply()
        _versionFlow.value += 1
    }

    @Synchronized
    fun addPrimaryCategory(
        isExpense: Boolean,
        name: String,
        assetFile: String
    ): Result<CategoryDef> {
        val current = (if (isExpense) expenseCategories else incomeCategories).toMutableList()
        val err = validateCategoryName(name, current.map { it.name })
        if (err != null) return Result.failure(IllegalArgumentException(err))
        val created = CategoryDef(name = name.trim(), assetFile = assetFile.ifBlank { "cat_other.webp" })
        // 若末尾是「其他」，默认插在「其他」之前，符合记账习惯
        val otherIdx = current.indexOfLast { it.name == "其他" }
        if (otherIdx >= 0) {
            current.add(otherIdx, created)
        } else {
            current.add(created)
        }
        persist(isExpense, current)
        return Result.success(created)
    }

    @Synchronized
    fun updatePrimaryCategory(
        isExpense: Boolean,
        oldName: String,
        newName: String,
        newAssetFile: String
    ): Result<CategoryDef> {
        val current = (if (isExpense) expenseCategories else incomeCategories).toMutableList()
        val idx = current.indexOfFirst { it.name == oldName }
        if (idx < 0) return Result.failure(IllegalArgumentException("未找到分类「$oldName」"))
        val err = validateCategoryName(newName, current.map { it.name }, ignoreName = oldName)
        if (err != null) return Result.failure(IllegalArgumentException(err))
        val updated = current[idx].copy(
            name = newName.trim(),
            assetFile = newAssetFile.ifBlank { current[idx].assetFile }
        )
        current[idx] = updated
        persist(isExpense, current)
        return Result.success(updated)
    }

    @Synchronized
    fun movePrimaryCategory(isExpense: Boolean, index: Int, delta: Int): Boolean {
        val current = (if (isExpense) expenseCategories else incomeCategories).toMutableList()
        val targetIndex = index + delta
        if (index !in current.indices || targetIndex !in current.indices) return false
        val item = current.removeAt(index)
        current.add(targetIndex, item)
        persist(isExpense, current)
        return true
    }

    @Synchronized
    fun deletePrimaryCategory(isExpense: Boolean, name: String): Result<Unit> {
        val current = (if (isExpense) expenseCategories else incomeCategories).toMutableList()
        if (current.size <= 1) {
            return Result.failure(IllegalStateException("至少需保留 1 个一级分类"))
        }
        val removed = current.removeAll { it.name == name }
        if (!removed) return Result.failure(IllegalArgumentException("未找到分类「$name」"))
        persist(isExpense, current)
        return Result.success(Unit)
    }

    @Synchronized
    fun addSubCategory(
        isExpense: Boolean,
        parentName: String,
        subName: String,
        assetFile: String
    ): Result<SubCategoryDef> {
        val current = (if (isExpense) expenseCategories else incomeCategories).toMutableList()
        val pIdx = current.indexOfFirst { it.name == parentName }
        if (pIdx < 0) return Result.failure(IllegalArgumentException("未找到一级分类「$parentName」"))
        val parent = current[pIdx]
        val err = validateCategoryName(subName, parent.subCategories.map { it.name })
        if (err != null) return Result.failure(IllegalArgumentException(err))
        val created = SubCategoryDef(
            name = subName.trim(),
            assetFile = assetFile.ifBlank { parent.assetFile }
        )
        current[pIdx] = parent.copy(subCategories = parent.subCategories + created)
        persist(isExpense, current)
        return Result.success(created)
    }

    @Synchronized
    fun updateSubCategory(
        isExpense: Boolean,
        parentName: String,
        oldSubName: String,
        newSubName: String,
        newAssetFile: String
    ): Result<SubCategoryDef> {
        val current = (if (isExpense) expenseCategories else incomeCategories).toMutableList()
        val pIdx = current.indexOfFirst { it.name == parentName }
        if (pIdx < 0) return Result.failure(IllegalArgumentException("未找到一级分类「$parentName」"))
        val parent = current[pIdx]
        val sIdx = parent.subCategories.indexOfFirst { it.name == oldSubName }
        if (sIdx < 0) return Result.failure(IllegalArgumentException("未找到二级分类「$oldSubName」"))
        val err = validateCategoryName(
            newSubName,
            parent.subCategories.map { it.name },
            ignoreName = oldSubName
        )
        if (err != null) return Result.failure(IllegalArgumentException(err))
        val updatedSub = parent.subCategories[sIdx].copy(
            name = newSubName.trim(),
            assetFile = newAssetFile.ifBlank { parent.subCategories[sIdx].assetFile }
        )
        val newSubs = parent.subCategories.toMutableList().apply { set(sIdx, updatedSub) }
        current[pIdx] = parent.copy(subCategories = newSubs)
        persist(isExpense, current)
        return Result.success(updatedSub)
    }

    @Synchronized
    fun moveSubCategory(
        isExpense: Boolean,
        parentName: String,
        subIndex: Int,
        delta: Int
    ): Boolean {
        val current = (if (isExpense) expenseCategories else incomeCategories).toMutableList()
        val pIdx = current.indexOfFirst { it.name == parentName }
        if (pIdx < 0) return false
        val subs = current[pIdx].subCategories.toMutableList()
        val targetIndex = subIndex + delta
        if (subIndex !in subs.indices || targetIndex !in subs.indices) return false
        val item = subs.removeAt(subIndex)
        subs.add(targetIndex, item)
        current[pIdx] = current[pIdx].copy(subCategories = subs)
        persist(isExpense, current)
        return true
    }

    @Synchronized
    fun deleteSubCategory(
        isExpense: Boolean,
        parentName: String,
        subName: String
    ): Result<Unit> {
        val current = (if (isExpense) expenseCategories else incomeCategories).toMutableList()
        val pIdx = current.indexOfFirst { it.name == parentName }
        if (pIdx < 0) return Result.failure(IllegalArgumentException("未找到一级分类「$parentName」"))
        val parent = current[pIdx]
        val newSubs = parent.subCategories.filterNot { it.name == subName }
        if (newSubs.size == parent.subCategories.size) {
            return Result.failure(IllegalArgumentException("未找到二级分类「$subName」"))
        }
        current[pIdx] = parent.copy(subCategories = newSubs)
        persist(isExpense, current)
        return Result.success(Unit)
    }

    /**
     * 恢复默认分类：
     * @param isExpense true=仅支出, false=仅收入, null=支出与收入全部处理
     * @param mergeOnly true=补全缺失的默认分类（保留用户自建分类与自定义图标）, false=重置为出厂默认分类树
     */
    @Synchronized
    fun restoreDefaultCategories(isExpense: Boolean? = null, mergeOnly: Boolean = true) {
        val nextExp = if (mergeOnly) {
            mergeWithDefaults(expenseCategories, defaultExpenseCategories)
        } else {
            defaultExpenseCategories
        }
        val nextInc = if (mergeOnly) {
            mergeWithDefaults(incomeCategories, defaultIncomeCategories)
        } else {
            defaultIncomeCategories
        }
        when (isExpense) {
            null -> replaceAllCategories(nextExp, nextInc)
            true -> persist(true, nextExp)
            false -> persist(false, nextInc)
        }
    }

    private fun mergeWithDefaults(
        current: List<CategoryDef>,
        defaults: List<CategoryDef>
    ): List<CategoryDef> {
        val result = current.map { existing ->
            val defMatch = defaults.firstOrNull { it.name == existing.name }
            if (defMatch == null) {
                existing
            } else {
                val missingSubs = defMatch.subCategories.filter { defSub ->
                    existing.subCategories.none { it.name == defSub.name }
                }
                if (missingSubs.isEmpty()) existing else existing.copy(
                    subCategories = existing.subCategories + missingSubs
                )
            }
        }.toMutableList()

        defaults.forEach { defCat ->
            if (result.none { it.name == defCat.name }) {
                val otherIdx = result.indexOfLast { it.name == "其他" }
                if (otherIdx >= 0 && defCat.name != "其他") {
                    result.add(otherIdx, defCat)
                } else {
                    result.add(defCat)
                }
            }
        }
        return result
    }

    fun isPendingTriage(category: String): Boolean {
        val trimmed = category.trim()
        return trimmed == PENDING_TRIAGE_CATEGORY || trimmed.startsWith("$PENDING_TRIAGE_CATEGORY-")
    }

    /**
     * 判断「一级分类」或「一级-二级分类」是否已存在于指定分类列表中
     */
    fun hasCategoryInList(fullCategory: String, list: List<CategoryDef>): Boolean {
        val parts = fullCategory.split("-", "·", "/").map { it.trim() }.filter { it.isNotEmpty() }
        val mainName = parts.firstOrNull() ?: return false
        val subName = parts.getOrNull(1)
        val parent = list.firstOrNull { it.name == mainName } ?: return false
        if (subName.isNullOrBlank()) return true
        return parent.subCategories.any { it.name == subName }
    }

    /**
     * 判断「一级分类」或「一级-二级分类」是否已存在于当前分类树中
     */
    fun hasCategoryPath(fullCategory: String, isExpense: Boolean = true): Boolean {
        val list = if (isExpense) expenseCategories else incomeCategories
        return hasCategoryInList(fullCategory, list)
    }

    /**
     * 确保「一级分类」或「一级-二级分类」在分类树中存在（若缺失则自动创建并匹配最佳图标）
     * @return 是否实际新建了分类节点
     */
    @Synchronized
    fun ensureCategoryPathExists(
        fullCategory: String,
        isExpense: Boolean = true,
        iconAssetOverride: String? = null
    ): Boolean {
        if (isPendingTriage(fullCategory) || hasCategoryPath(fullCategory, isExpense)) return false
        val parts = fullCategory.split("-", "·", "/").map { it.trim() }.filter { it.isNotEmpty() }
        val mainName = parts.firstOrNull() ?: return false
        val subName = parts.getOrNull(1)
        var createdAny = false
        val currentList = if (isExpense) expenseCategories else incomeCategories
        if (currentList.none { it.name == mainName }) {
            val mainIcon = if (subName.isNullOrBlank() && !iconAssetOverride.isNullOrBlank()) {
                iconAssetOverride
            } else {
                matchBestIconAsset(mainName, null, isExpense)
            }
            if (addPrimaryCategory(isExpense, mainName, mainIcon).isSuccess) {
                createdAny = true
            }
        }
        if (!subName.isNullOrBlank()) {
            val subIcon = iconAssetOverride?.takeIf { it.isNotBlank() }
                ?: matchBestIconAsset(subName, mainName, isExpense)
            if (addSubCategory(isExpense, mainName, subName, subIcon).isSuccess) {
                createdAny = true
            }
        }
        return createdAny
    }

    /**
     * 根据分类名称与父分类语义，从 81 枚 iCost 线性图标库中自动匹配最契合的 `.webp` 图标
     */
    fun matchBestIconAsset(
        name: String,
        parentName: String? = null,
        isExpense: Boolean = true
    ): String {
        val trimmedName = name.trim()
        val combined = listOfNotNull(parentName, trimmedName).joinToString("-")
        cachedAllIcons.firstOrNull { it.label == trimmedName }?.let { return it.assetFile }
        if (trimmedName.length >= 2) {
            cachedAllIcons.firstOrNull {
                trimmedName.contains(it.label) || it.label.contains(trimmedName)
            }?.let {
                return it.assetFile
            }
        }
        return when {
            combined.contains("夜宵") || combined.contains("烧烤") || combined.contains("聚餐") ||
                combined.contains("早餐") || combined.contains("午餐") || combined.contains("晚餐") -> "sub_meals.webp"
            combined.contains("咖啡") || combined.contains("星巴克") || combined.contains("瑞幸") -> "sub_coffee.webp"
            combined.contains("奶茶") || combined.contains("茶饮") || combined.contains("果茶") -> "sub_milktea.webp"
            combined.contains("甜品") || combined.contains("烘焙") || combined.contains("面包") ||
                combined.contains("零食") -> "sub_snacks.webp"
            combined.contains("水果") || combined.contains("生鲜") -> "sub_fruit.webp"
            combined.contains("买菜") || combined.contains("蔬菜") -> "sub_vegetable.webp"
            combined.contains("餐") || combined.contains("食") -> "cat_dining.webp"
            combined.contains("球") || combined.contains("健身") || combined.contains("游泳") ||
                combined.contains("跑步") || combined.contains("运动") -> "cat_sports.webp"
            combined.contains("猫") || combined.contains("狗") || combined.contains("宠") -> "cat_pet.webp"
            combined.contains("云") || combined.contains("AI") || combined.contains("算力") ||
                combined.contains("订阅") || combined.contains("会员") || combined.contains("软件") -> "cat_app_software.webp"
            combined.contains("打车") || combined.contains("网约车") || combined.contains("代驾") -> "sub_taxi.webp"
            combined.contains("地铁") -> "sub_subway.webp"
            combined.contains("公交") || combined.contains("单车") -> "sub_bus.webp"
            combined.contains("高铁") || combined.contains("火车") -> "sub_train.webp"
            combined.contains("机票") || combined.contains("航班") -> "sub_flight.webp"
            combined.contains("充电") || combined.contains("加油") -> "sub_fuel.webp"
            combined.contains("停车") || combined.contains("高速") || combined.contains("过路") -> "sub_parking.webp"
            combined.contains("车") -> "cat_car.webp"
            combined.contains("药") -> "sub_medicine.webp"
            combined.contains("牙") -> "sub_dental.webp"
            combined.contains("体检") -> "sub_checkup.webp"
            combined.contains("医") || combined.contains("诊") -> "cat_medical.webp"
            combined.contains("书") || combined.contains("阅读") -> "sub_books.webp"
            combined.contains("课") || combined.contains("培训") -> "sub_course.webp"
            combined.contains("学") || combined.contains("考") -> "cat_study.webp"
            combined.contains("电影") || combined.contains("演出") || combined.contains("游戏") ||
                combined.contains("播客") || combined.contains("娱乐") -> "cat_entertainment.webp"
            combined.contains("红包") -> "cat_red_packet.webp"
            combined.contains("礼") -> "cat_gift.webp"
            combined.contains("酒") || combined.contains("烟") -> "cat_tobacco_alcohol.webp"
            combined.contains("旅") || combined.contains("酒店") || combined.contains("民宿") -> "cat_travel.webp"
            combined.contains("房租") -> "sub_rent.webp"
            combined.contains("水电") || combined.contains("燃气") -> "sub_utilities.webp"
            combined.contains("物业") -> "sub_property_fee.webp"
            combined.contains("话费") || combined.contains("宽带") || combined.contains("流量") -> "cat_telecom.webp"
            !isExpense && (combined.contains("薪") || combined.contains("工资")) -> "cat_salary.webp"
            !isExpense && combined.contains("奖") -> "cat_bonus.webp"
            !isExpense && (combined.contains("兼职") || combined.contains("副业") || combined.contains("稿费")) -> "cat_parttime.webp"
            !isExpense && (combined.contains("理财") || combined.contains("分红") || combined.contains("利息")) -> "cat_investment.webp"
            else -> {
                val list = if (isExpense) expenseCategories else incomeCategories
                list.firstOrNull { it.name == parentName }?.assetFile ?: "cat_other.webp"
            }
        }
    }
}
