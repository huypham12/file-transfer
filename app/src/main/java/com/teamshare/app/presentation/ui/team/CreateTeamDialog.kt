package com.teamshare.app.presentation.ui.team

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CreateTeamDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, desc: String, ownerName: String) -> Unit
) {
    var teamName by remember { mutableStateOf("") }
    var ownerName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var ownerNameError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = {
            Text(text = "Tạo nhóm cộng tác mới", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = ownerName,
                    onValueChange = {
                        ownerName = it
                        if (ownerNameError != null) ownerNameError = null
                    },
                    label = { Text("Tên hiển thị của bạn (*)") },
                    singleLine = true,
                    isError = ownerNameError != null,
                    supportingText = {
                        if (ownerNameError != null) {
                            Text(text = ownerNameError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(text = "Tên hiển thị của bạn với vai trò Trưởng nhóm")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = teamName,
                    onValueChange = {
                        teamName = it
                        if (nameError != null) nameError = null
                    },
                    label = { Text("Tên nhóm (*)") },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = {
                        if (nameError != null) {
                            Text(text = nameError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(text = "${teamName.length}/50 ký tự")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Mô tả nhóm (Tùy chọn)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedOwner = ownerName.trim()
                    val trimmed = teamName.trim()
                    var hasError = false

                    if (trimmedOwner.isEmpty()) {
                        ownerNameError = "Tên hiển thị không được để trống"
                        hasError = true
                    }
                    if (trimmed.isEmpty()) {
                        nameError = "Tên nhóm không được để trống"
                        hasError = true
                    } else if (trimmed.length > 50) {
                        nameError = "Tên nhóm không được vượt quá 50 ký tự"
                        hasError = true
                    }

                    if (!hasError) {
                        onConfirm(trimmed, description.trim(), trimmedOwner)
                    }
                },
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Tạo nhóm", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss, 
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Hủy")
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

