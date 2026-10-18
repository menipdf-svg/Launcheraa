package com.meni.pixellauncher

/** מתגים של יכולות בלאנצ'ר. key נשמר בהגדרות */
enum class Feature(val default: Boolean) {
    HOME_SEARCH(true),
    NOTIFICATION_DOTS(true),
    GLANCE_HEBREW_DATE(true),
    GLANCE_WEATHER(false),
    GLANCE_SUNSET(false),
    GLANCE_CALENDAR(false),
    GLANCE_MEDIA(true),
    SUGGESTIONS(true),
    NEW_BADGES(true),
    CONTEXT_DOCK(false),
    SEARCH_CONTACTS(false),
    SEARCH_SETTINGS(true),
    SEARCH_CALCULATOR(true),
    SEARCH_WEB(true),
    BLUR(true)
}
