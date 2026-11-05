package com.promenar.infinity.background.indexing

import java.util.Collections
import java.util.IdentityHashMap

/** 多窗口下任一 Activity 可见即视为可见；按实例身份去重。 */
internal class IndexVisibleActivitySet<T : Any> {
    private val activities = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

    fun started(activity: T): Boolean {
        activities.add(activity)
        return activities.isNotEmpty()
    }

    fun stopped(activity: T): Boolean {
        activities.remove(activity)
        return activities.isNotEmpty()
    }
}
