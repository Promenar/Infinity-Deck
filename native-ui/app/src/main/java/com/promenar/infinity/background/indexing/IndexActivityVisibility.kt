package com.promenar.infinity.background.indexing

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 跟随实际可见 Activity，避免进程生命周期的后台延迟影响唤醒锁。 */
internal class IndexActivityVisibility(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private val activities = IndexVisibleActivitySet<Activity>()
    private val mutableVisible = MutableStateFlow(false)
    val visible = mutableVisible.asStateFlow()

    fun start() = application.registerActivityLifecycleCallbacks(this)

    override fun onActivityStarted(activity: Activity) { mutableVisible.value = activities.started(activity) }
    override fun onActivityStopped(activity: Activity) { mutableVisible.value = activities.stopped(activity) }
    override fun onActivityDestroyed(activity: Activity) { mutableVisible.value = activities.stopped(activity) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
