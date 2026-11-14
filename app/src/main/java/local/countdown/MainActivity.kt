package local.countdown

import android.app.AlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.UUID

internal data class Timer(val id: String, val name: String, val up: Boolean, val time: Long,
    val emoji: String, val category: String, val reminder: Boolean, val unit: TimeUnit? = null)
internal data class Category(val name: String, val rgb: Int)
private val shape = RoundedCornerShape(16.dp)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("countdown", MODE_PRIVATE) }
    private var defaultUnit by mutableStateOf(TimeUnit.AUTO)
    private val timers = mutableStateListOf<Timer>()
    private val categories = mutableStateListOf<Category>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        defaultUnit = TimeUnit.parse(prefs.getString("defaultUnit", null)) ?: TimeUnit.AUTO
        load(decode(prefs.getString("data", "{}") ?: "{}", backup = false))
        setContent { Screen() }
    }

    private fun load(data: StoredData) {
        timers.clear()
        timers.addAll(data.timers)
        categories.clear()
        categories.addAll(data.categories)
    }

    private fun save() {
        prefs.edit().putString("data", encode()).apply()
    }

    private fun encode(): String {
        val timerArray = JSONArray()
        timers.forEach {
            timerArray.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("up", it.up).put("time", it.time).put("emoji", it.emoji)
                .put("category", it.category).put("reminder", it.reminder).put("unit", it.unit?.name))
        }
        val categoryArray = JSONArray()
        categories.forEach { categoryArray.put(JSONObject().put("name", it.name).put("rgb", it.rgb)) }
        return JSONObject().put("version", 1).put("timers", timerArray)
            .put("categories", categoryArray).toString()
    }

    private val exportBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use {
                    it.write(encode())
                }
            }
            message(if (result.isSuccess) "Backup exported" else "Could not write backup")
        }
    }

    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val data = runCatching {
                requireNotNull(contentResolver.openInputStream(uri)).use { stream ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count == -1) break
                        require(bytes.size() + count <= 4 * 1024 * 1024)
                        bytes.write(buffer, 0, count)
                    }
                    decode(bytes.toString("UTF-8"), backup = true)
                }
            }.getOrNull()
            if (data == null) {
                message("Invalid or unsupported Countdown backup")
            } else {
                AlertDialog.Builder(this).setTitle("Replace timers and categories?")
                    .setMessage("Restore ${data.timers.size} timers and ${data.categories.size} categories. This replaces all current timers and categories.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Replace") { _, _ ->
                        timers.forEach { ReminderReceiver.cancel(this, it.id) }
                        load(data)
                        save()
                        timers.forEach { ReminderReceiver.schedule(this, it) }
                        if (timers.any { it.reminder }) requestNotifications()
                        message("Backup restored")
                    }.show()
            }
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) message("Enable Countdown notifications in Android settings to receive reminders") }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        exactAlarms = exactAlarmsAvailable()
        timers.forEach { ReminderReceiver.schedule(this, it) }
    }

    private fun exactAlarmsAvailable() =
        Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private val alarmSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        exactAlarms = exactAlarmsAvailable()
    }

    private var exactAlarms by mutableStateOf(false)

    private fun openAlarmSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.fromParts("package", packageName, null))
        runCatching { alarmSettings.launch(intent) }
            .onFailure { message("Open Android settings and allow Alarms & reminders for Countdown") }
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    @Composable
    private fun Screen() {
        var up by rememberSaveable { mutableStateOf(false) }
        var filters by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        var countdownSort by rememberSaveable { mutableStateOf(TimerSort.DATE_ASC) }
        var countupSort by rememberSaveable { mutableStateOf(TimerSort.DATE_DESC) }
        val sort = if (up) countupSort else countdownSort
        var searching by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var amoled by remember { mutableStateOf(prefs.getBoolean("amoled", true)) }
        var editor by rememberSaveable { mutableStateOf<String?>(null) }
        var labelEditor by rememberSaveable { mutableStateOf<String?>(null) }
        var page by rememberSaveable { mutableStateOf("") }
        var showDates by remember { mutableStateOf(prefs.getBoolean("showDates", true)) }
        var notifications by remember { mutableStateOf(prefs.getBoolean("notifications", true)) }
        val types = remember { mutableStateMapOf<ReminderType, Boolean>().apply {
            ReminderType.entries.forEach { put(it, it.enabled(prefs)) }
        } }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
        var settings by rememberSaveable { mutableStateOf(false) }
        var showCategories by rememberSaveable { mutableStateOf(false) }
        BackHandler((settings && page.isNotEmpty()) || ((settings || showCategories) && labelEditor != null)) {
            if (labelEditor != null) labelEditor = null else page = ""
        }
        val background = if (amoled) Color.Black else Color(0xFF151820)
        Column(Modifier.fillMaxSize().background(background).safeDrawingPadding()
            .padding(horizontal = 16.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!settings && searching) {
                Field(if (showCategories) "Search labels" else "Search timers", query) { query = it }
            }
            if (settings && page.isNotEmpty()) Button("Back") { labelEditor = null; page = "" }
                    Box(Modifier.weight(1f)) { Label(when {
                        settings -> page.ifEmpty { "Settings" }
                        showCategories -> "Labels"
                        up -> "Countup"
                        else -> "Countdown"
                    }, 30) }
                    if (!settings) {
                        HeaderIcon(R.drawable.ic_search, "Search") {
                            searching = !searching
                            query = ""
                        }
                        HeaderIcon(R.drawable.ic_add, if (showCategories) "Add label" else "Add timer") {
                            if (showCategories) labelEditor = "" else editor = ""
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (settings && page != "Labels") {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (page) {
                        "Notifications" -> {
                            SwitchRow("Allow notifications", notifications) { enabled ->
                                notifications = enabled
                                prefs.edit().putBoolean("notifications", enabled).apply()
                                refreshReminders()
                                if (enabled) requestNotifications()
                            }
                            Label("Notification types", 20)
                            ReminderType.entries.forEach { type ->
                                SwitchRow(type.label, types[type] == true, enabled = notifications) { enabled ->
                                    types[type] = enabled
                                    prefs.edit().putBoolean(type.name, enabled).apply()
                                    refreshReminders()
                                }
                            }
                            Label("Applies to countdowns with Reminder on.", 13)
                            if (notifications && !exactAlarms) SettingsLink("Alarms & reminders") { openAlarmSettings() }
                        }
                        "Time Units" -> TimeUnit.entries.forEach { format ->
                            Button(format.label, selected = defaultUnit == format) {
                                defaultUnit = format
                                prefs.edit().putString("defaultUnit", format.name).apply()
                            }
                        }
                        else -> {
                            SettingsLink("Notifications") { page = "Notifications" }
                            Label("Appearance", 20)
                            SwitchRow("AMOLED", amoled) {
                                amoled = it
                                prefs.edit().putBoolean("amoled", it).apply()
                            }
                            SwitchRow("Show Date & Time", showDates) {
                                showDates = it
                                prefs.edit().putBoolean("showDates", it).apply()
                            }
                            SettingsLink("Time Units", defaultUnit.label) { page = "Time Units" }
                            Label("Organization", 20)
                            SettingsLink("Labels") { page = "Labels" }
                            Label("My Data", 20)
                            SettingsLink("Backup") { exportBackup.launch("countdown-backup.json") }
                            SettingsLink("Restore") {
                                importBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                            }
                        }
                    }
                }
            } else if (showCategories || settings) {
                val editingLabel = labelEditor
                if (editingLabel != null) {
                    CategoryEditor(categories.find { it.name == editingLabel }, onRename = { old, new ->
                        filters = ArrayList(filters.map { if (it == old) new else it })
                    }) { labelEditor = null }
                } else {
                    if (settings) Button("+ Label", color = Color(0xFF4536A4)) { labelEditor = "" }
                    val visibleLabels = categories.filter { settings || it.name.contains(query.trim(), ignoreCase = true) }
                    if (visibleLabels.isEmpty()) Label(if (categories.isEmpty()) "No labels yet" else "No matching labels")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(visibleLabels, key = { it.name }) { category ->
                            Row(Modifier.fillMaxWidth().background(tint(category), shape)
                                .clickable { labelEditor = category.name }.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Label(category.name, 18)
                                Label("Edit", 14)
                            }
                        }
                    }
                }
            } else {
            val editing = editor
            if (editing != null) {
                TimerEditor(timers.find { it.id == editing }, up, now) { editor = null }
            } else {
                val activeFilters = filters.filter { name -> name.isEmpty() || categories.any { it.name == name } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(if (activeFilters.isEmpty()) "Filter" else "Filter (${activeFilters.size})") {
                    chooseFilters(activeFilters) { filters = ArrayList(it) }
                }
                Button("Sort") {
                    AlertDialog.Builder(this@MainActivity).setTitle("Sort by")
                        .setSingleChoiceItems(TimerSort.entries.map { it.label }.toTypedArray(), sort.ordinal) { dialog, index ->
                            if (up) countupSort = TimerSort.entries[index] else countdownSort = TimerSort.entries[index]
                            dialog.dismiss()
                        }.setNegativeButton("Cancel", null).show()
                }
                }
                val visible = timers.filter { timer ->
                    timer.up == up && (activeFilters.isEmpty() || timer.category in activeFilters) &&
                        (timer.name.contains(query.trim(), ignoreCase = true) ||
                            timer.category.contains(query.trim(), ignoreCase = true))
                }
                    .sortedWith(sort.order)
                if (visible.isEmpty()) Label("No timers in this view")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visible, key = { it.id }) { timer ->
                        val color = tint(categories.find { it.name == timer.category })
                        Row(Modifier.fillMaxWidth().background(
                            Brush.linearGradient(listOf(Color.White.copy(alpha = .16f).compositeOverCompat(color), color)), shape)
                            .border(1.dp, Color.White.copy(alpha = .18f), shape)
                            .clickable {
                                if (timer.up) editor = timer.id
                                else showTimerActions(timer) { editor = timer.id }
                            }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                                BasicText(timer.emoji, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = TextStyle(color = Color.White, fontSize = 36.sp))
                            }
                            Column(Modifier.weight(1f)) {
                                Label(timer.name, 18)
                                if (showDates) Label(DateFormat.getDateTimeInstance().format(Date(timer.time)), 12)
                                if (timer.category.isNotEmpty()) Label(timer.category, 12)
                            }
                            BasicText(duration(timer, now, timer.unit ?: defaultUnit),
                                Modifier.widthIn(max = 130.dp),
                                style = TextStyle(color = Color.White, fontSize = 18.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End))
                        }
                    }
                }
            }
            }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF323045), Color(0xFF171724))), RoundedCornerShape(50))
                    .border(1.dp, Color(0xFF625C82), RoundedCornerShape(50)).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GlassTab("Countdowns", R.drawable.ic_countdown, !settings && !showCategories && !up, Color(0xFF6B4CDD), Modifier.weight(1f)) {
                    if (up) editor = null
                    up = false
                    searching = false
                    query = ""
                    page = ""
                    labelEditor = null
                    settings = false
                    showCategories = false
                }
                GlassTab("Countup", R.drawable.ic_count_up, !settings && !showCategories && up, Color(0xFF18767E), Modifier.weight(1f)) {
                    if (!up) editor = null
                    up = true
                    searching = false
                    query = ""
                    page = ""
                    labelEditor = null
                    settings = false
                    showCategories = false
                }
                GlassTab("Labels", R.drawable.ic_categories, showCategories, Color(0xFF955622), Modifier.weight(1f)) {
                    searching = false
                    query = ""
                    page = ""
                    labelEditor = null
                    settings = false
                    showCategories = true
                }
                GlassTab("Settings", R.drawable.ic_settings, settings, Color(0xFF70488F), Modifier.weight(1f)) {
                    showCategories = false
                    searching = false
                    query = ""
                    page = ""
                    labelEditor = null
                    settings = true
                }
            }
        }
    }

    @Composable
    private fun TimerEditor(timer: Timer?, up: Boolean, now: Long, close: () -> Unit) {
        val isUp = timer?.up ?: up
        var name by rememberSaveable(timer?.id) { mutableStateOf(timer?.name.orEmpty()) }
        var emoji by rememberSaveable(timer?.id) { mutableStateOf(timer?.emoji ?: if (isUp) "⏱️" else "⏳") }
        var category by rememberSaveable(timer?.id) { mutableStateOf(timer?.category.orEmpty()) }
        var unit by rememberSaveable(timer?.id) { mutableStateOf(timer?.unit) }
        var reminder by rememberSaveable(timer?.id) { mutableStateOf(timer?.reminder ?: false) }
        var time by rememberSaveable(timer?.id) {
            mutableLongStateOf(timer?.time ?: (System.currentTimeMillis() + if (isUp) 0 else 3_600_000))
        }
        val pastReminder = !isUp && reminder && time <= now
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(if (isUp) "Countup" else "Countdown", 22)
            Field("Name", name) { name = it }
            Field("Emoji", emoji) { emoji = it }
            Label("Time format", 14)
            Button(unit?.label ?: "Default (${defaultUnit.label})") {
                chooseUnit(unit, allowDefault = true) { unit = it }
            }
            Label(if (isUp) "Starts at" else "Ends at", 14)
            val displayedTime = java.text.SimpleDateFormat("EEE, d MMM yyyy · h:mm a", java.util.Locale.getDefault())
            Button(displayedTime.format(Date(time))) { pickDate(time) { time = it } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("No category", category.isEmpty()) { category = "" }
                categories.forEach { item ->
                    Button(item.name, category == item.name, tint(item)) { category = item.name }
                }
            }
            if (!isUp) {
                Button(if (reminder) "Reminder on" else "Reminder off", reminder) { reminder = !reminder }
                if (pastReminder) Label("Choose a future end time or turn off the reminder.", 14)
                if (reminder && !exactAlarms) {
                    Label("Allow precise alarms for reminders at the countdown end.", 13)
                    Button("Open Alarms & reminders") { openAlarmSettings() }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Save", enabled = name.isNotBlank() && !pastReminder) {
                    if (!isUp && reminder && time <= System.currentTimeMillis()) return@Button
                    val updated = Timer(timer?.id ?: UUID.randomUUID().toString(), name.trim(), isUp,
                        time, emoji.trim().ifBlank { if (isUp) "⏱️" else "⏳" }, category, reminder, unit)
                    val index = timers.indexOfFirst { it.id == updated.id }
                    if (index < 0) timers.add(updated) else timers[index] = updated
                    save()
                    ReminderReceiver.cancel(this@MainActivity, updated.id)
                    ReminderReceiver.schedule(this@MainActivity, updated)
                    if (updated.reminder) requestNotifications()
                    close()
                }
                Button("Cancel", action = close)
                if (timer != null) Button("Delete") {
                    confirmDelete(timer, close)
                }
            }
        }
    }

    private fun chooseFilters(current: List<String>, onApply: (List<String>) -> Unit) {
        val labels = listOf("") + categories.map { it.name }
        val checked = BooleanArray(labels.size) { labels[it] in current }
        AlertDialog.Builder(this).setTitle("Filter by label")
            .setMultiChoiceItems(labels.map { it.ifEmpty { "No label" } }.toTypedArray(), checked) { _, index, selected ->
                checked[index] = selected
            }
            .setPositiveButton("Apply") { _, _ -> onApply(labels.filterIndexed { index, _ -> checked[index] }) }
            .setNeutralButton("Show all") { _, _ -> onApply(emptyList()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTimerActions(timer: Timer, edit: () -> Unit) {
        val actions = arrayOf("Edit", "Change label", "Delete")
        AlertDialog.Builder(this).setTitle(timer.name)
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> edit()
                    1 -> changeTimerLabel(timer)
                    2 -> confirmDelete(timer)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun changeTimerLabel(timer: Timer) {
        val labels = listOf("") + categories.map { it.name }
        AlertDialog.Builder(this).setTitle("Change label")
            .setSingleChoiceItems(labels.map { it.ifEmpty { "No label" } }.toTypedArray(),
                labels.indexOf(timer.category)) { dialog, selected ->
                val index = timers.indexOfFirst { it.id == timer.id }
                if (index >= 0) {
                    timers[index] = timers[index].copy(category = labels[selected])
                    save()
                }
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(timer: Timer, onDeleted: () -> Unit = {}) {
        AlertDialog.Builder(this).setMessage("Delete ${timer.name}?")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                timers.removeAll { it.id == timer.id }
                save()
                ReminderReceiver.cancel(this, timer.id)
                onDeleted()
            }.show()
    }

    private fun refreshReminders() {
        timers.forEach {
            ReminderReceiver.cancel(this, it.id)
            ReminderReceiver.schedule(this, it)
        }
    }

    @Composable
    private fun CategoryEditor(category: Category?, onRename: (String, String) -> Unit, close: () -> Unit) {
        var name by rememberSaveable(category?.name) { mutableStateOf(category?.name.orEmpty()) }
        var rgb by rememberSaveable(category?.name) { mutableIntStateOf(category?.rgb ?: 0x5963A6) }
        val newName = name.trim()
        val duplicate = categories.any { it != category && it.name.equals(newName, ignoreCase = true) }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Label name", name) { name = it }
            Label("Label color", 14)
            LabelPalette(rgb) { rgb = it }
            Box(Modifier.fillMaxWidth().background(tint(Category(newName, rgb)), shape)
                .border(1.dp, Color.White.copy(alpha = .2f), shape).padding(16.dp)) {
                Label(newName.ifEmpty { "Label preview" }, 18)
            }
            if (duplicate) Label("A label with this name already exists.", 14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Save label", enabled = newName.isNotEmpty() && !duplicate) {
                    val item = Category(newName, rgb)
                    if (category == null) {
                        categories.add(item)
                    } else {
                        categories[categories.indexOf(category)] = item
                        timers.indices.forEach { index ->
                            if (timers[index].category == category.name) timers[index] = timers[index].copy(category = newName)
                        }
                        onRename(category.name, newName)
                    }
                    save()
                    close()
                }
                Button("Cancel", action = close)
            }
        }
    }

    private fun chooseUnit(current: TimeUnit?, allowDefault: Boolean, onPick: (TimeUnit?) -> Unit) {
        val choices: List<TimeUnit?> = if (allowDefault) listOf(null) + TimeUnit.entries else TimeUnit.entries
        val labels = choices.map { it?.label ?: "Default (${defaultUnit.label})" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Time format")
            .setSingleChoiceItems(labels, choices.indexOf(current)) { dialog, index ->
                onPick(choices[index])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickDate(time: Long, onPick: (Long) -> Unit) {
        val date = Calendar.getInstance().apply { timeInMillis = time }
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                date.set(year, month, day, hour, minute, 0)
                date.set(Calendar.MILLISECOND, 0)
                onPick(date.timeInMillis)
            }, date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(this)).show()
        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show()
    }
}

private fun tint(category: Category?): Color = category?.let {
    Color(0xFF000000.toInt() or it.rgb).let { color ->
        Color(color.red * .48f, color.green * .48f, color.blue * .48f)
    }
} ?: Color(0xFF32345A)

private fun Color.compositeOverCompat(base: Color) =
    Color(red * alpha + base.red * (1 - alpha), green * alpha + base.green * (1 - alpha),
        blue * alpha + base.blue * (1 - alpha))

@Composable
private fun Label(text: String, size: Int = 16) {
    BasicText(text, style = TextStyle(color = Color.White, fontSize = size.sp))
}

@Composable
private fun Field(label: String, value: String, change: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(label, 14)
        BasicTextField(value, change, modifier = Modifier.fillMaxWidth().background(Color(0xFF252936), shape)
            .padding(12.dp), textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            singleLine = true, cursorBrush = SolidColor(Color.White))
    }
}

@Composable
private fun Button(text: String, selected: Boolean = false, color: Color = Color(0xFF272B37),
    enabled: Boolean = true, action: () -> Unit) {
    Box(Modifier.background(color, shape)
        .border(1.dp, if (selected) Color.White else Color.Transparent, shape)
        .clickable(enabled = enabled, onClick = action).padding(horizontal = 12.dp, vertical = 12.dp)) {
        BasicText(text, style = TextStyle(color = if (enabled) Color.White else Color.Gray, fontSize = 14.sp))
    }
}

@Composable
private fun GlassTab(text: String, icon: Int, selected: Boolean, accent: Color, modifier: Modifier, action: () -> Unit) {
    val pill = RoundedCornerShape(50)
    Box(
        modifier.background(
            Brush.verticalGradient(listOf(
                if (selected) accent else Color.Transparent,
                if (selected) accent.copy(alpha = .48f) else Color.Transparent
            )), pill
        ).border(1.dp, if (selected) Color.White.copy(alpha = .24f) else Color.Transparent, pill)
            .selectable(selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = action)
            .heightIn(min = 48.dp).padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(icon), contentDescription = text, modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(Color.White))
    }
}

@Composable
private fun SettingsLink(title: String, value: String = "", action: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF242434), shape)
        .clickable(role = Role.Button, onClick = action).padding(15.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Label(title)
        Label(if (value.isEmpty()) "›" else "$value  ›", 15)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF242434), shape)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change)
        .padding(horizontal = 15.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Label(title)
        Box(Modifier.size(46.dp, 28.dp)
            .background(if (checked && enabled) Color(0xFF7961E8) else Color(0xFF535362), CircleShape)
            .padding(3.dp), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(22.dp).background(if (enabled) Color.White else Color.LightGray, CircleShape))
        }
    }
}


@Composable
private fun HeaderIcon(icon: Int, description: String, action: () -> Unit) {
    Box(Modifier.size(48.dp).background(Color(0xFF302B50), CircleShape)
        .clickable(role = Role.Button, onClick = action), contentAlignment = Alignment.Center) {
        Image(painterResource(icon), description, Modifier.size(23.dp), colorFilter = ColorFilter.tint(Color.White))
    }
}

private enum class TimerSort(val label: String, val order: Comparator<Timer>) {
    DATE_ASC("Date · earliest first", compareBy<Timer> { it.time }.thenBy { it.id }),
    DATE_DESC("Date · latest first", compareByDescending<Timer> { it.time }.thenBy { it.id }),
    NAME_ASC("Name · A to Z", compareBy<Timer, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }),
    NAME_DESC("Name · Z to A", compareBy<Timer, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.reversed().thenBy { it.id }),
}

private val labelColors = listOf(
    "Rose" to 0xEF6586, "Coral" to 0xF47962, "Amber" to 0xE9A74F, "Gold" to 0xEAC95B,
    "Lime" to 0x9AC65B, "Emerald" to 0x49B583, "Teal" to 0x4CBAB6, "Sky" to 0x60B2E4,
    "Blue" to 0x6487EC, "Indigo" to 0x5963A6, "Lavender" to 0xA68AF4, "Orchid" to 0xCA77D2,
    "Plum" to 0x945482, "Terracotta" to 0xAE6553, "Slate" to 0x74869D, "Silver" to 0xB5BBC9,
)

@Composable
private fun LabelPalette(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labelColors.chunked(4).forEach { colors ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                colors.forEach { (name, rgb) ->
                    Box(Modifier.size(48.dp).selectable(selected == rgb, role = Role.RadioButton,
                        onClick = { onSelect(rgb) }).semantics { contentDescription = name },
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(38.dp).background(Color(rgb or 0xFF000000.toInt()), CircleShape)
                            .border(2.dp, if (selected == rgb) Color.White else Color.White.copy(alpha = .16f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            if (selected == rgb) BasicText("✓", style = TextStyle(color = Color.Black, fontSize = 20.sp))
                        }
                    }
                }
            }
        }
    }
}
