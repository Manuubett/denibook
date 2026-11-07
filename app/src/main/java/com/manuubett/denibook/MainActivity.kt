package com.manuubett.denibook

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val green = Color.parseColor("#15803D")
    private val red = Color.parseColor("#DC2626")
    private val ink = Color.parseColor("#0F172A")
    private val grey = Color.parseColor("#64748B")
    private val slate = Color.parseColor("#475569")
    private val bg = Color.parseColor("#F1F5F9")

    private val codeSaveBackup = 11
    private val codeSaveCsv = 12
    private val codeRestore = 13

    private lateinit var prefs: SharedPreferences
    private lateinit var db: Db
    private lateinit var gate: PayGate
    private lateinit var root: LinearLayout

    private var screen = "main"
    private var currentId = 0L

    // "" = locked, "owner" = sees everything, "waiter" = adds debts only
    private var role = ""

    private var allowed = false
    private var checking = true
    private var lastGateAt = 0L
    private var lastStopAt = 0L
    private var downloadId = -1L
    private var updateText = "Check for updates"
    private var pendingText: String? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id == downloadId && id != -1L) installDownloaded()
        }
    }

    private class Field(val hint: String, val initial: String = "", val kind: Int = TEXT) {
        companion object {
            const val TEXT = 0
            const val PHONE = 1
            const val NUMBER = 2
            const val PLAIN = 3
        }
    }

    private val shopName: String get() = prefs.getString("shop", "") ?: ""
    private val remindDays: Int get() = prefs.getInt("remind_days", 14)
    private fun isOwner() = role == "owner"

    // ---- Life cycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("denibook_settings", Context.MODE_PRIVATE)
        db = Db(this)
        gate = PayGate(this, appId = "denibook")

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.isFillViewport = true

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(24))

        scroll.addView(root)
        setContentView(scroll)

        try {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(downloadReceiver, filter)
            }
        } catch (e: Exception) {
            // The download still happens; the user opens it from notifications
        }

        render()
    }

    override fun onResume() {
        super.onResume()

        // Ask for the PIN again after the app was away for 2 minutes
        if (Pin.isSet(prefs, "owner") && role.isNotEmpty() &&
            System.currentTimeMillis() - lastStopAt > 120_000L
        ) {
            role = ""
        }

        render()
        ensureGate(false)
    }

    override fun onStop() {
        lastStopAt = System.currentTimeMillis()
        super.onStop()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Not registered
        }

        super.onDestroy()
    }

    override fun onBackPressed() {
        if (screen == "customer") {
            screen = "main"
            render()
        } else if (screen != "main") {
            screen = "main"
            render()
        } else {
            super.onBackPressed()
        }
    }

    private fun locked(): Boolean = Pin.isSet(prefs, "owner") && role.isEmpty()

    // ---- Subscription ----

    private fun ensureGate(force: Boolean) {
        val now = System.currentTimeMillis()

        if (locked()) return
        if (!force && allowed && now - lastGateAt < 30 * 60_000L) return

        checking = true
        render()

        gate.check(
            onDenied = {
                allowed = false
                checking = false
                render()
            },
            onAllowed = {
                allowed = true
                checking = false
                lastGateAt = System.currentTimeMillis()
                render()
            }
        )
    }

    // ---- Drawing ----

    private fun render() {
        root.removeAllViews()

        // No owner PIN set: the person using the phone is the owner
        if (!Pin.isSet(prefs, "owner") && role.isEmpty()) role = "owner"

        if (locked()) {
            lockScreen()
            return
        }

        title()

        if (checking && !allowed) {
            note("Checking your subscription…")
            footer()
            return
        }

        if (!allowed) {
            val c = card()
            c.addView(heading("Subscription needed"))
            c.addView(body("Your free trial or subscription has ended. Your records are safe on this phone."))
            c.addView(button("Subscribe or renew", green) { ensureGate(true) })
            footer()
            return
        }

        if (!isOwner() && screen != "main" && screen != "customer") screen = "main"

        when (screen) {
            "customer" -> customerScreen()
            "reports" -> reportsScreen()
            "backup" -> backupScreen()
            "settings" -> settingsScreen()
            else -> mainScreen()
        }

        footer()
    }

    private fun title() {
        val t = TextView(this)
        t.text = "Deni Book"
        t.textSize = 26f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(green)
        root.addView(t)

        val s = TextView(this)
        val who = if (role == "waiter") "Waiter" else ""
        val shop = if (shopName.isEmpty()) "Customer debts and payments" else shopName
        s.text = if (who.isEmpty()) shop else "$shop · $who"
        s.textSize = 14f
        s.setTextColor(grey)
        s.setPadding(0, 0, 0, dp(12))
        root.addView(s)
    }

    private fun backBar(label: String) {
        val b = Button(this)
        b.text = "←  $label"
        b.isAllCaps = false
        b.setTextColor(green)
        b.textSize = 16f
        b.setBackgroundColor(Color.TRANSPARENT)
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.setPadding(0, 0, 0, 0)
        b.setOnClickListener {
            screen = "main"
            render()
        }
        root.addView(b, LinearLayout.LayoutParams(-2, dp(44)))
    }

    // ---- Lock ----

    private fun lockScreen() {
        val c = card()
        c.addView(heading("Deni Book is locked"))
        c.addView(body("Enter your PIN."))

        val pin = EditText(this)
        pin.hint = "PIN"
        pin.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        pin.gravity = Gravity.CENTER
        pin.textSize = 22f
        c.addView(pin)

        val msg = TextView(this)
        msg.setTextColor(red)
        msg.textSize = 13f
        c.addView(msg)

        val unlock = button("Unlock", green) {}
        unlock.setOnClickListener {
            val typed = pin.text.toString()
            unlock.isEnabled = false

            thread {
                var found = ""

                try {
                    if (Pin.check(prefs, "owner", typed)) {
                        found = "owner"
                    } else if (Pin.isSet(prefs, "waiter") && Pin.check(prefs, "waiter", typed)) {
                        found = "waiter"
                    }
                } catch (e: Exception) {
                    found = ""
                }

                runOnUiThread {
                    if (found.isNotEmpty()) {
                        role = found
                        screen = "main"
                        render()
                        ensureGate(false)
                    } else {
                        unlock.isEnabled = true
                        pin.setText("")
                        msg.text = "Wrong PIN. Try again."
                    }
                }
            }
        }
        c.addView(unlock)

        c.addView(
            body("Forgot the owner PIN? Reinstalling the app erases the records, so restore from your backup file.")
        )
    }

    // ---- Main screen ----

    private fun mainScreen() {
        val all = db.customers()
        val now = System.currentTimeMillis()
        val day = startOfDay(now)

        if (isOwner()) {
            val owing = all.filter { it.balance > 0 }
            val owed = owing.sumOf { it.balance }

            val s = card()
            s.addView(heading("Owed to you"))

            val big = TextView(this)
            big.text = money(owed)
            big.textSize = 30f
            big.setTypeface(null, Typeface.BOLD)
            big.setTextColor(if (owed > 0) red else green)
            s.addView(big)

            s.addView(
                body(
                    "${owing.size} customer(s) owe money\n" +
                        "Today: credit given ${money(db.total("debt", day, day + 86_400_000L))} · " +
                        "paid ${money(db.total("pay", day, day + 86_400_000L))}"
                )
            )

            if (all.isNotEmpty() && prefs.getLong("last_backup", 0L) == 0L) {
                s.addView(body("Tip: open Backup and save a copy of your records."))
            }

            if (!Pin.isSet(prefs, "owner")) {
                s.addView(body("Tip: set a PIN in Settings so only you can open the records."))
            }
        }

        val c = card()
        c.addView(heading("Customers (${all.size})"))

        val search = EditText(this)
        search.hint = "Search name or phone"
        search.setSingleLine()
        c.addView(search)

        val listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        c.addView(listBox)

        fillList(listBox, all, "")

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                fillList(listBox, all, s?.toString() ?: "")
            }

            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        c.addView(button("Add customer", green) { addCustomerDialog() })

        if (isOwner()) {
            val n = card()
            n.addView(button("Reports", slate) { screen = "reports"; render() })
            n.addView(button("Backup and export", slate) { screen = "backup"; render() })
            n.addView(button("Settings", slate) { screen = "settings"; render() })
        }
    }

    private fun fillList(box: LinearLayout, all: List<Customer>, filter: String) {
        box.removeAllViews()

        val q = filter.trim().lowercase()

        val shown = all
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.phone.contains(q) }
            .sortedWith(compareByDescending<Customer> { it.balance }.thenBy { it.name.lowercase() })

        if (shown.isEmpty()) {
            box.addView(body(if (all.isEmpty()) "Add your first customer to begin." else "No customer found."))
            return
        }

        for (c in shown.take(150)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(10), 0, dp(10))

            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL

            val name = TextView(this)
            name.text = c.name
            name.textSize = 16f
            name.setTypeface(null, Typeface.BOLD)
            name.setTextColor(ink)
            left.addView(name)

            val sub = TextView(this)
            sub.text = (if (c.phone.isEmpty()) "" else c.phone + " · ") + ago(c.lastAnyMs)
            sub.textSize = 12f
            sub.setTextColor(grey)
            left.addView(sub)

            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

            val bal = TextView(this)
            bal.text = if (c.balance < 0) "+${money(-c.balance)}" else money(c.balance)
            bal.textSize = 16f
            bal.setTypeface(null, Typeface.BOLD)
            bal.setTextColor(if (c.balance > 0) red else green)
            row.addView(bal)

            row.setOnClickListener {
                currentId = c.id
                screen = "customer"
                render()
            }

            box.addView(row)
        }

        if (shown.size > 150) {
            box.addView(body("Showing the first 150. Use the search box to find others."))
        }
    }

    private fun addCustomerDialog() {
        form(
            "Add customer",
            listOf(
                Field("Name", "", Field.TEXT),
                Field("Phone (optional)", "", Field.PHONE),
                Field("Amount already owed (optional)", "", Field.NUMBER)
            ),
            "Save", null
        ) { v, _ ->
            val name = v[0].trim()
            val opening = v[2].trim().toLongOrNull() ?: 0L

            if (name.isEmpty()) {
                "Enter the customer's name"
            } else {
                val id = db.addCustomer(name, cleanPhone(v[1]), System.currentTimeMillis())

                if (opening > 0L) {
                    db.addEntry(id, "debt", opening, "Opening balance", "", System.currentTimeMillis(), role)
                }

                render()
                null
            }
        }
    }

    // ---- Customer screen ----

    private fun customerScreen() {
        val c = db.customer(currentId)

        if (c == null) {
            screen = "main"
            render()
            return
        }

        backBar("Customers")

        val h = card()

        val name = TextView(this)
        name.text = c.name
        name.textSize = 22f
        name.setTypeface(null, Typeface.BOLD)
        name.setTextColor(ink)
        h.addView(name)

        if (c.phone.isNotEmpty()) h.addView(body(c.phone))

        val bal = TextView(this)
        bal.text = if (c.balance < 0) "Paid in advance ${money(-c.balance)}" else "Owes ${money(c.balance)}"
        bal.textSize = 26f
        bal.setTypeface(null, Typeface.BOLD)
        bal.setTextColor(if (c.balance > 0) red else green)
        bal.setPadding(0, dp(6), 0, dp(6))
        h.addView(bal)

        h.addView(button("Add debt", red) { addDebtDialog(c) })

        if (isOwner()) {
            h.addView(button("Record payment", green) { payDialog(c) })

            if (c.phone.isNotEmpty() && c.balance > 0) {
                h.addView(button("Send SMS reminder", slate) { sendReminder(c) })
            }

            h.addView(button("Share statement", slate) { shareStatement(c) })
            h.addView(button("Edit customer", slate) { editCustomerDialog(c) })
        }

        val list = db.entries(c.id)
        val e = card()
        e.addView(heading("History"))

        if (list.isEmpty()) e.addView(body("Nothing recorded yet."))

        for (en in list) e.addView(entryRow(en))

        if (isOwner() && list.isNotEmpty()) {
            e.addView(body("Tap an entry to cancel it if it was a mistake."))
        }
    }

    private fun entryRow(en: Entry): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL

        val top = TextView(this)
        top.text = if (en.type == "debt") "Debt" else "Payment" + methodText(en.method)
        top.textSize = 15f
        top.setTextColor(ink)
        left.addView(top)

        val sub = TextView(this)
        sub.text = fmtDate(en.atMs) +
            (if (en.note.isEmpty()) "" else " · ${en.note}") +
            (if (en.by == "waiter") " · waiter" else "") +
            (if (en.voided) " · cancelled" else "")
        sub.textSize = 12f
        sub.setTextColor(grey)
        left.addView(sub)

        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val amt = TextView(this)
        amt.text = (if (en.type == "debt") "+" else "−") + money(en.amount)
        amt.textSize = 15f
        amt.setTypeface(null, Typeface.BOLD)
        amt.setTextColor(if (en.type == "debt") red else green)
        row.addView(amt)

        if (en.voided) {
            top.paintFlags = top.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            amt.paintFlags = amt.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            top.setTextColor(grey)
            amt.setTextColor(grey)
        } else if (isOwner()) {
            row.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Cancel this entry?")
                    .setMessage(
                        "${if (en.type == "debt") "Debt" else "Payment"} of ${money(en.amount)} " +
                            "on ${fmtDate(en.atMs)}.\n\nIt stays in the history, marked as cancelled, " +
                            "and no longer counts in the balance."
                    )
                    .setPositiveButton("Cancel entry") { _, _ ->
                        db.voidEntry(en.id)
                        render()
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            }
        }

        return row
    }

    private fun methodText(m: String): String = when (m) {
        "cash" -> " (cash)"
        "mpesa" -> " (M-PESA)"
        else -> ""
    }

    private fun addDebtDialog(c: Customer) {
        form(
            "Add debt for ${c.name}",
            listOf(
                Field("Amount (KES)", "", Field.NUMBER),
                Field("What for (optional)", "", Field.PLAIN)
            ),
            "Save", null
        ) { v, _ ->
            val amount = v[0].trim().toLongOrNull() ?: 0L

            if (amount <= 0L) {
                "Enter the amount"
            } else {
                db.addEntry(c.id, "debt", amount, v[1].trim(), "", System.currentTimeMillis(), role)
                toast("Debt of ${money(amount)} added")
                render()
                null
            }
        }
    }

    private fun payDialog(c: Customer) {
        form(
            "Payment from ${c.name}",
            listOf(
                Field("Amount (KES)", if (c.balance > 0) c.balance.toString() else "", Field.NUMBER),
                Field("Note (optional)", "", Field.PLAIN)
            ),
            "Cash", "M-PESA"
        ) { v, which ->
            val amount = v[0].trim().toLongOrNull() ?: 0L

            if (amount <= 0L) {
                "Enter the amount"
            } else {
                db.addEntry(
                    c.id, "pay", amount, v[1].trim(),
                    if (which == 0) "cash" else "mpesa",
                    System.currentTimeMillis(), role
                )
                toast("Payment of ${money(amount)} recorded")
                render()
                null
            }
        }
    }

    private fun editCustomerDialog(c: Customer) {
        val b = AlertDialog.Builder(this)
            .setTitle("Edit ${c.name}")
            .setItems(arrayOf("Change name or phone", "Remove customer")) { _, which ->
                if (which == 0) {
                    form(
                        "Change details",
                        listOf(
                            Field("Name", c.name, Field.TEXT),
                            Field("Phone", c.phone, Field.PHONE)
                        ),
                        "Save", null
                    ) { v, _ ->
                        if (v[0].trim().isEmpty()) {
                            "Enter the name"
                        } else {
                            db.updateCustomer(c.id, v[0].trim(), cleanPhone(v[1]))
                            render()
                            null
                        }
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Remove ${c.name}?")
                        .setMessage(
                            (if (c.balance != 0L) "They have a balance of ${money(c.balance)}. " else "") +
                                "They will disappear from the list. The history stays in your backup file."
                        )
                        .setPositiveButton("Remove") { _, _ ->
                            db.removeCustomer(c.id)
                            screen = "main"
                            render()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Close", null)

        b.show()
    }

    private fun sendReminder(c: Customer) {
        val shop = if (shopName.isEmpty()) "us" else shopName
        val text = "Hello ${c.name}, a friendly reminder from $shop. " +
            "Your balance is ${money(c.balance)}. Please pay when you can. Thank you."

        try {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${c.phone}"))
            i.putExtra("sms_body", text)
            startActivity(i)
        } catch (e: Exception) {
            toast("Could not open messages")
        }
    }

    private fun shareStatement(c: Customer) {
        val sb = StringBuilder()
        val shop = if (shopName.isEmpty()) "Statement" else "$shopName statement"

        sb.append(shop).append('\n')
        sb.append(c.name).append('\n')
        sb.append("Balance: ").append(money(c.balance)).append("\n\n")

        for (en in db.entries(c.id).filter { !it.voided }.take(15)) {
            sb.append(fmtDate(en.atMs)).append("  ")
            sb.append(if (en.type == "debt") "Debt " else "Paid ")
            sb.append(money(en.amount))
            if (en.note.isNotEmpty()) sb.append("  (").append(en.note).append(')')
            sb.append('\n')
        }

        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, sb.toString())

        startActivity(Intent.createChooser(i, "Share statement"))
    }

    // ---- Reports ----

    private fun reportsScreen() {
        backBar("Reports")

        val now = System.currentTimeMillis()
        val today = startOfDay(now)
        val day = 86_400_000L
        val all = db.customers()
        val owing = all.filter { it.balance > 0 }

        val a = card()
        a.addView(heading("Credit and payments"))
        a.addView(
            body(
                "Today:  credit ${money(db.total("debt", today, today + day))}  ·  paid ${money(db.total("pay", today, today + day))}\n" +
                    "7 days:  credit ${money(db.total("debt", today - 6 * day, today + day))}  ·  paid ${money(db.total("pay", today - 6 * day, today + day))}\n" +
                    "30 days:  credit ${money(db.total("debt", today - 29 * day, today + day))}  ·  paid ${money(db.total("pay", today - 29 * day, today + day))}\n\n" +
                    "Total owed now: ${money(owing.sumOf { it.balance })}"
            )
        )

        val b = card()
        b.addView(heading("Biggest debts"))

        val top = owing.sortedByDescending { it.balance }.take(10)

        if (top.isEmpty()) b.addView(body("Nobody owes money."))

        for (c in top) {
            b.addView(smallRow(c.name, money(c.balance), red) {
                currentId = c.id
                screen = "customer"
                render()
            })
        }

        val d = card()
        d.addView(heading("Overdue ($remindDays+ days)"))
        d.addView(body("Owing, and no payment for $remindDays days or more."))

        val limit = remindDays * day

        val overdue = owing
            .filter {
                val ref = if (it.lastPayMs > 0L) it.lastPayMs else it.firstDebtMs
                ref > 0L && now - ref > limit
            }
            .sortedByDescending { it.balance }

        if (overdue.isEmpty()) d.addView(body("None. Well done."))

        for (c in overdue) {
            d.addView(smallRow(c.name, money(c.balance), red) {
                currentId = c.id
                screen = "customer"
                render()
            })
        }
    }

    private fun smallRow(left: String, right: String, rightColor: Int, click: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(10), 0, dp(10))

        val a = TextView(this)
        a.text = left
        a.textSize = 15f
        a.setTextColor(ink)
        row.addView(a, LinearLayout.LayoutParams(0, -2, 1f))

        val b = TextView(this)
        b.text = right
        b.textSize = 15f
        b.setTypeface(null, Typeface.BOLD)
        b.setTextColor(rightColor)
        row.addView(b)

        row.setOnClickListener { click() }

        return row
    }

    // ---- Backup ----

    private fun backupScreen() {
        backBar("Backup and export")

        val last = prefs.getLong("last_backup", 0L)

        val c = card()
        c.addView(heading("Save a copy of your records"))
        c.addView(
            body(
                "Records live on this phone only. If it is lost or reset, they are gone, so save a backup file " +
                    "often and keep it somewhere safe (for example send it to yourself on WhatsApp or email).\n\n" +
                    "Last backup: " + (if (last == 0L) "never" else fmtDate(last))
            )
        )

        c.addView(button("Save backup file", green) {
            val name = "DeniBook-backup-" +
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
            saveFile(codeSaveBackup, name, "application/json", db.exportJson(shopName))
        })

        c.addView(button("Restore from a backup file", red) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"

            try {
                startActivityForResult(i, codeRestore)
            } catch (e: Exception) {
                toast("No file manager found")
            }
        })

        val x = card()
        x.addView(heading("Spreadsheet"))
        x.addView(body("A list of every customer and balance that opens in Excel or Google Sheets."))

        x.addView(button("Save customer balances (CSV)", slate) {
            val sb = StringBuilder()
            sb.append("Customer,Phone,Balance,Last payment,Last activity\n")

            for (c2 in db.customers().sortedByDescending { it.balance }) {
                sb.append(csv(c2.name)).append(',')
                sb.append(csv(c2.phone)).append(',')
                sb.append(c2.balance).append(',')
                sb.append(csv(if (c2.lastPayMs > 0L) fmtDate(c2.lastPayMs) else "")).append(',')
                sb.append(csv(if (c2.lastAnyMs > 0L) fmtDate(c2.lastAnyMs) else "")).append('\n')
            }

            val name = "DeniBook-balances-" +
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".csv"
            saveFile(codeSaveCsv, name, "text/csv", sb.toString())
        })
    }

    private fun saveFile(code: Int, name: String, mime: String, text: String) {
        pendingText = text

        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        i.putExtra(Intent.EXTRA_TITLE, name)

        try {
            startActivityForResult(i, code)
        } catch (e: Exception) {
            toast("No file manager found")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        val uri = data?.data

        if (resultCode != RESULT_OK || uri == null) return

        if (requestCode == codeSaveBackup || requestCode == codeSaveCsv) {
            val text = pendingText ?: return
            pendingText = null

            thread {
                val ok = try {
                    contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    }
                    true
                } catch (e: Exception) {
                    false
                }

                runOnUiThread {
                    if (ok && requestCode == codeSaveBackup) {
                        prefs.edit().putLong("last_backup", System.currentTimeMillis()).apply()
                        render()
                    }

                    toast(if (ok) "Saved. Keep this file safe." else "Could not save the file")
                }
            }
        } else if (requestCode == codeRestore) {
            thread {
                val text = try {
                    contentResolver.openInputStream(uri)?.use {
                        String(it.readBytes(), Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    null
                }

                runOnUiThread {
                    if (text == null) {
                        toast("Could not read the file")
                    } else {
                        confirmRestore(text)
                    }
                }
            }
        }
    }

    private fun confirmRestore(text: String) {
        AlertDialog.Builder(this)
            .setTitle("Replace all records?")
            .setMessage(
                "Everything now on this phone will be replaced by the records in this backup file. " +
                    "This cannot be undone."
            )
            .setPositiveButton("Restore") { _, _ ->
                thread {
                    val ok = db.importJson(text)

                    runOnUiThread {
                        if (ok) {
                            screen = "main"
                            toast("Records restored")
                            render()
                        } else {
                            toast("That is not a Deni Book backup file")
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Settings ----

    private fun settingsScreen() {
        backBar("Settings")

        val c = card()
        c.addView(heading("Business"))

        c.addView(
            settingRow("Business name", if (shopName.isEmpty()) "Not set" else shopName) {
                form(
                    "Business name",
                    listOf(Field("Name shown at the top", shopName, Field.TEXT)),
                    "Save", null
                ) { v, _ ->
                    prefs.edit().putString("shop", v[0].trim()).apply()
                    render()
                    null
                }
            }
        )

        c.addView(
            settingRow("Overdue after", "$remindDays days") {
                form(
                    "Overdue after how many days?",
                    listOf(Field("Days", remindDays.toString(), Field.NUMBER)),
                    "Save", null
                ) { v, _ ->
                    val days = v[0].trim().toIntOrNull() ?: 0

                    if (days < 1) {
                        "Enter 1 or more"
                    } else {
                        prefs.edit().putInt("remind_days", days.coerceAtMost(365)).apply()
                        render()
                        null
                    }
                }
            }
        )

        val p = card()
        p.addView(heading("Security"))

        if (Pin.isSet(prefs, "owner")) {
            p.addView(body("Owner PIN is set. The owner sees everything."))
            p.addView(button("Change owner PIN", slate) { pinDialog("owner") })

            if (Pin.isSet(prefs, "waiter")) {
                p.addView(body("Waiter PIN is set. A waiter can only add debts."))
                p.addView(button("Change waiter PIN", slate) { pinDialog("waiter") })
                p.addView(button("Remove waiter PIN", red) {
                    Pin.clear(prefs, "waiter")
                    toast("Waiter PIN removed")
                    render()
                })
            } else {
                p.addView(body("Give your waiters their own PIN. With it they can add customers and debts, but cannot record payments, cancel entries or see reports."))
                p.addView(button("Set a waiter PIN", green) { pinDialog("waiter") })
            }

            p.addView(button("Remove owner PIN", red) {
                AlertDialog.Builder(this)
                    .setTitle("Remove the PIN?")
                    .setMessage("Anyone who opens the phone will see all records. The waiter PIN is removed too.")
                    .setPositiveButton("Remove") { _, _ ->
                        Pin.clear(prefs, "owner")
                        Pin.clear(prefs, "waiter")
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
        } else {
            p.addView(body("A PIN keeps your customers' details private. Without it anyone who opens the phone can see them."))
            p.addView(button("Set an owner PIN", green) { pinDialog("owner") })
        }

        val u = card()
        u.addView(heading("App"))
        u.addView(button(updateText, slate) { checkUpdate() })

        u.addView(button("How it works", slate) {
            AlertDialog.Builder(this)
                .setTitle("How it works")
                .setMessage(
                    "1. Add each customer with a name and phone.\n\n" +
                        "2. When they eat on credit, open them and tap Add debt.\n\n" +
                        "3. When they pay, tap Record payment. Part payments are fine.\n\n" +
                        "4. Reports show who owes the most and who is overdue.\n\n" +
                        "5. Save a backup file often. Records stay on this phone and work without internet."
                )
                .setPositiveButton("OK", null)
                .show()
        })
    }

    private fun pinDialog(key: String) {
        val label = if (key == "owner") "owner" else "waiter"

        form(
            "New $label PIN (4 to 8 digits)",
            listOf(Field("PIN", "", Field.NUMBER)),
            "Next", null
        ) { first, _ ->
            val one = first[0].trim()

            if (one.length < 4 || one.length > 8) {
                "The PIN must be 4 to 8 digits"
            } else {
                form(
                    "Type the PIN again",
                    listOf(Field("PIN", "", Field.NUMBER)),
                    "Save", null
                ) { second, _ ->
                    if (one != second[0].trim()) {
                        "The two PINs did not match"
                    } else {
                        savePin(key, one)
                        null
                    }
                }
                null
            }
        }
    }

    private fun savePin(key: String, pin: String) {
        thread {
            val clash = try {
                if (key == "waiter") {
                    Pin.check(prefs, "owner", pin)
                } else {
                    Pin.isSet(prefs, "waiter") && Pin.check(prefs, "waiter", pin)
                }
            } catch (e: Exception) {
                false
            }

            if (!clash) {
                try {
                    Pin.set(prefs, key, pin)
                } catch (e: Exception) {
                    // Reported below
                }
            }

            runOnUiThread {
                if (clash) {
                    toast("The owner and waiter PINs must be different")
                } else if (!Pin.isSet(prefs, key)) {
                    toast("Could not save the PIN")
                } else {
                    if (key == "owner") role = "owner"
                    toast("PIN saved. Do not forget it.")
                    render()
                }
            }
        }
    }

    private fun settingRow(label: String, value: String, click: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(10), 0, dp(10))

        val a = TextView(this)
        a.text = label
        a.textSize = 16f
        a.setTextColor(ink)
        row.addView(a)

        val b = TextView(this)
        b.text = value
        b.textSize = 13f
        b.setTextColor(green)
        row.addView(b)

        row.setOnClickListener { click() }

        return row
    }

    // ---- Updates ----

    private fun checkUpdate() {
        updateText = "Checking…"
        render()

        thread {
            try {
                val latest = UpdateChecker.fetchLatest()
                val mine = UpdateChecker.installedVersion(this)

                runOnUiThread {
                    if (latest != null && UpdateChecker.isNewer(latest.version, mine)) {
                        updateText = "Update ${latest.version} available"
                        render()

                        AlertDialog.Builder(this)
                            .setTitle("Update ${latest.version}")
                            .setMessage(if (latest.notes.isEmpty()) "A new version is ready." else latest.notes)
                            .setPositiveButton("Download") { _, _ -> startDownload(latest.docId) }
                            .setNegativeButton("Later", null)
                            .show()
                    } else {
                        updateText = "Up to date ($mine)"
                        render()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateText = "Check for updates"
                    render()
                    toast("Could not check. Try again with data on.")
                }
            }
        }
    }

    private fun startDownload(docId: String) {
        toast("Downloading…")

        thread {
            try {
                val url = UpdateChecker.requestDownloadUrl(docId)

                runOnUiThread {
                    val file = File(getExternalFilesDir(null), "DeniBook-update.apk")
                    if (file.exists()) file.delete()

                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle("Deni Book update")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(this, null, "DeniBook-update.apk")

                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadId = dm.enqueue(req)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Download failed. Try again.") }
            }
        }
    }

    private fun installDownloaded() {
        val file = File(getExternalFilesDir(null), "DeniBook-update.apk")
        if (!file.exists()) return

        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow Deni Book to install apps, then download again")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        // targetSdk 28 lets us use a plain file URI
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(i)
    }

    // ---- Forms ----

    /**
     * A dialog with text boxes. onOk gets the typed values and which button
     * was pressed (0 = main, 1 = second). It returns an error message to
     * keep the dialog open, or null when it is done.
     */
    private fun form(
        title: String,
        fields: List<Field>,
        okLabel: String,
        altLabel: String?,
        onOk: (List<String>, Int) -> String?
    ) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        val inputs = ArrayList<EditText>()

        for (f in fields) {
            val e = EditText(this)
            e.hint = f.hint
            e.setText(f.initial)
            e.setSingleLine()

            e.inputType = when (f.kind) {
                Field.PHONE -> InputType.TYPE_CLASS_PHONE
                Field.NUMBER -> InputType.TYPE_CLASS_NUMBER
                Field.PLAIN -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            }

            box.addView(e)
            inputs.add(e)
        }

        val error = TextView(this)
        error.setTextColor(red)
        error.textSize = 13f
        box.addView(error)

        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(okLabel, null)
            .setNegativeButton("Cancel", null)

        if (altLabel != null) b.setNeutralButton(altLabel, null)

        val dialog = b.create()

        dialog.setOnShowListener {
            val submit = { which: Int ->
                val problem = onOk(inputs.map { it.text.toString() }, which)

                if (problem == null) {
                    dialog.dismiss()
                } else {
                    error.text = problem
                }
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit(0) }

            if (altLabel != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { submit(1) }
            }
        }

        dialog.show()
    }

    // ---- Money, time and text ----

    private fun money(v: Long): String = "KES " + String.format(Locale.US, "%,d", v)

    private fun csv(text: String): String = "\"" + text.replace("\"", "\"\"") + "\""

    private fun cleanPhone(text: String): String = text.filter { it.isDigit() || it == '+' }

    private fun fmtDate(ms: Long): String =
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

    private fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun ago(ms: Long): String {
        if (ms == 0L) return "no activity"

        val days = ((startOfDay(System.currentTimeMillis()) - startOfDay(ms)) / 86_400_000L).toInt()

        return when {
            days <= 0 -> "today"
            days == 1 -> "yesterday"
            else -> "$days days ago"
        }
    }

    // ---- Small helpers ----

    private fun footer() {
        val f = TextView(this)
        f.text = "Deni Book build ${UpdateChecker.installedVersion(this)}\nPowered by dehemanuelssolutions.co.ke"
        f.textSize = 12f
        f.gravity = Gravity.CENTER
        f.setTextColor(grey)
        f.setPadding(0, dp(20), 0, 0)
        f.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dehemanuelssolutions.co.ke/")))
        }
        root.addView(f)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(14))

        val shape = GradientDrawable()
        shape.setColor(Color.WHITE)
        shape.cornerRadius = dp(14).toFloat()
        c.background = shape

        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(12)
        root.addView(c, lp)

        return c
    }

    private fun heading(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 18f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(ink)
        t.setPadding(0, 0, 0, dp(6))
        return t
    }

    private fun body(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setTextColor(grey)
        t.setPadding(0, dp(2), 0, dp(6))
        return t
    }

    private fun note(text: String) {
        val c = card()
        c.addView(body(text))
    }

    private fun button(text: String, color: Int, click: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = 15f

        val shape = GradientDrawable()
        shape.setColor(color)
        shape.cornerRadius = dp(10).toFloat()
        b.background = shape

        val lp = LinearLayout.LayoutParams(-1, dp(48))
        lp.topMargin = dp(8)
        b.layoutParams = lp
        b.setOnClickListener { click() }

        return b
    }
}


