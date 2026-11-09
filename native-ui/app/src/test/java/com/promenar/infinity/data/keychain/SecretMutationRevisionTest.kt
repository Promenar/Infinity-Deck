package com.promenar.infinity.data.keychain

import com.promenar.infinity.data.security.SecretMutationRevision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SecretMutationRevisionTest {
    @Test fun `交叠写入期间为不稳定且结束后修订变化`() = runBlocking {
        val before = SecretMutationRevision.current()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            SecretMutationRevision.suspending {
                entered.complete(Unit)
                finish.await()
            }
        }
        entered.await()
        assertEquals(1L, SecretMutationRevision.current() and 1L)
        SecretMutationRevision.blocking { assertEquals(1L, SecretMutationRevision.current() and 1L) }
        finish.complete(Unit)
        job.join()
        val after = SecretMutationRevision.current()
        assertEquals(0L, after and 1L)
        assertNotEquals(before, after)
    }
}
