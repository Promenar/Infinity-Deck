package com.promenar.infinity.ui.lab

import com.promenar.infinity.ui.lab.catalog.LabCatalog

/** 深链打开的实验室版本。 */
data class LabDeepLink(val versionId: String, val record: Boolean) {
    companion object {
        /** 开发者模式未开启或目录里没有该版本时返回 null，深链静默失效。 */
        fun resolve(versionId: String, record: Boolean, developerModeEnabled: Boolean): LabDeepLink? =
            if (developerModeEnabled && LabCatalog.version(versionId) != null) LabDeepLink(versionId, record) else null
    }
}
