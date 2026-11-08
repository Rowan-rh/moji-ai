package com.moji.ai.ai

import android.content.Context
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 商户记忆条目（对标 photo-ledger 与 blue-whale-keeper）：
 * 当用户手动新增或修改某笔账单的分类/账户时，自动记忆「商户名 -> 习惯分类 + 常用钱包」，
 * 下次无论离线快路径还是云端大模型遇到该商户，直接 100% 命中用户习惯。
 */
@Serializable
data class MerchantMemoryEntry(
    val merchantKeyword: String,
    val category: String,
    val preferredWalletId: Long? = null,
    val hitCount: Int = 1,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

class MerchantMemoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("moji_merchant_memory_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _memoriesFlow = MutableStateFlow(loadEntries())
    val memoriesFlow: StateFlow<List<MerchantMemoryEntry>> = _memoriesFlow.asStateFlow()

    private fun loadEntries(): List<MerchantMemoryEntry> {
        val raw = prefs.getString("merchant_memories_json", null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                return json.decodeFromString<List<MerchantMemoryEntry>>(raw)
            }
        }
        return defaultSeedMemories()
    }

    private fun saveEntries(list: List<MerchantMemoryEntry>) {
        val sorted = list.sortedWith(compareByDescending<MerchantMemoryEntry> { it.hitCount }.thenByDescending { it.updatedAtMillis })
            .take(300)
        _memoriesFlow.value = sorted
        prefs.edit().putString("merchant_memories_json", json.encodeToString(sorted)).apply()
    }

    /**
     * 当用户保存或编辑交易时自动自学习商户偏好
     */
    fun learnFromTransaction(tx: TransactionEntity) {
        if (tx.type != TransactionType.EXPENSE && tx.type != TransactionType.INCOME) return
        val normalized = normalizeMerchantKey(tx.merchant)
        if (normalized.length < 2 || normalized in GENERIC_MERCHANT_STOPWORDS) return
        if (tx.category.isBlank()) return

        val current = _memoriesFlow.value.toMutableList()
        val idx = current.indexOfFirst { it.merchantKeyword.equals(normalized, ignoreCase = true) }
        if (idx >= 0) {
            val existing = current[idx]
            current[idx] = existing.copy(
                category = tx.category,
                preferredWalletId = tx.sourceWalletId ?: tx.targetWalletId ?: existing.preferredWalletId,
                hitCount = existing.hitCount + 1,
                updatedAtMillis = System.currentTimeMillis()
            )
        } else {
            current.add(
                0,
                MerchantMemoryEntry(
                    merchantKeyword = normalized,
                    category = tx.category,
                    preferredWalletId = tx.sourceWalletId ?: tx.targetWalletId,
                    hitCount = 1,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
        saveEntries(current)
    }

    fun upsertMemory(keyword: String, category: String, preferredWalletId: Long? = null) {
        val normalized = normalizeMerchantKey(keyword)
        if (normalized.length < 2 || category.isBlank()) return
        val current = _memoriesFlow.value.toMutableList()
        val idx = current.indexOfFirst { it.merchantKeyword.equals(normalized, ignoreCase = true) }
        if (idx >= 0) {
            val old = current[idx]
            current[idx] = old.copy(
                category = category,
                preferredWalletId = preferredWalletId ?: old.preferredWalletId,
                hitCount = old.hitCount + 1,
                updatedAtMillis = System.currentTimeMillis()
            )
        } else {
            current.add(
                0,
                MerchantMemoryEntry(
                    merchantKeyword = normalized,
                    category = category,
                    preferredWalletId = preferredWalletId,
                    hitCount = 1,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
        saveEntries(current)
    }

    fun deleteMemory(keyword: String) {
        val updated = _memoriesFlow.value.filterNot { it.merchantKeyword.equals(keyword, ignoreCase = true) }
        saveEntries(updated)
    }

    /**
     * 当分类被重命名或删除迁移时，同步级联更新商户记忆库中的目标分类
     */
    fun migrateCategoryCascade(
        oldCategory: String,
        newCategory: String,
        includeSubPrefix: Boolean,
        preserveSubSuffix: Boolean
    ) {
        var changed = false
        val updated = _memoriesFlow.value.map { entry ->
            val mapped = CategoryCatalog.remapCascadedCategory(
                current = entry.category,
                oldCategory = oldCategory,
                newCategory = newCategory,
                includeSubPrefix = includeSubPrefix,
                preserveSubSuffix = preserveSubSuffix
            )
            if (mapped != null) {
                changed = true
                entry.copy(category = mapped, updatedAtMillis = System.currentTimeMillis())
            } else {
                entry
            }
        }
        if (changed) {
            saveEntries(updated)
        }
    }

    /**
     * 在原始文本或提取出的商户名中匹配已学习的商户记忆
     */
    fun matchMemory(rawText: String, extractedMerchant: String = ""): MerchantMemoryEntry? {
        val entries = _memoriesFlow.value
        if (extractedMerchant.isNotBlank()) {
            val cleanMerchant = normalizeMerchantKey(extractedMerchant)
            entries.firstOrNull {
                cleanMerchant.contains(it.merchantKeyword, ignoreCase = true) ||
                    it.merchantKeyword.contains(cleanMerchant, ignoreCase = true)
            }?.let { return it }
        }
        return entries
            .sortedByDescending { it.merchantKeyword.length }
            .firstOrNull { rawText.contains(it.merchantKeyword, ignoreCase = true) }
    }

    private fun normalizeMerchantKey(raw: String): String {
        return raw.trim()
            .replace(Regex("""\([^)]*\)|（[^）]*）"""), "")
            .replace(Regex("""(有限公司|股份有限公司|专营店|旗舰店|官方店|餐饮店|便利店)$"""), "")
            .trim()
    }

    companion object {
        private val GENERIC_MERCHANT_STOPWORDS = setOf(
            "日常商户", "商户退款", "个人账户间流转", "信用账户还款", "收入入账",
            "朋友AA/报销收款", "平账校准", "余额校准", "微信支付", "支付宝"
        )

        private fun defaultSeedMemories(): List<MerchantMemoryEntry> = listOf(
            MerchantMemoryEntry("瑞幸咖啡", "餐饮-咖啡", 5L, 8),
            MerchantMemoryEntry("星巴克", "餐饮-咖啡", 1L, 5),
            MerchantMemoryEntry("Manner", "餐饮-咖啡", 5L, 4),
            MerchantMemoryEntry("霸王茶姬", "餐饮-奶茶", 4L, 5),
            MerchantMemoryEntry("喜茶", "餐饮-奶茶", 4L, 4),
            MerchantMemoryEntry("蜜雪冰城", "餐饮-奶茶", 4L, 4),
            MerchantMemoryEntry("唐久", "餐饮-零食", 5L, 6),
            MerchantMemoryEntry("盒马", "餐饮-买菜", 5L, 7),
            MerchantMemoryEntry("山姆会员商店", "购物-超市", 1L, 6),
            MerchantMemoryEntry("叮咚买菜", "餐饮-买菜", 4L, 4),
            MerchantMemoryEntry("滴滴出行", "交通-打车", 4L, 6),
            MerchantMemoryEntry("12306", "交通-火车", 1L, 4),
            MerchantMemoryEntry("GitHub", "应用软件", 1L, 3),
            MerchantMemoryEntry("OpenAI", "应用软件", 1L, 3)
        )
    }
}

/**
 * 带正权重与「排除词一票否决」的离线分类评分引擎（对标 blue-whale-keeper + AutoRuleSubmit + double-entry-generator）：
 * - 强信号词 (+10)：直接指明消费场景（如“外卖”、“打车”、“地铁”、“水电费”、“挂号”）
 * - 品牌词 (+8)：知名连锁品牌与平台（如“瑞幸”、“滴滴”、“盒马”、“山姆”）
 * - 品类词 (+6)：商品词（如“拿铁”、“美式”、“感冒药”、“猫粮”）
 * - 排除词 (-999 一票否决)：例如“美团外卖”命中“外卖”排除词后，一票否决“美团->旅行/娱乐”，精准路由到“餐饮-三餐”；
 *   “美团打车”命中“打车”排除词后，一票否决“美团->餐饮”，精准路由到“交通-打车”；
 *   “美团买药/京东大药房”命中“买药/药房”后，一票否决“餐饮/购物”，精准路由到“医疗-买药”。
 */
object WeightedCategoryClassifier {

    data class WeightedCategoryRule(
        val category: String,
        val strongKeywords: List<String> = emptyList(),
        val brandKeywords: List<String> = emptyList(),
        val itemKeywords: List<String> = emptyList(),
        val excludeKeywords: List<String> = emptyList()
    )

    private val rules: List<WeightedCategoryRule> = listOf(
        WeightedCategoryRule(
            category = "餐饮-咖啡",
            strongKeywords = listOf("咖啡", "Coffee"),
            brandKeywords = listOf("瑞幸", "Luckin", "星巴克", "Starbucks", "Manner", "库迪", "Cotti", "M Stand", "Tim Hortons", "皮爷"),
            itemKeywords = listOf("拿铁", "美式", "卡布奇诺", "冷萃", "馥芮白", "摩卡")
        ),
        WeightedCategoryRule(
            category = "餐饮-奶茶",
            strongKeywords = listOf("奶茶", "果茶", "茶饮"),
            brandKeywords = listOf("喜茶", "奈雪", "霸王茶姬", "茶百道", "古茗", "蜜雪冰城", "一点点", "沪上阿姨", "茶颜悦色", "CoCo都可"),
            itemKeywords = listOf("珍珠奶茶", "芝士茶", "柠檬茶", "杨枝甘露", "伯牙绝弦")
        ),
        WeightedCategoryRule(
            category = "餐饮-买菜",
            strongKeywords = listOf("买菜", "生鲜", "菜市场", "农贸"),
            brandKeywords = listOf("盒马", "叮咚买菜", "朴朴超市", "小象超市", "美团买菜", "钱大妈", "百果园"),
            itemKeywords = listOf("蔬菜", "猪肉", "牛肉", "鸡蛋", "海鲜", "生鲜配送"),
            excludeKeywords = listOf("打车", "机票", "酒店")
        ),
        WeightedCategoryRule(
            category = "餐饮-零食",
            strongKeywords = listOf("零食", "便利店", "甜品", "烘焙", "面包"),
            brandKeywords = listOf("唐久", "全家", "FamilyMart", "罗森", "Lawson", "7-Eleven", "711", "便利蜂", "美宜佳", "良品铺子", "三只松鼠", "零食很忙", "好利来", "鲍师傅"),
            itemKeywords = listOf("薯片", "巧克力", "饼干", "冰淇淋", "关东煮", "烤肠")
        ),
        WeightedCategoryRule(
            category = "餐饮-水果",
            strongKeywords = listOf("水果店", "鲜果"),
            brandKeywords = listOf("百果园", "鲜丰水果", "切果NOW"),
            itemKeywords = listOf("榴莲", "车厘子", "草莓", "西瓜", "蓝莓", "果切")
        ),
        WeightedCategoryRule(
            category = "餐饮-三餐",
            strongKeywords = listOf("外卖", "餐饮", "堂食", "点餐", "快餐", "火锅", "烧烤", "拉面", "米线", "早餐", "午餐", "晚餐", "夜宵"),
            brandKeywords = listOf("美团", "饿了么", "麦当劳", "肯德基", "KFC", "必胜客", "汉堡王", "萨莉亚", "海底捞", "老乡鸡", "真功夫", "太二", "西贝", "兰湘子", "袁记云饺", "霸王餐"),
            itemKeywords = listOf("套餐", "盖饭", "牛肉面", "麻辣烫", "黄焖鸡", "猪脚饭", "轻食"),
            excludeKeywords = listOf("打车", "单车", "买药", "药房", "酒店", "民宿", "机票", "火车票", "电影票")
        ),
        WeightedCategoryRule(
            category = "交通-打车",
            strongKeywords = listOf("打车", "网约车", "快车", "专车", "出租车", "顺风车", "代驾"),
            brandKeywords = listOf("滴滴", "高德打车", "曹操出行", "T3出行", "享道出行", "花小猪", "美团打车", "阳光出行", "首汽约车"),
            itemKeywords = listOf("行程费", "车费", "高速费")
        ),
        WeightedCategoryRule(
            category = "交通-地铁",
            strongKeywords = listOf("地铁", "轨道交通", "乘车码"),
            brandKeywords = listOf("地铁通行", "申通地铁", "北京一卡通", "深圳通", "羊城通", "长安通"),
            itemKeywords = listOf("地铁站", "进站", "出站"),
            excludeKeywords = listOf("公交车")
        ),
        WeightedCategoryRule(
            category = "交通-公交",
            strongKeywords = listOf("公交", "巴士", "共享单车", "单车"),
            brandKeywords = listOf("哈啰", "美团单车", "青桔单车"),
            itemKeywords = listOf("公交乘车", "骑行卡")
        ),
        WeightedCategoryRule(
            category = "交通-火车",
            strongKeywords = listOf("火车票", "高铁", "动车", "铁路"),
            brandKeywords = listOf("12306", "中铁网络", "铁路12306"),
            itemKeywords = listOf("二等座", "一等座", "候补购票", "改签费"),
            excludeKeywords = listOf("电动车", "充气宝", "充气泵", "打气筒", "轮胎", "车载")
        ),
        WeightedCategoryRule(
            category = "交通-飞机",
            strongKeywords = listOf("机票", "航空", "航班"),
            brandKeywords = listOf("东方航空", "南方航空", "中国国航", "海南航空", "春秋航空", "吉祥航空", "携程机票", "航旅纵横"),
            itemKeywords = listOf("经济舱", "值机", "机建燃油")
        ),
        WeightedCategoryRule(
            category = "交通-加油",
            strongKeywords = listOf("加油", "充电桩", "特来电", "星星充电"),
            brandKeywords = listOf("中国石化", "中国石油", "壳牌", "中海油", "小桔充电", "蔚来能源"),
            itemKeywords = listOf("92号", "95号", "柴油", "充电服务费")
        ),
        WeightedCategoryRule(
            category = "交通-停车",
            strongKeywords = listOf("停车费", "停车场", "泊车", "ETC"),
            brandKeywords = listOf("捷停车", "ETCP", "蜜蜂停车"),
            itemKeywords = listOf("临停", "车牌缴费")
        ),
        WeightedCategoryRule(
            category = "医疗-买药",
            strongKeywords = listOf("买药", "药房", "大药房", "药店", "购药"),
            brandKeywords = listOf("老百姓大药房", "益丰大药房", "一心堂", "大参林", "海王星辰", "京东大药房", "美团买药", "阿里健康大药房"),
            itemKeywords = listOf("感冒灵", "布洛芬", "维生素", "创可贴", "眼药水", "阿莫西林")
        ),
        WeightedCategoryRule(
            category = "医疗-门诊",
            strongKeywords = listOf("医院", "门诊", "挂号", "体检", "牙科", "口腔", "医保局"),
            brandKeywords = listOf("协和医院", "华西医院", "瑞尔齿科", "美年大健康", "爱康国宾"),
            itemKeywords = listOf("诊疗费", "化验费", "CT检查", "洗牙", "补牙")
        ),
        WeightedCategoryRule(
            category = "购物-超市",
            strongKeywords = listOf("超市", "卖场", "会员店", "仓储超市"),
            brandKeywords = listOf("山姆", "Sam's Club", "Costco", "开市客", "沃尔玛", "永辉超市", "大润发", "物美", "华润万家", "奥乐齐", "ALDI"),
            itemKeywords = listOf("购物袋", "量贩")
        ),
        WeightedCategoryRule(
            category = "购物-京东",
            strongKeywords = listOf("京东商城", "京东自营", "京东支付"),
            brandKeywords = listOf("京东", "JD.COM"),
            excludeKeywords = listOf("京东大药房", "白条还款")
        ),
        WeightedCategoryRule(
            category = "购物-淘宝",
            strongKeywords = listOf("淘宝", "天猫", "闲鱼"),
            brandKeywords = listOf("天猫超市", "淘宝网", "天猫国际"),
            excludeKeywords = listOf("阿里健康大药房", "飞猪")
        ),
        WeightedCategoryRule(
            category = "购物-拼多多",
            strongKeywords = listOf("拼多多", "多多支付"),
            brandKeywords = listOf("多多买菜", "百亿补贴")
        ),
        WeightedCategoryRule(
            category = "数码",
            strongKeywords = listOf("数码", "电子产品", "手机配件", "电脑配件", "充气宝", "充气泵", "智能门锁", "指纹锁"),
            brandKeywords = listOf("Apple Store", "小米之家", "华为授权体验店", "大疆", "DJI", "绿联", "安克", "Anker"),
            itemKeywords = listOf("数据线", "充电器", "无线充", "手机支架", "快充", "耳机", "AirPods", "键盘", "鼠标", "显示器", "硬盘", "智能锁", "电子锁", "路由器")
        ),
        WeightedCategoryRule(
            category = "应用软件",
            strongKeywords = listOf("软件订阅", "云服务", "API充值", "会员订阅", "App Store"),
            brandKeywords = listOf("GitHub", "OpenAI", "Claude", "Anthropic", "DeepSeek", "Google Cloud", "iCloud", "1Password", "Notion", "JetBrains"),
            itemKeywords = listOf("Token", "Plus会员", "Pro订阅", "云盘")
        ),
        WeightedCategoryRule(
            category = "娱乐",
            strongKeywords = listOf("电影票", "演出", "游戏充值", "视频会员", "音乐会员", "网咖", "台球", "剧本杀", "KTV"),
            brandKeywords = listOf("猫眼", "淘票票", "Bilibili", "哔哩哔哩", "网易云音乐", "QQ音乐", "爱奇艺", "腾讯视频", "优酷", "芒果TV", "Steam", "腾讯游戏", "米哈游", "泡泡玛特"),
            itemKeywords = listOf("月卡", "VIP会员", "盲盒", "电影院")
        ),
        WeightedCategoryRule(
            category = "住房-房租",
            strongKeywords = listOf("房租", "租房", "押金", "房东"),
            brandKeywords = listOf("自如", "泊寓", "龙湖冠寓", "相寓")
        ),
        WeightedCategoryRule(
            category = "住房-水电煤",
            strongKeywords = listOf("电费", "水费", "燃气费", "暖气费", "生活缴费"),
            brandKeywords = listOf("国家电网", "南方电网", "自来水公司", "燃气集团", "网上国网")
        ),
        WeightedCategoryRule(
            category = "住房-物业",
            strongKeywords = listOf("物业费", "物业管理", "垃圾处理费"),
            brandKeywords = listOf("万科物业", "万物云", "碧桂园服务", "绿城服务", "保利物业")
        ),
        WeightedCategoryRule(
            category = "通讯",
            strongKeywords = listOf("话费", "流量包", "宽带费", "手机充值"),
            brandKeywords = listOf("中国移动", "中国联通", "中国电信", "中国广电")
        ),
        WeightedCategoryRule(
            category = "服饰",
            strongKeywords = listOf("服装", "女装", "男装", "运动鞋", "羽绒服", "跑鞋", "跑步鞋"),
            brandKeywords = listOf("优衣库", "UNIQLO", "ZARA", "Nike", "耐克", "Adidas", "阿迪达斯", "安踏", "李宁", "迪卡侬", "UR", "波司登"),
            itemKeywords = listOf("球鞋", "休闲鞋", "板鞋", "拖鞋", "凉鞋", "T恤", "裤子", "外套", "卫衣", "夹克", "衬衫", "连衣裙", "短袖", "袜子")
        ),
        WeightedCategoryRule(
            category = "护肤",
            strongKeywords = listOf("护肤品", "面膜", "防晒", "洁面"),
            brandKeywords = listOf("丝芙兰", "屈臣氏", "珀莱雅", "兰蔻", "雅诗兰黛", "科颜氏", "理肤泉")
        ),
        WeightedCategoryRule(
            category = "宠物",
            strongKeywords = listOf("宠物医院", "猫粮", "狗粮", "猫砂", "宠物洗澡"),
            brandKeywords = listOf("瑞鹏宠物", "新瑞鹏", "皇家宠物", "渴望")
        ),
        WeightedCategoryRule(
            category = "运动",
            strongKeywords = listOf("健身房", "游泳馆", "羽毛球", "网球", "瑜伽", "私教"),
            brandKeywords = listOf("乐刻运动", "Keep", "超级猩猩", "威尔仕")
        ),
        WeightedCategoryRule(
            category = "旅行",
            strongKeywords = listOf("酒店", "民宿", "景区门票", "度假"),
            brandKeywords = listOf("携程", "去哪儿", "飞猪", "同程旅行", "华住会", "全季酒店", "亚朵酒店", "汉庭", "万豪", "希尔顿"),
            excludeKeywords = listOf("火车票", "12306")
        ),
        WeightedCategoryRule(
            category = "日用",
            strongKeywords = listOf("日用百货", "共享充电宝", "干洗", "家政", "快递费", "垃圾袋", "马桶垫", "破窗器", "安全锤", "茶仓杯", "保温杯", "洗衣液", "纸巾", "抽纸", "洗发", "护发", "沐浴露"),
            brandKeywords = listOf("名创优品", "MINISO", "无印良品", "MUJI", "怪兽充电", "街电", "小电", "顺丰速运", "京东快递", "菜鸟", "多芬", "宝洁", "海飞丝"),
            itemKeywords = listOf("牙膏", "洗发水", "身体乳", "洗手液", "茶杯", "水杯", "收纳", "保鲜膜", "充电宝租借")
        )
    )

    fun classifyExpense(rawText: String, merchant: String = ""): Pair<String, Float> {
        val combined = "$merchant $rawText"
        val activeExpenseCats = CategoryCatalog.expenseCategories
        var bestCategory = activeExpenseCats.firstOrNull()?.name ?: "餐饮"
        var bestScore = 0

        for (rule in rules) {
            if (rule.excludeKeywords.any { combined.contains(it, ignoreCase = true) }) {
                continue
            }
            var score = 0
            rule.strongKeywords.forEach { kw ->
                if (combined.contains(kw, ignoreCase = true)) score += 10
            }
            rule.brandKeywords.forEach { kw ->
                if (combined.contains(kw, ignoreCase = true)) score += 8
            }
            rule.itemKeywords.forEach { kw ->
                if (combined.contains(kw, ignoreCase = true)) score += 6
            }
            if (score > bestScore) {
                bestScore = score
                bestCategory = rule.category
            }
        }

        // 动态感知用户在 CategoryCatalog 中新增或修改的一级/二级分类名称
        for (cat in activeExpenseCats) {
            for (sub in cat.subCategories) {
                if (sub.name.length >= 2 && combined.contains(sub.name, ignoreCase = true)) {
                    val customScore = 14
                    if (customScore > bestScore) {
                        bestScore = customScore
                        bestCategory = "${cat.name}-${sub.name}"
                    }
                }
            }
            if (cat.name.length >= 2 && combined.contains(cat.name, ignoreCase = true)) {
                val customScore = 11
                if (customScore > bestScore) {
                    bestScore = customScore
                    bestCategory = cat.name
                }
            }
        }

        // 若内置规则产出的分类已被用户重命名或删除，回退到当前有效分类
        val parts = bestCategory.split("-")
        val mainPart = parts.firstOrNull().orEmpty()
        val subPart = parts.getOrNull(1)
        val matchedMain = activeExpenseCats.firstOrNull { it.name == mainPart }
        if (matchedMain == null) {
            val matchedBySub = if (!subPart.isNullOrBlank()) {
                activeExpenseCats.firstNotNullOfOrNull { c ->
                    c.subCategories.firstOrNull { it.name == subPart }?.let { "${c.name}-${it.name}" }
                }
            } else null
            bestCategory = matchedBySub
                ?: activeExpenseCats.firstOrNull { it.name == "其他" }?.name
                ?: activeExpenseCats.firstOrNull()?.name
                ?: bestCategory
        } else if (!subPart.isNullOrBlank() && matchedMain.subCategories.none { it.name == subPart }) {
            bestCategory = matchedMain.name
        }

        val confidence = when {
            bestScore >= 16 -> 0.96f
            bestScore >= 10 -> 0.92f
            bestScore >= 6 -> 0.88f
            else -> 0.80f
        }
        return bestCategory to confidence
    }
}
