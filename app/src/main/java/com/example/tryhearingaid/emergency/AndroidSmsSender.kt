package com.example.tryhearingaid.emergency

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

/**
 * Sends SOS texts with the device's [SmsManager] (Requirement 10.3).
 *
 * Needs the `SEND_SMS` permission. Without it [SmsManager] throws a [SecurityException],
 * which [SosCoordinator] counts as a failed send and retries, so the caller should check the
 * permission first and tell the user if it is missing.
 *
 * The SOS text carries a maps link and easily exceeds one 160-character SMS, so the message is
 * split with [SmsManager.divideMessage] and sent as a multipart message. Sending a long text
 * with `sendTextMessage` would fail.
 *
 * A `true` result means the message was handed to the phone's radio. It is not a delivery receipt.
 */
class AndroidSmsSender(private val context: Context) : SmsSender {

    override fun send(phoneNumber: String, message: String): Boolean {
        val manager = smsManager()
        if (manager == null) {
            Log.w(TAG, "No SmsManager available on this device.")
            return false
        }
        val parts = manager.divideMessage(message)
        if (parts.size > 1) {
            manager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
        } else {
            manager.sendTextMessage(phoneNumber, null, message, null, null)
        }
        return true
    }

    private fun smsManager(): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    private companion object {
        const val TAG = "AndroidSmsSender"
    }
}
