package com.promenar.infinity.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 仅保存连接配置与主机公钥信任，不包含认证材料。 */
@Entity(tableName = "ssh_hosts", indices = [Index(value = ["alias"], unique = true)])
data class SshHostEntity(
    @PrimaryKey val id: String,
    val alias: String,
    val hostname: String,
    @ColumnInfo(defaultValue = "22") val port: Int = 22,
    val username: String,
    @ColumnInfo(name = "auth_type", defaultValue = "'KEY'") val authType: String = "KEY",
    @ColumnInfo(name = "jump_alias") val jumpAlias: String? = null,
    @ColumnInfo(name = "trusted_key_type") val trustedKeyType: String? = null,
    @ColumnInfo(name = "trusted_fingerprint") val trustedFingerprint: String? = null,
    @ColumnInfo(name = "keep_terminal", defaultValue = "0") val keepTerminal: Boolean = false,
    @ColumnInfo(name = "local_forward_port") val localForwardPort: Int? = null,
    @ColumnInfo(name = "forward_host", defaultValue = "'127.0.0.1'") val forwardHost: String = "127.0.0.1",
    @ColumnInfo(name = "forward_port") val forwardPort: Int? = null,
    @ColumnInfo(name = "key_entry_id") val keyEntryId: String? = null,
)
