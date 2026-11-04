package com.promenar.infinity.ui.chat

internal fun canImportIntoSessionLocation(sessionId: String, locationId: String?, isImporting: Boolean): Boolean =
    !isImporting && locationId == "session:$sessionId"
