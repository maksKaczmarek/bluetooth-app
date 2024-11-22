package com.example.bluetooth_app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        val createTableSQL = """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT,
                $COLUMN_ADDRESS TEXT,
                $COLUMN_MESSAGE TEXT
            )
        """.trimIndent()
        db.execSQL(createTableSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Example of adding a new column in version 2
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN new_column_name TEXT DEFAULT ''")
        }
        // Add more upgrade logic here for future versions
    }

    fun insertMessage(name: String, address: String, message: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, name)
            put(COLUMN_ADDRESS, address)
            put(COLUMN_MESSAGE, message)
        }
        db.insert(TABLE_NAME, null, values)
    }

    companion object {
        private const val DATABASE_NAME = "devices.db"
        private const val DATABASE_VERSION = 2 // Increment this version when you make changes to the database schema
        const val TABLE_NAME = "devices"
        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"
        const val COLUMN_ADDRESS = "address"
        const val COLUMN_MESSAGE = "message"
    }
}