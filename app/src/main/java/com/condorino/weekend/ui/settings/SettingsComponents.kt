package com.condorino.weekend.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.condorino.weekend.R
import com.condorino.weekend.core.MoneyInput
import com.condorino.weekend.ui.theme.CondorinoColors

@Composable
fun SettingsSection(
    title: String,
    subtitle: String? = null,
    /**
     * Sections start closed so this screen opens as a readable list rather than a very long
     * scroll — there are close to twenty of them, most holding an API field mapping set once and
     * never looked at again. A section that is genuinely part of everyday use passes true.
     */
    initiallyExpanded: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    // Keyed by title so each section keeps its own state, and survives rotation and navigating
    // away and back. The screen is a plain scrolling Column, not a lazy list, so nothing here is
    // recycled onto a different section.
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "sectionChevron")

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                // A filled row, not bare text: closed, the header *is* the control, and it has to
                // look like one. Squared off at the bottom while open so it reads as one piece
                // with the panel it opens.
                .clip(
                    if (expanded) RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                    else RoundedCornerShape(14.dp),
                )
                .background(if (expanded) CondorinoColors.SurfaceElevated else CondorinoColors.Surface)
                .clickable { expanded = !expanded }
                // 48dp is the minimum comfortable touch target, and this is the only way in.
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    // Sentence case at a readable size, in the primary text colour. The old
                    // 10sp tertiary all-caps label was the quietest thing on a screen where it
                    // had become the only navigation.
                    title,
                    color = CondorinoColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                // Kept visible while closed too: one line saying what a section is for is what
                // makes a list of twenty headings answerable without opening each one.
                subtitle?.let {
                    Text(
                        it,
                        color = CondorinoColors.TextTertiary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Icon(
                // One icon, rotated, so the arrow turns rather than swapping shape mid-animation.
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.action_collapse_section else R.string.action_expand_section,
                    title,
                ),
                tint = CondorinoColors.TextSecondary,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                    .background(CondorinoColors.Surface)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

/**
 * A heading over a run of [SettingsSection]s.
 *
 * Twenty sections in one undifferentiated column is a list you have to read end to end to use.
 * Three or four named groups turn it into one you can skim: the API plumbing sits together and
 * out of the way of the handful of settings actually adjusted day to day.
 */
@Composable
fun SettingsGroupHeader(title: String) {
    Text(
        title.uppercase(),
        color = CondorinoColors.Amber,
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(top = 26.dp, bottom = 2.dp, start = 2.dp),
    )
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
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
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
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

/**
 * A secret the user types once and never needs to read back character by character — an API
 * client secret, a token. Masked by default, with a toggle to reveal it, so it stays off-screen in
 * a shoulder-surfing situation but is still checkable if a paste went wrong.
 */
@Composable
fun PasswordField(
    label: String,
    value: String,
    placeholder: String? = null,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    EditableField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        keyboardType = KeyboardType.Password,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.action_hide_secret else R.string.action_show_secret,
                    ),
                    tint = CondorinoColors.TextTertiary,
                )
            }
        },
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
