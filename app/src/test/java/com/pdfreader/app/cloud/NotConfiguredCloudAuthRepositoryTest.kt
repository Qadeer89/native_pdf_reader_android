package com.pdfreader.app.cloud

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NotConfiguredCloudAuthRepository] is the stub [CloudAuthRepository] used until a real
 * Firebase project (DEC-003) is connected — this locks in the two things the rest of the
 * app can rely on from it in the meantime: it never pretends to be signed in, and it fails
 * clearly rather than silently.
 */
class NotConfiguredCloudAuthRepositoryTest {

    @Test
    fun startsSignedOut() {
        val repository = NotConfiguredCloudAuthRepository()
        assertNull(repository.currentUser.value)
    }

    @Test
    fun signInAlwaysFailsRatherThanPretendingToSucceed() = runTest {
        val repository = NotConfiguredCloudAuthRepository()

        val result = repository.signInWithGoogle("fake-id-token")

        assertTrue("sign-in must fail until a real Firebase project is connected", result.isFailure)
        assertNull(repository.currentUser.value)
    }

    @Test
    fun signOutIsANoOpWhenAlreadySignedOut() = runTest {
        val repository = NotConfiguredCloudAuthRepository()

        repository.signOut()

        assertNull(repository.currentUser.value)
    }
}
