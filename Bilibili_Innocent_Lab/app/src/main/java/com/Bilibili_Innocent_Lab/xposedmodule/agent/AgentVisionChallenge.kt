package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.VisionChallenge
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import androidx.core.graphics.createBitmap

/** 本地随机图片挑战不包含设备截图或个人数据；答案只用于本地核对。 */
internal object AgentVisionChallenge {
    fun create(): VisionChallenge {
        val random = SecureRandom()
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val answer = (1..8).joinToString("") { alphabet[random.nextInt(alphabet.length)].toString() }
        val bitmap = createBitmap(640, 160, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 68f; typeface = android.graphics.Typeface.MONOSPACE }
            canvas.drawText(answer, 30f, 106f, paint)
            val bytes = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            VisionChallenge("data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP), answer)
        } finally { bitmap.recycle() }
    }
}
