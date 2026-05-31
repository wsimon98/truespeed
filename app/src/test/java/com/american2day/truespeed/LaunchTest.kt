package com.american2day.truespeed

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Inflates MainActivity on the JVM so layout/onCreate crashes surface here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LaunchTest {
    @Test
    fun activityLaunches() {
        Robolectric.buildActivity(MainActivity::class.java).setup()
    }
}
