package com.promenar.infinity.onboarding

/**
 * 启动期备份恢复收敛后、业务界面出现前结算向导发起的恢复，每次启动调用一次。
 *
 * [committed] 表示本次启动提交了一份恢复。只有存在向导恢复标记时才会改动引导：提交成功则记为完成，
 * 并把向导里选的语言写回设置（备份会带回导出设备的语言）；未提交（已回滚或暂存已丢弃）则只撤销标记，
 * 引导停在原步骤，回滚提示由启动流程照常弹出。返回引导是否因此完成。
 */
fun settleOnboardingRestore(
    store: OnboardingStateStore,
    committed: Boolean,
    persistLanguage: (String) -> Boolean,
): Boolean {
    if (store.stagedRestoreTxId == null) return false
    if (!store.settleRestore(committed)) return false
    store.state.value.languageCode?.let(persistLanguage)
    return true
}
