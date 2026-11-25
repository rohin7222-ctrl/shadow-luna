package com.personal.devicememory.collector.contactscalendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.personal.devicememory.core.common.collector.Collector
import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.core.common.util.toJsonString

/**
 * Read-only collector for Contacts from Android [ContactsContract].
 * Normalizes contact relationships without modifying user data.
 */
class ContactCollector(
    private val context: Context
) : Collector {

    override val sourceName: String = "Contacts"

    companion object {
        const val TAG = "ContactCollector"

        fun hasPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    override suspend fun collect(sinceTimestamp: Long): List<CollectedRecord> {
        if (!hasPermission(context)) {
            Log.w(TAG, "Missing READ_CONTACTS permission")
            return emptyList()
        }

        val records = mutableListOf<CollectedRecord>()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
        )

        val (selection, selectionArgs) = if (sinceTimestamp > 0) {
            "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} > ?" to arrayOf(sinceTimestamp.toString())
        } else {
            null to null
        }

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameCol = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val updatedCol = c.getColumnIndex(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)

                val now = TimestampUtil.nowMillis()

                while (c.moveToNext()) {
                    val contactId = c.getString(idCol)
                    val rawName = c.getString(nameCol) ?: "Unnamed Contact"
                    val updatedTime = if (updatedCol >= 0) c.getLong(updatedCol) else now
                    val effectiveTime = if (updatedTime > 0) updatedTime else now

                    val sanitizedName = SensitiveContentFilter.redact(rawName) ?: rawName

                    val metadata = mapOf<String, Any?>(
                        "contact_id" to contactId,
                        "display_name" to sanitizedName
                    )

                    records.add(
                        CollectedRecord(
                            sourceType = SourceType.CONTACT,
                            timestamp = effectiveTime,
                            textContent = "Contact: $sanitizedName",
                            metadataJson = metadata.toJsonString()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query ContactsContract", e)
        }

        Log.i(TAG, "Collected ${records.size} contact records")
        return records
    }
}
