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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suffix: String? = null,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onValueChange(new.filter { it.isDigit() }) },
        label = { Text(label, fontSize = 12.sp) },
        suffix = suffix?.let { { Text(it, fontSize = 12.sp) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = fieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * [onValueChange] is deliberately the *last* parameter so the settings screen can use the
 * trailing-lambda form for its many one-line fields.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextField(
    label: String,
    value: String,
    placeholder: String? = null,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = placeholder?.let { { Text(it, fontSize = 12.sp, color = CondorinoColors.TextTertiary) } },
        singleLine = true,
        colors = fieldColors(),
        modifier = modifier.fillMaxWidth(),
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
