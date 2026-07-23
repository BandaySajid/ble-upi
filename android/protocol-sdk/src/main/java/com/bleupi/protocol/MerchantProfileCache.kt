package com.bleupi.protocol

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

class MerchantProfileCache(context: Context) {
    private val dbHelper = CacheDatabaseHelper(context)

    fun get(shortHash: Int): MerchantProfile? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "merchants",
            arrayOf("display_name", "vpa", "public_key"),
            "short_hash = ?",
            arrayOf(shortHash.toString()),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) {
                MerchantProfile(
                    shortHash = shortHash,
                    displayName = it.getString(0),
                    vpa = it.getString(1),
                    publicKey = it.getBlob(2)
                )
            } else null
        }
    }

    fun put(profile: MerchantProfile) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("short_hash", profile.shortHash.toString())
            put("display_name", profile.displayName)
            put("vpa", profile.vpa)
            put("public_key", profile.publicKey)
        }
        db.replace("merchants", null, values)
    }

    private class CacheDatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, "merchant_profiles.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE merchants (
                    short_hash TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    vpa TEXT NOT NULL,
                    public_key BLOB NOT NULL
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS merchants")
            onCreate(db)
        }
    }
}
