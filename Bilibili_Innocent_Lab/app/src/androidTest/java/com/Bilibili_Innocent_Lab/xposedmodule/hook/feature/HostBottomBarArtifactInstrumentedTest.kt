package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** 使用实机宿主资源验证发布按钮换肤契约，不启动宿主、不修改偏好。 */
@RunWith(AndroidJUnit4::class)
class HostBottomBarArtifactInstrumentedTest {
    @Test fun nativePublishAppearanceSurvivesRepeatedDayNightCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
            .createPackageContext("tv.danmaku.bili", 0)
        val backgroundId = context.resources.getIdentifier("publish_bg", "id", context.packageName)
        val publishId = context.resources.getIdentifier("home_publish_icon", "id", context.packageName)
        val rootId = context.resources.getIdentifier("publish_root", "id", context.packageName)
        val shadowId = context.resources.getIdentifier("night_shadow", "id", context.packageName)
        val remoteId = context.resources.getIdentifier("publish_remote_iv", "id", context.packageName)
        check(listOf(backgroundId, publishId, rootId, shadowId, remoteId).all { it != 0 })
        val tabHost = FrameLayout(context)
        val publish = FrameLayout(context).apply { id = publishId }
        val root = FrameLayout(context).apply { id = rootId }
        val backgroundView = FrameLayout(context).apply {
            id = backgroundId
            background = GradientDrawable().apply { setColor(Color.MAGENTA) }
        }
        val nativeFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        val plus = ImageView(context).apply { colorFilter = nativeFilter }
        val remote = ImageView(context).apply {
            id = remoteId
            background = GradientDrawable().apply { setColor(Color.MAGENTA) }
        }
        val shadow = View(context).apply {
            id = shadowId
            background = GradientDrawable().apply { setColor(Color.BLACK) }
        }
        backgroundView.addView(plus)
        root.addView(backgroundView)
        root.addView(remote)
        root.addView(shadow)
        publish.addView(root)
        tabHost.addView(publish)
        val ordinary = View(context).apply { background = GradientDrawable() }
        tabHost.addView(ordinary)
        repeat(3) {
            val dark = it % 2 == 0
            // 宿主换肤重挂背景，并在普通加号与远程图标之间切换。
            backgroundView.background = GradientDrawable().apply { setColor(if (dark) Color.DKGRAY else Color.MAGENTA) }
            val drawable = backgroundView.background
            plus.visibility = if (dark) View.VISIBLE else View.GONE
            remote.visibility = if (dark) View.GONE else View.VISIBLE
            shadow.visibility = if (dark) View.VISIBLE else View.GONE
            HostBottomBarFxController.stripAllHostArtifacts(tabHost)
            assertSame(drawable, backgroundView.background)
            assertEquals(View.VISIBLE, backgroundView.visibility)
            assertEquals(1f, backgroundView.alpha, 0f)
            assertSame(nativeFilter, plus.colorFilter)
            assertEquals(if (dark) View.VISIBLE else View.GONE, plus.visibility)
            assertEquals(if (dark) View.GONE else View.VISIBLE, remote.visibility)
            assertEquals(if (dark) View.VISIBLE else View.GONE, shadow.visibility)
            assertEquals(255, backgroundView.background.alpha)
            assertEquals(255, remote.background.alpha)
            assertEquals(255, shadow.background.alpha)
            // 宿主可继续对原对象 mutate 换色，避免换肤时的空指针。
            val themed = (backgroundView.background as GradientDrawable).mutate() as GradientDrawable
            themed.setColor(Color.BLUE)
            assertNull(ordinary.background)
        }
        // 资源根节点也能单独识别，兼容外层发布包装在不同宿主版本中的差异。
        HostBottomBarFxController.stripAllHostArtifacts(root, isRoot = false)
        assertEquals(255, backgroundView.background.alpha)
    }
}
