package com.promenar.infinity.data.local.db.recovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AndroidDualDatabaseSourceContractTest {
    @Test
    fun `retained public v17 beside verified current v18 is normal post promotion state`() {
        assertThat(requiresDualDatabaseRecovery(source(18, CURRENT_V18_IDENTITY))).isFalse()
    }

    @Test
    fun `retained public v17 beside verified current v19 is normal post promotion state`() {
        assertThat(requiresDualDatabaseRecovery(source(19, CURRENT_V19_IDENTITY))).isFalse()
    }

    @Test
    fun `retained public v17 beside verified current v20 is normal post promotion state`() {
        assertThat(requiresDualDatabaseRecovery(source(20, CURRENT_V20_IDENTITY))).isFalse()
    }

    @Test
    fun `untrusted identities do not bypass recovery based on the version alone`() {
        assertThat(requiresDualDatabaseRecovery(source(18, "untrusted-room-identity"))).isTrue()
        assertThat(requiresDualDatabaseRecovery(source(19, CURRENT_V18_IDENTITY))).isTrue()
        assertThat(requiresDualDatabaseRecovery(source(20, CURRENT_V19_IDENTITY))).isTrue()
        assertThat(requiresDualDatabaseRecovery(source(19, CURRENT_V20_IDENTITY))).isTrue()
        assertThat(requiresDualDatabaseRecovery(source(19, "5aa1bdf81ceefffeebbbbb1088436b05"))).isTrue()
        assertThat(requiresDualDatabaseRecovery(source(21, CURRENT_V20_IDENTITY))).isTrue()
    }

    @Test
    fun `精确v21身份不进入旧双库合并`() {
        assertThat(requiresDualDatabaseRecovery(source(21, CURRENT_V21_IDENTITY))).isFalse()
    }

    @Test
    fun `v18到v34拒绝任意交叉版本身份和未知身份`() {
        val identities = mapOf(18 to CURRENT_V18_IDENTITY, 19 to CURRENT_V19_IDENTITY,
            20 to CURRENT_V20_IDENTITY, 21 to CURRENT_V21_IDENTITY, 22 to CURRENT_V22_IDENTITY,
            23 to CURRENT_V23_IDENTITY, 24 to CURRENT_V24_IDENTITY, 25 to CURRENT_V25_IDENTITY,
            26 to CURRENT_V26_IDENTITY, 27 to CURRENT_V27_IDENTITY, 28 to CURRENT_V28_IDENTITY,
            29 to CURRENT_V29_IDENTITY, 30 to CURRENT_V30_IDENTITY, 31 to CURRENT_V31_IDENTITY, 32 to CURRENT_V32_IDENTITY, 33 to CURRENT_V33_IDENTITY, 34 to CURRENT_V34_IDENTITY, 35 to CURRENT_V35_IDENTITY)
        identities.forEach { (version, identity) ->
            assertThat(requiresDualDatabaseRecovery(source(version, identity))).isFalse()
            identities.filterKeys { it != version }.values.forEach { other ->
                assertThat(requiresDualDatabaseRecovery(source(version, other))).isTrue()
            }
            assertThat(requiresDualDatabaseRecovery(source(version, "unknown"))).isTrue()
        }
    }

    @Test
    fun `public v2 and internal v5 still require explicit merge`() {
        assertThat(requiresDualDatabaseRecovery(source(2, PUBLIC_V2_IDENTITY))).isTrue()
        assertThat(requiresDualDatabaseRecovery(source(5, INTERNAL_V5_IDENTITY))).isTrue()
        assertThat(requiresDualDatabaseRecovery(source(20, "unknown"))).isTrue()
    }

    private fun source(version: Int, identity: String): RecoveryDatabaseSource {
        val files = listOf(RecoveryFileFingerprint(CURRENT_DATABASE_NAME, 1, "a".repeat(64)))
        return RecoveryDatabaseSource("current", CURRENT_DATABASE_NAME, version, identity, files, recoveryManifestSha256(files))
    }
}
