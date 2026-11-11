package com.promenar.infinity.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

/**
 * 页面返回是否应弹出 [entryId] 所在的页面：它仍是栈顶，且下面还有页面。
 *
 * 页面弹出后会在退场动画里继续显示一小段时间，其中的返回键仍可点按、仍可能持有键盘焦点；
 * 连点返回或连按硬件回车时第二次返回若照常执行，会把下面的页面直至起始页一并弹出，
 * 返回栈清空后导航区一片空白，系统返回直接退到桌面。
 */
internal fun canPopFrom(currentEntryId: String?, entryId: String, hasPrevious: Boolean): Boolean =
    currentEntryId == entryId && hasPrevious

/** 只在 [entry] 仍是栈顶且下面还有页面时返回（见 [canPopFrom]）；返回是否弹出了页面。 */
internal fun NavController.popBackStackFrom(entry: NavBackStackEntry): Boolean =
    canPopFrom(currentBackStackEntry?.id, entry.id, previousBackStackEntry != null) && popBackStack()
