package xyz.michaelzhao.simple

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import xyz.michaelzhao.simple.ui.theme.SimpleTheme
import java.util.Date

class TextAppsWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val glanceId =
            GlanceAppWidgetManager(this@TextAppsWidgetConfigActivity)
                .getGlanceIdBy(appWidgetId)
        val preferencesManager = PreferencesManager(this@TextAppsWidgetConfigActivity)

        val prevNumber = preferencesManager.getNumber(appWidgetId)
        val prevHide = preferencesManager.getHidePage(appWidgetId)
        val prevFontSize = preferencesManager.getFontSize(appWidgetId)

        setResult(RESULT_CANCELED)

        setContent {
            ConfigurationScreen(
                prevNumber,
                prevHide,
                prevFontSize,
                onSave = { numberText: String, hidePageNumber: Boolean, fontSizeText: String ->
                    val number = numberText.toIntOrNull()
                    val fontSize = fontSizeText.toFloatOrNull()

                    // Save is disabled unless both parse, so this is a guard rather than a
                    // path we expect to take.
                    if (number == null || fontSize == null) {
                        Log.e(
                            "Widget Config",
                            "Save with unparseable values: '$numberText', '$fontSizeText'"
                        )
                    } else {
                        saveAndFinish(
                            appWidgetId,
                            glanceId,
                            preferencesManager,
                            number,
                            fontSize,
                            hidePageNumber
                        )
                    }
                }
            )
        }
    }

    private fun saveAndFinish(
        appWidgetId: Int,
        glanceId: GlanceId,
        preferencesManager: PreferencesManager,
        number: Int,
        fontSize: Float,
        hidePageNumber: Boolean
    ) {
        preferencesManager.saveNumber(appWidgetId, number)
        preferencesManager.saveHidePage(appWidgetId, hidePageNumber)
        preferencesManager.saveFontSize(appWidgetId, fontSize)

        val resVal = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(RESULT_OK, resVal)

        // Finish only once the widget state has been written. Finishing first cancels
        // lifecycleScope part way through the write, which leaves the widget on its
        // default page.
        lifecycleScope.launch {
            try {
                updateAppWidgetState(
                    this@TextAppsWidgetConfigActivity,
                    glanceId
                ) { prefs ->
                    prefs[intPreferencesKey("widget_number")] = number
                    prefs[booleanPreferencesKey("hide_page_num")] = hidePageNumber
                    prefs[intPreferencesKey("version")] = Date().time.toInt()
                }
                TextAppsWidget().update(this@TextAppsWidgetConfigActivity, glanceId)
            } catch (e: Exception) {
                Log.e("Widget Config", "Update failed", e)
            }
            finish()
        }
    }
}

@Composable
fun ConfigurationScreen(
    prevNumber: Int,
    prevHide: Boolean,
    prevFontSize: Float,
    onSave: (String, Boolean, String) -> Unit
) {
    var numberText by remember { mutableStateOf(prevNumber.toString()) }
    var hidePageNumber by remember { mutableStateOf(prevHide) }
    var fontSizeText by remember { mutableStateOf(prevFontSize.toString()) }
    var textFieldSize by remember { mutableStateOf(Size.Zero) }
    var expanded by remember { mutableStateOf(false) }
    val data = DataManager(LocalContext.current).loadData()
    val density = LocalDensity.current

    val icon = if (expanded)
        Icons.Filled.KeyboardArrowUp
    else
        Icons.Filled.KeyboardArrowDown
    val pageNumber = numberText.toIntOrNull()
    val pageNumberValid = pageNumber != null && pageNumber >= 0 && pageNumber < data.size
    val parsedFontSize = fontSizeText.toFloatOrNull()
    val fontSizeValid = parsedFontSize != null && parsedFontSize > 0f

    // Flag a field only once something has been typed into it, but require both to be
    // valid before the widget can be saved.
    val numberErr = numberText.isNotBlank() && !pageNumberValid
    val fontErr = fontSizeText.isNotBlank() && !fontSizeValid

    SimpleTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    Text(
                        text = "Widget Configuration",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 32.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Column {
                        OutlinedTextField(
                            value = numberText,
                            onValueChange = { numberText = it },
                            label = { Text("App Display Number") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    textFieldSize = coordinates.size.toSize()
                                }
                                .onFocusEvent {
                                    if (it.isFocused) {
                                        expanded = true
                                    }
                                },
                            trailingIcon = {
                                Icon(
                                    icon,
                                    "dropdown",
                                    modifier = Modifier.clickable { expanded = !expanded })
                            },
                            isError = numberErr
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.width(
                                with(density) { textFieldSize.width.toDp() }
                            )
                        ) {
                            data.mapIndexed { i, d ->
                                val labels = d.joinToString { it.first }
                                val text =
                                    if (labels.length > 20) labels.take(20) + "..." else labels
                                DropdownMenuItem(
                                    text = { Text("$i: $text") },
                                    onClick = {
                                        numberText = i.toString()
                                        expanded = false
                                    })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = fontSizeText,
                        onValueChange = { fontSizeText = it },
                        label = { Text("Font Size (default = 30)") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = fontErr
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = hidePageNumber,
                            onCheckedChange = { hidePageNumber = it })
                        Text("Hide Page Number")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        { onSave(numberText, hidePageNumber, fontSizeText) },
                        enabled = pageNumberValid && fontSizeValid
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
