package com.moji.ai.data.model

data class CategoryDef(
    val name: String,
    val assetFile: String,
    val subCategories: List<SubCategoryDef> = emptyList()
)

data class SubCategoryDef(
    val name: String,
    val assetFile: String
)

object CategoryCatalog {
    val expenseCategories: List<CategoryDef> = listOf(
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

    val incomeCategories: List<CategoryDef> = listOf(
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
}
