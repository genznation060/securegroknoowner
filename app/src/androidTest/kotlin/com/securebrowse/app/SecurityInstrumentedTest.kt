package com.securebrowse.app

import android.app.admin.DevicePolicyManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.securebrowse.app.kiosk.KioskController
import com.securebrowse.app.ui.MainActivity
import com.securebrowse.policy.KioskAssessment
import com.securebrowse.policy.LockPresentation
import com.securebrowse.policy.SessionRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityInstrumentedTest {
    @Test
    fun provisioningConstantsMatchTheFramework() {
        assertEquals(
            KioskAssessment.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
        )
        assertEquals(KioskAssessment.ACTION_GET_PROVISIONING_MODE, DevicePolicyManager.ACTION_GET_PROVISIONING_MODE)
        assertEquals(KioskAssessment.ACTION_ADMIN_POLICY_COMPLIANCE, DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE)
        assertEquals(KioskAssessment.EXTRA_PROVISIONING_MODE, DevicePolicyManager.EXTRA_PROVISIONING_MODE)
        assertEquals(DevicePolicyManager.LOCK_TASK_FEATURE_NONE, SessionRules.FEATURE_NONE)
        assertEquals(DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO, SessionRules.FEATURE_SYSTEM_INFO)
        assertEquals(DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS, SessionRules.FEATURE_NOTIFICATIONS)
    }

    @Test
    fun ordinaryInstallIsNotAKiosk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(KioskController.isDeviceOwner(context))
        assertEquals(LockPresentation.NOT_LOCKED, KioskController.presentation(context))
        assertFalse(KioskAssessment.exitAllowed(adminAuthenticated = false))
    }

    @Test
    fun unapprovedAddressDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.address)).perform(replaceText("https://example.com"), closeSoftKeyboard())
            onView(withId(R.id.goButton)).perform(click())
            onView(withId(R.id.blockedTitle)).check(matches(isDisplayed()))
            onView(withId(R.id.blockedTitle)).check(matches(withText("Website blocked")))
        }
    }
}
