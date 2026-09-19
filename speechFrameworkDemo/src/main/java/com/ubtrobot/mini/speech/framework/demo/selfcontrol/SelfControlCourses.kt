package com.ubtrobot.mini.speech.framework.demo.selfcontrol

/**
 * Course / sách con / unit — port từ otto_course_units.h.
 */
object SelfControlCourses {
    data class UnitList(val name: String, val units: List<String>)
    data class CourseDef(
        val id: String,
        val displayName: String,
        val units: List<String> = emptyList(),
        val subs: List<UnitList> = emptyList(),
    )

    val COURSES: List<CourseDef> = listOf(
        CourseDef("custom", "Tự cấu hình"),
        CourseDef(
            "explorers", "Explorers",
            subs = listOf(
                UnitList("Pre-Discovery A", listOf(
                    "Unit Routines", "Unit Welcome The Magic Forest", "Unit One Let's Draw",
                    "Unit Two Let's Play", "Unit Review One", "Unit Three The Big Monster",
                    "Unit Special Day One", "Unit Special Day Two"
                )),
                UnitList("Pre-Discovery B", listOf(
                    "Unit Routines Review", "Unit Four My Family", "Unit Review Two",
                    "Unit Five Where's My Bird?", "Unit Midterm Test", "Unit Six Let's Tidy Up!",
                    "Unit Review Three", "Unit Special Day Three", "Unit Special Day Four",
                    "Unit Review Four", "Unit Final Test"
                )),
                UnitList("PRE-SPARK A", listOf(
                    "Unit Routines", "Unit Welcome Let's Remember", "Unit One The Surprise",
                    "Unit Two The Brown Mouse", "Unit Review One", "Unit Three Where Is Greenman?",
                    "Unit Special Day One", "Unit Special Day Two"
                )),
                UnitList("SPARK Two A", listOf(
                    "Unit Our Friends", "Unit One Weather", "Unit Two Our Families",
                    "Unit Three Our Places", "Unit Four Our Colors and Shapes"
                )),
                UnitList("SPARK Three A", listOf(
                    "Unit Hello Again", "Unit One Our Day", "Unit Two Dinner Time",
                    "Unit Three With My Friends", "Unit Four Our Colors and Shapes",
                    "Unit Four Our Faces"
                )),
            )
        ),
        CourseDef(
            "younginnovators", "Young Innovators",
            subs = listOf(
                UnitList("QUEST One A", listOf(
                    "Unit One Hello", "Unit Two My School", "Unit Three Favourite Toys",
                    "Unit Four My Family", "Unit Five Our Pets", "Unit Six My Face"
                )),
                UnitList("QUEST Two B", listOf(
                    "Unit Seven At the Farm", "Unit Eight My Town", "Unit Nine Our Clothes",
                    "Unit Ten Our Hobbies", "Unit Eleven My Birthday", "Unit Twelve Our Holiday"
                )),
                UnitList("QUEST Three A", listOf(
                    "Unit Hello", "Unit One Family Matters", "Unit Two Home Sweet Home",
                    "Unit Values One and Two", "Unit Three A Day in the Life", "Unit Four In the City",
                    "Unit Values Three and Four", "Unit YLE Movers Listening Skills Practice"
                )),
            )
        ),
        CourseDef(
            "futureleaders", "Future Leaders",
            subs = listOf(
                UnitList("Summit One A", listOf(
                    "Unit Welcome", "Unit One Having a Good Time", "Unit Two Spending Money",
                    "Unit Three We Are What We Eat", "Unit Four All in the Family",
                    "Unit Five No Place Like Home", "Unit Six Friends Forever"
                )),
            )
        ),
        CourseDef("ielts", "IELTS", units = listOf("Unit 1", "Unit 2", "Unit 3", "Unit 4", "Unit 5", "Unit 6")),
        CourseDef("toeic", "TOEIC", units = listOf("Unit 1", "Unit 2", "Unit 3", "Unit 4", "Unit 5", "Unit 6")),
        CourseDef("manual_mac", "Tự nhập MAC"),
        CourseDef("daily_chat", "Giao tiếp hằng ngày"),
    )

    fun get(idx: Int): CourseDef? = COURSES.getOrNull(idx)

    fun activeUnitList(courseIdx: Int, subIdx: Int): UnitList? {
        val c = get(courseIdx) ?: return null
        if (c.subs.isNotEmpty()) {
            val s = subIdx.coerceIn(0, c.subs.lastIndex)
            return c.subs[s]
        }
        if (c.units.isEmpty()) return null
        return UnitList(c.displayName, c.units)
    }

    fun resolveUnitName(courseIdx: Int, subIdx: Int, selected: String): String {
        val list = activeUnitList(courseIdx, subIdx) ?: return selected
        val n = selected.toIntOrNull() ?: return selected
        if (n in 1..list.units.size) return list.units[n - 1]
        return selected
    }
}
