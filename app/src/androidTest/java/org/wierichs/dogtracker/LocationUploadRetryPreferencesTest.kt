package org.wierichs.dogtracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationUploadRetryPreferencesTest {
    @Test
    fun serverRetryDeadlinePersistsUntilCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousDeadline = TrackerPreferences.locationUploadRetryAfter(context)
        val deadline = System.currentTimeMillis() + 60_000L

        try {
            assertTrue(TrackerPreferences.setLocationUploadRetryAfter(context, deadline))
            assertEquals(deadline, TrackerPreferences.locationUploadRetryAfter(context))

            assertTrue(TrackerPreferences.clearLocationUploadRetryAfter(context))
            assertNull(TrackerPreferences.locationUploadRetryAfter(context))
        } finally {
            if (previousDeadline == null) {
                TrackerPreferences.clearLocationUploadRetryAfter(context)
            } else {
                TrackerPreferences.setLocationUploadRetryAfter(context, previousDeadline)
            }
        }
    }
}
