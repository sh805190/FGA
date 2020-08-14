package com.mathewsachin.fategrandautomata.scripts.prefs

import com.mathewsachin.fategrandautomata.scripts.enums.RefillResourceEnum

interface IRefillPreferences {
    val enabled: Boolean
    var repetitions: Int
    val resource: RefillResourceEnum
    val autoDecrement: Boolean

    val shouldLimitRuns: Boolean
    val limitRuns: Int
}