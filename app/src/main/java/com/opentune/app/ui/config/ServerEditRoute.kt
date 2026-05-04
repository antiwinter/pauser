package com.opentune.app.ui.config

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.R
import com.opentune.app.providers.OpenTuneProviderIds
import com.opentune.app.providers.ProviderFieldSchema
import com.opentune.app.providers.ServerConfigRepository
import com.opentune.emby.api.formatHttpExceptionForDisplay
import com.opentune.provider.ServerFieldKind
import com.opentune.storage.OpenTuneDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

private const val LOG_TAG = "OpenTuneServerEdit"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ServerEditRoute(
    providerId: String,
    database: OpenTuneDatabase,
    sourceId: Long,
    onDone: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OpenTuneApplication
    val fields = remember(providerId) { ProviderFieldSchema.fieldsForEdit(providerId).sortedBy { it.order } }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(providerId, sourceId) {
        loaded = false
        val initial = withContext(Dispatchers.IO) {
            ServerConfigRepository.loadEditFields(providerId, database, sourceId)
        }
        values = fields.associate { it.id to (initial[it.id] ?: "") }
        loaded = true
    }

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.server_edit_title))
            Text(
                if (providerId == OpenTuneProviderIds.HTTP_LIBRARY) {
                    stringResource(R.string.server_edit_hint_http)
                } else {
                    stringResource(R.string.server_edit_hint_file_share)
                },
            )
            if (!loaded) {
                Text("Loading…")
            } else {
                fields.forEach { spec ->
                    val v = values[spec.id] ?: ""
                    OutlinedTextField(
                        value = v,
                        onValueChange = { nv -> values = values + (spec.id to nv) },
                        label = { Text(providerFieldLabel(spec.labelKey)) },
                        placeholder = run {
                            val ph = providerFieldPlaceholder(spec.placeholderKey)
                            if (ph != null) ({ Text(ph) }) else null
                        },
                        singleLine = spec.kind != ServerFieldKind.Text,
                        visualTransformation = if (spec.kind == ServerFieldKind.Password) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                    )
                }
            }
            error?.let { Text("Error: $it") }
        }
        Button(
            onClick = {
                scope.launch {
                    error = null
                    try {
                        val profile = app.deviceProfile
                        withContext(Dispatchers.IO) {
                            ServerConfigRepository.submitEdit(
                                providerId = providerId,
                                sourceId = sourceId,
                                values = values,
                                database = database,
                                deviceProfile = profile,
                            )
                        }
                        onDone()
                    } catch (e: HttpException) {
                        if (providerId == OpenTuneProviderIds.HTTP_LIBRARY) {
                            val msg = formatHttpExceptionForDisplay(e)
                            Log.e(LOG_TAG, "submitEdit failed: $msg", e)
                            error = msg
                        } else {
                            Log.e(LOG_TAG, "submitEdit failed", e)
                            error = e.message
                        }
                    } catch (e: SerializationException) {
                        Log.e(LOG_TAG, "submitEdit JSON error: ${e.message}", e)
                        error = e.message ?: e::class.java.simpleName
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "submitEdit failed: ${e::class.java.simpleName} ${e.message}", e)
                        error = e.message ?: e::class.java.simpleName
                    }
                }
            },
            enabled = loaded && (
                if (providerId == OpenTuneProviderIds.HTTP_LIBRARY) {
                    (values["username"]?.isNotBlank() == true) && (values["password"]?.isNotBlank() == true)
                } else {
                    true
                }
                ),
        ) {
            Text(stringResource(R.string.server_edit_primary))
        }
        Button(onClick = onDone) { Text(stringResource(R.string.action_cancel)) }
    }
}
