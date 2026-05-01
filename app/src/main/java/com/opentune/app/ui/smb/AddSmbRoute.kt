package com.opentune.app.ui.smb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.storage.OpenTuneDatabase
import com.opentune.storage.SmbSourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddSmbRoute(
    database: OpenTuneDatabase,
    onDone: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Add SMB share")
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name") })
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host / IP") })
        OutlinedTextField(value = share, onValueChange = { share = it }, label = { Text("Share name") })
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") })
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
        OutlinedTextField(value = domain, onValueChange = { domain = it }, label = { Text("Domain (optional)") })
        error?.let { Text("Error: $it") }
        Button(
            onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            database.smbSourceDao().insert(
                                SmbSourceEntity(
                                    displayName = name.ifBlank { share },
                                    host = host.trim(),
                                    shareName = share.trim(),
                                    username = username,
                                    password = password,
                                    domain = domain.ifBlank { null },
                                ),
                            )
                        }
                        onDone()
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
            },
        ) {
            Text("Save")
        }
        Button(onClick = onDone) { Text("Cancel") }
    }
}
