package com.airbnb.paris.proxies

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ViewProxyTest {

    private lateinit var context: Context
    private lateinit var view: View
    private lateinit var proxy: ViewProxy

    @Before
    fun setup() {
        context = RuntimeEnvironment.application
        view = View(context)
        proxy = ViewProxy(view)
    }

    @Test
    fun setBackground_null() {
        // Since null is the default first set the background to something else
        view.background = ColorDrawable(Color.WHITE)
        proxy.setBackground(null)
        assertEquals(null, view.background)
    }

    @Test
    fun setContentDescription_null() {
        // Since null is the default first set the content description to something else
        view.contentDescription = "Hello"
        proxy.setContentDescription(null)
        assertEquals(null, view.contentDescription)
    }

    @Test
    fun setForeground_null() {
        // Since null is the default first set the foreground to something else
        view.foreground = ColorDrawable(Color.WHITE)
        proxy.setForeground(null)
        assertEquals(null, view.foreground)
    }

    @Test
    fun setVisibility_visibleProgrammatic() {
        // Since visible is the default first set the visibility to something else
        view.visibility = View.GONE
        proxy.setVisibility(View.VISIBLE)
        assertEquals(View.VISIBLE, view.visibility)
    }

    @Test
    fun setVisibility_visibleXml() {
        // Since visible is the default first set the visibility to something else
        view.visibility = View.GONE
        // When set in XML the index corresponding to visible is 0
        proxy.setVisibility(0)
        assertEquals(View.VISIBLE, view.visibility)
    }

    @Test
    fun setVisibility_invisibleProgrammatic() {
        proxy.setVisibility(View.INVISIBLE)
        assertEquals(View.INVISIBLE, view.visibility)
    }

    @Test
    fun setVisibility_invisibleXml() {
        // When set in XML the index corresponding to invisible is 1
        proxy.setVisibility(1)
        assertEquals(View.INVISIBLE, view.visibility)
    }

    @Test
    fun setVisibility_goneProgrammatic() {
        proxy.setVisibility(View.GONE)
        assertEquals(View.GONE, view.visibility)
    }

    @Test
    fun setVisibility_goneXml() {
        // When set in XML the index corresponding to gone is 2
        proxy.setVisibility(2)
        assertEquals(View.GONE, view.visibility)
    }
}
