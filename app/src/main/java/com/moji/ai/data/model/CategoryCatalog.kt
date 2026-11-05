package com.moji.ai.data.model

data class CategoryDef(
    val name: String,
    val iconKey: String,
    val subCategories: List<String> = emptyList()
)

object CategoryCatalog {
    val expenseCategories: List<CategoryDef> = listOf(
        CategoryDef("餐饮", "restaurant", listOf("三餐", "零食", "水果", "蔬菜", "咖啡奶茶", "外卖")),
        CategoryDef("购物", "shopping", listOf("日用百货", "超市生鲜", "网购", "家居")),
        CategoryDef("服饰", "clothing", listOf("衣服", "鞋帽", "配饰")),
        CategoryDef("日用", "daily", listOf("纸品清洁", "洗护", "生活用品")),
        CategoryDef("数码", "digital", listOf("数码配件", "手机电脑", "Apple礼品卡")),
        CategoryDef("美妆", "beauty", listOf("彩妆", "美容美甲")),
        CategoryDef("护肤", "skincare", listOf("护肤品", "个人护理")),
        CategoryDef("应用软件", "app", listOf("会员订阅", "iCloud", "效率工具", "AI服务")),
        CategoryDef("住房", "home", listOf("房租", "物业费", "水电燃气", "维修")),
        CategoryDef("交通", "transport", listOf("地铁公交", "打车", "火车机票", "共享单车")),
        CategoryDef("娱乐", "entertainment", listOf("电影演出", "游戏", "运动健身", "聚会")),
        CategoryDef("医疗", "medical", listOf("买药", "门诊挂号", "体检")),
        CategoryDef("通讯", "phone", listOf("话费流量", "宽带", "快递邮费")),
        CategoryDef("汽车", "car", listOf("加油充电", "停车过路", "保养保险")),
        CategoryDef("学习", "study", listOf("书籍", "课程培训", "考试"))
    )

    val incomeCategories: List<CategoryDef> = listOf(
        CategoryDef("工资", "salary", listOf("基本工资", "绩效提成", "年终奖")),
        CategoryDef("兼职", "parttime", listOf("副业收入", "稿费咨询")),
        CategoryDef("理财", "invest", listOf("基金股票", "利息分红", "余额宝收益")),
        CategoryDef("红包", "gift", listOf("微信红包", "人情礼金")),
        CategoryDef("二手闲置", "secondhand", listOf("闲鱼转卖")),
        CategoryDef("其他收入", "other_income", emptyList())
    )
}
