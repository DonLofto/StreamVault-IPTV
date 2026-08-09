package com.streamvault.data.sync

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.kotlin.mock

class EpgAdmissionPolicyTest {

    @Test
    fun `mockContext_thatCannotReportConstraints_isAdmitted`() {
        val context: Context = mock()
        val policy = EpgAdmissionPolicy(context)
        assertThat(policy.isAdmitted()).isTrue()
        assertThat(policy.rejectionReason()).isNull()
    }
}
