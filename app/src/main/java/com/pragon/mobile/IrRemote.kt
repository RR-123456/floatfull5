package com.pragon.mobile

import android.content.Context
import android.hardware.ConsumerIrManager

/** v21: sends IR through the phone's own IR blaster. Returns a plain reason instead of failing silently. */
object IrRemote {
    private fun mgr(c: Context): ConsumerIrManager? =
        try { c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager } catch (_: Exception) { null }

    fun available(c: Context): Boolean = try { mgr(c)?.hasIrEmitter() == true } catch (_: Exception) { false }

    /** null = sent, otherwise the reason it was not. */
    fun transmit(c: Context, pattern: IntArray): String? {
        val m = mgr(c) ?: return "This phone has no IR blaster."
        return try {
            if (!m.hasIrEmitter()) "This phone has no IR blaster." else { m.transmit(IrCodes.FREQ, pattern); null }
        } catch (e: SecurityException) { "IR permission is missing." }
        catch (e: Exception) { "Could not send the IR signal." }
    }
}
