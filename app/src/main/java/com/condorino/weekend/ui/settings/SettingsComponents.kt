package com.condorino.weekend.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.condorino.weekend.core.MoneyInput
import com.condorino.weekend.ui.theme.CondorinoColors

@Composable
fun SettingsSection(
    title: String,
    subtitle: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            title.uppercase(),
            color = CondorinoColors.TextTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp,
        )
        subtitle?.let {
            Text(it, color = CondorinoColors.TextTertiary.copy(alpha = 0.8f), fontSize = 11.sp, lineHeight = 15.sp)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CondorinoColors.Surface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * The single text input used across settings.
 *
 * ### Why it keeps its own state
 *
 * Every settings field writes straight through to DataStore and reads its value back out of a
 * Flow. That round trip takes a moment, so feeding the upstream value back into the field on every
 * recomposition makes it fight the user: a character typed while a write is still in flight gets
 * overwritten by the older value coming back, and the caret jumps. Long values — an API secret,
 * say — end up silently mangled.
 *
 * So the field owns its text. It adopts the upstream value only until the user first touches it,
 * which still covers the real case of DataStore loading after the first composition, and after
 * that the field is the authority for as long as it is on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditableField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    suffix: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    sanitize: (String) -> String = { it },
) {
    var text by rememberSaveable { mutableStateOf(value) }
    var edited by rememberSaveable { mutableStateOf(false) }

    // Late-arriving stored value (DataStore resolves after the first frame) is adopted; anything
    // arriving after the user starts typing is ignored.
    if (!edited && text != value) text = value

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            edited = true
            val cleaned = sanitize(raw)
            text = cleaned
            onValueChange(cleaned)
        },
        label = {
            Text(label, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        placeholder = placeholder?.let {
            {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = CondorinoColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        suffix = suffix?.let { { Text(it, fontSize = 12.sp) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
        ),
        colors = fieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Whole-number input. Anything that is not a digit is dropped as it is typed. */
@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suffix: String? = null,
    modifier: Modifier = Modifier,
) {
    EditableField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        suffix = suffix,
        keyboardType = KeyboardType.Number,
        sanitize = MoneyInput::sanitizeInteger,
    )
}

/**
 * Decimal input for money. Accepts both separators — a German keyboard offers a comma, an English
 * one a dot — and keeps at most two fractional digits.
 */
@Composable
fun DecimalField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suffix: String? = null,
    modifier: Modifier = Modifier,
) {
    EditableField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        suffix = suffix,
        keyboardType = KeyboardType.Decimal,
        sanitize = MoneyInput::sanitizeDecimal,
    )
}

/**
 * [onValueChange] is deliberately the *last* parameter so the settings screen can use the
 * trailing-lambda form for its many one-line fields.
 */
@Composable
fun TextField(
    label: String,
    value: String,
    placeholder: String? = null,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    EditableField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CondorinoColors.Amber,
    unfocusedBorderColor = CondorinoColors.Outline,
    focusedLabelColor = CondorinoColors.Amber,
    unfocusedLabelColor = CondorinoColors.TextTertiary,
    focusedTextColor = CondorinoColors.TextPrimary,
    unfocusedTextColor = CondorinoColors.TextPrimary,
    cursorColor = CondorinoColors.Amber,
)

@Composable
fun SwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = CondorinoColors.TextPrimary, fontSize = 14.sp)
            description?.let {
                Text(it, color = CondorinoColors.TextTertiary, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CondorinoColors.Background,
                checkedTrackColor = CondorinoColors.Amber,
            ),
        )
    }
}

@Composable
fun WeightSlider(label: String, value: Double, onChange: (Double) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = CondorinoColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                "${(value * 100).toInt()} %",
                color = CondorinoColors.Amber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = 0f..0.5f,
            steps = 9,
            colors = SliderDefaults.colors(
                thumbColor = CondorinoColors.Amber,
                activeTrackColor = CondorinoColors.Amber,
                inactiveTrackColor = CondorinoColors.SurfaceHigh,
            ),
        )
    }
}
