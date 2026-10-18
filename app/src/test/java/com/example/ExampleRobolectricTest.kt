package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string and verify bundled html asset`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("The CBT App", appName)

    val htmlContent =
      context.assets.open("index.html").bufferedReader().use { it.readText() }
    assertTrue(htmlContent.contains("The CBT App"))
    assertTrue(htmlContent.contains("__mcqMain"))
  }
}
