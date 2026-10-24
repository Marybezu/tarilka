package ua.tarilka.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

object PhotoUtil {
    private fun dir(context: Context) = File(context.cacheDir, "photos").apply { mkdirs() }

    /** A content:// uri the system camera app can write the photo into. */
    fun captureUri(context: Context): Uri {
        val f = File(dir(context), "capture.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    /** Copies, rotates upright and shrinks the photo so the model gets a small, clean image. */
    suspend fun prepare(context: Context, uri: Uri, maxSide: Int = 1024): File = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = cr.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Cannot read image")
        val rotation = cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        val scale = minOf(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val file = File(dir(context), "meal.jpg")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (out !== bmp) bmp.recycle()
        file
    }
}
