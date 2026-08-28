package com.condorino.weekend.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.condorino.weekend.ui.theme.CondorinoColors

/**
 * The shared search box. Unlike the settings fields this one is fully controlled: its value lives
 * in plain composable state next to the list it filters, so there is no round trip to fight with.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    clearContentDescription: String = "",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, fontSize = 13.sp, color = CondorinoColors.TextTertiary) },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null, tint = CondorinoColors.TextTertiary)
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = clearContentDescription,
                        tint = CondorinoColors.TextTertiary,
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Search,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CondorinoColors.Amber,
            unfocusedBorderColor = CondorinoColors.Outline,
            focusedTextColor = CondorinoColors.TextPrimary,
            unfocusedTextColor = CondorinoColors.TextPrimary,
            cursorColor = CondorinoColors.Amber,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
