package app.familycloud.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.familycloud.ui.theme.Dimens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familycloud.R
import app.familycloud.data.settings.SavedConnection
import app.familycloud.ui.AppUpdateButton

@Composable
fun ConnectionRoute(
    viewModel: ConnectionViewModel,
    showBack: Boolean,
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val attemptedUrl by viewModel.attemptedUrl.collectAsStateWithLifecycle()
    ConnectionScreen(
        saved = saved,
        busy = busy,
        error = error,
        attemptedUrl = attemptedUrl,
        showBack = showBack,
        onBack = onBack,
        onSubmit = { url, login, password, allowHttp ->
            viewModel.submit(url, login, password, allowHttp, onConnected)
        },
    )
}

@Composable
fun ConnectionScreen(
    saved: SavedConnection?,
    busy: Boolean,
    error: String?,
    attemptedUrl: String?,
    showBack: Boolean,
    onBack: () -> Unit,
    onSubmit: (url: String, login: String, password: String, allowHttp: Boolean) -> Unit,
) {
    val initial = remember { saved }
    var url by rememberSaveable { mutableStateOf(initial?.baseUrl ?: "https://") }
    var login by rememberSaveable { mutableStateOf(initial?.login ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var allowHttp by rememberSaveable { mutableStateOf(initial?.allowHttp ?: false) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (showBack) {
                ConnectionTopBar(onBack)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Dimens.xl),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            if (!showBack) {
                Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.headlineSmall)
            }
            AppUpdateButton()
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.connection_url)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                ),
            )
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text(stringResource(R.string.connection_login)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false,
                ),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.connection_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                    }
                },
            )
            RowAllowHttp(allowHttp) { allowHttp = it }
            Text(stringResource(R.string.connection_http_hint), style = MaterialTheme.typography.bodySmall)
            if (password.startsWith(' ') || password.endsWith(' ')) {
                Text(stringResource(R.string.connection_password_space), color = MaterialTheme.colorScheme.error)
            }
            saved?.let {
                Text(
                    stringResource(R.string.connection_auth_scheme, it.authScheme.name),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (attemptedUrl != null) {
                Text(stringResource(R.string.connection_attempt_url, attemptedUrl))
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { onSubmit(url, login, password, allowHttp) },
                enabled = !busy && url.isNotBlank() && login.isNotBlank() && password.isNotEmpty(),
            ) {
                Text(
                    if (busy) stringResource(R.string.connection_checking) else stringResource(R.string.connection_test),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.connection_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        },
    )
}

@Composable
private fun RowAllowHttp(checked: Boolean, onChecked: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(stringResource(R.string.connection_http))
    }
}
