package com.pragon.mobile

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v7.5: takes one photo with the phone's camera app for the vision tools (OCR, labels, object detection, scene description, cut-out).
 * No screen of its own (transparent): it asks for the camera permission if needed, opens the camera, decodes the shot (rotated upright,
 * at most 1600 px on the long side), deletes the temporary file and hands the bitmap to the waiting caller. A plain Activity on purpose,
 * so it works with the transparent theme (an AppCompat activity would crash on it).
 */
class VisionCaptureActivity : Activity() {

    companion object {
        private const val REQ_PERM = 71
        private const val REQ_SHOT = 72
        @Volatile private var latch: CountDownLatch? = null
        @Volatile private var bitmap: Bitmap? = null
        @Volatile private var error: String = ""

        /** Blocking (up to [timeoutS] seconds): opens the camera and waits for the picture. Call from a background thread. */
        fun capture(ctx: Context, timeoutS: Long = 90): Pair<Bitmap?, String> {
            bitmap = null; error = ""
            val l = CountDownLatch(1)
            latch = l
            try {
                ctx.startActivity(Intent(ctx, VisionCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { latch = null; return null to "I couldn't open the camera: ${e.message}" }
            val done = try { l.await(timeoutS, TimeUnit.SECONDS) } catch (e: InterruptedException) { false }
            latch = null
            if (!done) return null to "The camera timed out. Try again."
            return bitmap to error
        }
    }

    private var uri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return   // a restart after rotation: the first instance is still handling it
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_PERM)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else finishWith(null, "I need the camera permission. Allow it for Pragon in the phone's app settings.")
    }

    private fun openCamera() {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Pragon_scan_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pragon")
            }
            val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return finishWith(null, "The phone wouldn't let me prepare a photo.")
            uri = u
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, u)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_SHOT)
        } catch (e: Exception) { finishWith(null, "I couldn't open the camera: ${e.message}") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SHOT) return
        val u = uri
        if (resultCode != RESULT_OK || u == null) { drop(); return finishWith(null, "No photo was taken.") }
        val bmp = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, u)) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longSide = maxOf(info.size.width, info.size.height)
                if (longSide > 1600) { val s = 1600f / longSide; dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
            }
        } catch (e: Throwable) { null }
        drop()
        if (bmp == null) finishWith(null, "I couldn't read the photo.") else finishWith(bmp, "")
    }

    /** The scan photo is only a working copy: remove it from the gallery. */
    private fun drop() { uri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) { } }; uri = null }

    private fun finishWith(b: Bitmap?, err: String) {
        bitmap = b; error = err
        latch?.countDown()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        latch?.countDown()   // never leave the caller waiting if the screen is closed some other way
    }
}
