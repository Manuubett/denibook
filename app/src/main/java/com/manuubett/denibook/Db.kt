package com.manuubett.denibook

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class Customer(
    val id: Long,
    val name: String,
    val phone: String,
    val balance: Long,        // positive: the customer owes; negative: paid in advance
    val lastPayMs: Long,      // 0 if never paid
    val firstDebtMs: Long,    // 0 if no debt yet
    val lastAnyMs: Long       // last activity of any kind, 0 if none
)

class Entry(
    val id: Long,
    val customerId: Long,
    val type: String,         // "debt" or "pay"
    val amount: Long,
    val note: String,
    val method: String,       // "cash", "mpesa" or "" for debts
    val atMs: Long,
    val by: String,           // "owner" or "waiter"
    val voided: Boolean
)

/**
 * All records stay on the phone (works with no internet).
 *
 * A customer's balance is every debt minus every payment. A wrong entry
 * is never erased: it is cancelled ("voided") so the history stays honest.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "deni.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE customers(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "phone TEXT NOT NULL DEFAULT ''," +
                "created_ms INTEGER NOT NULL," +
                "active INTEGER NOT NULL DEFAULT 1)"
        )

        db.execSQL(
            "CREATE TABLE entries(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "customer_id INTEGER NOT NULL," +
                "type TEXT NOT NULL," +
                "amount INTEGER NOT NULL," +
                "note TEXT NOT NULL DEFAULT ''," +
                "method TEXT NOT NULL DEFAULT ''," +
                "at_ms INTEGER NOT NULL," +
                "by_role TEXT NOT NULL DEFAULT 'owner'," +
                "voided INTEGER NOT NULL DEFAULT 0)"
        )

        db.execSQL("CREATE INDEX entries_customer ON entries(customer_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 only
    }

    // ---- Customers ----

    fun addCustomer(name: String, phone: String, createdMs: Long): Long {
        val v = ContentValues()
        v.put("name", name)
        v.put("phone", phone)
        v.put("created_ms", createdMs)
        v.put("active", 1)
        return writableDatabase.insert("customers", null, v)
    }

    fun updateCustomer(id: Long, name: String, phone: String) {
        val v = ContentValues()
        v.put("name", name)
        v.put("phone", phone)
        writableDatabase.update("customers", v, "id=?", arrayOf(id.toString()))
    }

    // Hidden from the list; the history stays in the backup file
    fun removeCustomer(id: Long) {
        val v = ContentValues()
        v.put("active", 0)
        writableDatabase.update("customers", v, "id=?", arrayOf(id.toString()))
    }

    private val customerSql =
        "SELECT c.id, c.name, c.phone, " +
            "COALESCE(SUM(CASE WHEN e.voided=0 AND e.type='debt' THEN e.amount " +
            "WHEN e.voided=0 AND e.type='pay' THEN -e.amount ELSE 0 END),0) AS bal, " +
            "COALESCE(MAX(CASE WHEN e.voided=0 AND e.type='pay' THEN e.at_ms END),0) AS last_pay, " +
            "COALESCE(MIN(CASE WHEN e.voided=0 AND e.type='debt' THEN e.at_ms END),0) AS first_debt, " +
            "COALESCE(MAX(CASE WHEN e.voided=0 THEN e.at_ms END),0) AS last_any " +
            "FROM customers c LEFT JOIN entries e ON e.customer_id=c.id "

    private fun readCustomers(sql: String, args: Array<String>?): List<Customer> {
        val out = ArrayList<Customer>()

        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Customer(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getLong(3),
                        c.getLong(4),
                        c.getLong(5),
                        c.getLong(6)
                    )
                )
            }
        }

        return out
    }

    fun customers(): List<Customer> =
        readCustomers(customerSql + "WHERE c.active=1 GROUP BY c.id", null)

    fun customer(id: Long): Customer? =
        readCustomers(customerSql + "WHERE c.id=? AND c.active=1 GROUP BY c.id", arrayOf(id.toString()))
            .firstOrNull()

    // ---- Entries ----

    fun addEntry(
        customerId: Long,
        type: String,
        amount: Long,
        note: String,
        method: String,
        atMs: Long,
        by: String
    ): Long {
        val v = ContentValues()
        v.put("customer_id", customerId)
        v.put("type", type)
        v.put("amount", amount)
        v.put("note", note)
        v.put("method", method)
        v.put("at_ms", atMs)
        v.put("by_role", by)
        return writableDatabase.insert("entries", null, v)
    }

    fun voidEntry(id: Long) {
        val v = ContentValues()
        v.put("voided", 1)
        writableDatabase.update("entries", v, "id=?", arrayOf(id.toString()))
    }

    fun entries(customerId: Long): List<Entry> {
        val out = ArrayList<Entry>()

        readableDatabase.rawQuery(
            "SELECT id, customer_id, type, amount, note, method, at_ms, by_role, voided " +
                "FROM entries WHERE customer_id=? ORDER BY at_ms DESC, id DESC",
            arrayOf(customerId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Entry(
                        c.getLong(0),
                        c.getLong(1),
                        c.getString(2),
                        c.getLong(3),
                        c.getString(4),
                        c.getString(5),
                        c.getLong(6),
                        c.getString(7),
                        c.getInt(8) == 1
                    )
                )
            }
        }

        return out
    }

    // Total debts ("debt") or payments ("pay") between two moments
    fun total(type: String, from: Long, to: Long): Long {
        val c = readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(e.amount),0) FROM entries e " +
                "JOIN customers c ON c.id=e.customer_id " +
                "WHERE c.active=1 AND e.voided=0 AND e.type=? AND e.at_ms>=? AND e.at_ms<?",
            arrayOf(type, from.toString(), to.toString())
        )

        try {
            return if (c.moveToFirst()) c.getLong(0) else 0L
        } finally {
            c.close()
        }
    }

    // ---- Backup and restore ----

    fun exportJson(shop: String): String {
        val root = JSONObject()
        root.put("app", "denibook")
        root.put("version", 1)
        root.put("shop", shop)
        root.put("saved_ms", System.currentTimeMillis())

        val customers = JSONArray()

        readableDatabase.rawQuery(
            "SELECT id, name, phone, created_ms, active FROM customers ORDER BY id", null
        ).use { c ->
            while (c.moveToNext()) {
                customers.put(
                    JSONObject()
                        .put("id", c.getLong(0))
                        .put("name", c.getString(1))
                        .put("phone", c.getString(2))
                        .put("created", c.getLong(3))
                        .put("active", c.getInt(4))
                )
            }
        }

        val entries = JSONArray()

        readableDatabase.rawQuery(
            "SELECT id, customer_id, type, amount, note, method, at_ms, by_role, voided " +
                "FROM entries ORDER BY id", null
        ).use { c ->
            while (c.moveToNext()) {
                entries.put(
                    JSONObject()
                        .put("id", c.getLong(0))
                        .put("cid", c.getLong(1))
                        .put("type", c.getString(2))
                        .put("amount", c.getLong(3))
                        .put("note", c.getString(4))
                        .put("method", c.getString(5))
                        .put("at", c.getLong(6))
                        .put("by", c.getString(7))
                        .put("voided", c.getInt(8))
                )
            }
        }

        root.put("customers", customers)
        root.put("entries", entries)

        return root.toString()
    }

    // Replaces everything on this phone with the backup. False if the file is not a Deni Book backup.
    fun importJson(text: String): Boolean {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return false
        }

        if (root.optString("app") != "denibook") return false

        val customers = root.optJSONArray("customers") ?: return false
        val entries = root.optJSONArray("entries") ?: return false

        val db = writableDatabase
        db.beginTransaction()

        try {
            db.delete("entries", null, null)
            db.delete("customers", null, null)

            for (i in 0 until customers.length()) {
                val o = customers.getJSONObject(i)
                val v = ContentValues()
                v.put("id", o.getLong("id"))
                v.put("name", o.getString("name"))
                v.put("phone", o.optString("phone", ""))
                v.put("created_ms", o.optLong("created", 0L))
                v.put("active", o.optInt("active", 1))
                db.insertOrThrow("customers", null, v)
            }

            for (i in 0 until entries.length()) {
                val o = entries.getJSONObject(i)
                val v = ContentValues()
                v.put("id", o.getLong("id"))
                v.put("customer_id", o.getLong("cid"))
                v.put("type", o.getString("type"))
                v.put("amount", o.getLong("amount"))
                v.put("note", o.optString("note", ""))
                v.put("method", o.optString("method", ""))
                v.put("at_ms", o.getLong("at"))
                v.put("by_role", o.optString("by", "owner"))
                v.put("voided", o.optInt("voided", 0))
                db.insertOrThrow("entries", null, v)
            }

            db.setTransactionSuccessful()
        } catch (e: Exception) {
            return false
        } finally {
            db.endTransaction()
        }

        return true
    }
}
