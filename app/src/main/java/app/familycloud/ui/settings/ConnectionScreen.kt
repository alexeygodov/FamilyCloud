package app.familycloud.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familycloud.R
import app.familycloud.data.settings.SavedConnection
import app.familycloud.ui.AppUpdateButton
import app.familycloud.ui.components.Notice
import app.familycloud.ui.components.NoticeTone
import app.familycloud.ui.theme.Dimens

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

@OptIn(ExperimentalMaterial3Api::class)
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
    var details by rememberSaveable { mutableStateOf(false) }
    val loginFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val version = remember {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_META_DATA).versionName
            ?: ""
    }
    val insecure = allowHttp || url.trim().startsWith("http://", ignoreCase = true)
    val spaceInPassword = password.startsWith(' ') || password.endsWith(' ')
    val canSubmit = !busy && url.isNotBlank() && login.isNotBlank() && password.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connection_title)) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                if (error != null) {
                    Notice(tone = NoticeTone.Error, text = error)
                }
                Button(
                    onClick = { onSubmit(url, login, password, allowHttp) },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.spinner),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            stringResource(R.string.connection_checking),
                            modifier = Modifier.padding(start = Dimens.s),
                        )
                    } else {
                        Text(stringResource(R.string.connection_test))
                    }
                }
                AppUpdateButton(label = stringResource(R.string.update_version_check, version))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Dimens.l),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!showBack) {
                Surface(
                    modifier = Modifier.size(Dimens.emptyIcon),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    stringResource(R.string.connection_intro),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                enabled = !busy,
                label = { Text(stringResource(R.string.connection_url)) },
                placeholder = { Text(stringResource(R.string.connection_url_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { loginFocus.requestFocus() }),
            )
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                enabled = !busy,
                label = { Text(stringResource(R.string.connection_login)) },
                supportingText = { Text(stringResource(R.string.login_case)) },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().focusRequester(loginFocus),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                enabled = !busy,
                label = { Text(stringResource(R.string.connection_password)) },
                supportingText = if (spaceInPassword) {
                    { Text(stringResource(R.string.connection_password_space)) }
                } else {
                    null
                },
                isError = spaceInPassword,
                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            stringResource(if (showPassword) R.string.hide_password else R.string.show_password),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    if (canSubmit) onSubmit(url, login, password, allowHttp)
                }),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.connection_http), modifier = Modifier.weight(1f))
                Switch(checked = allowHttp, onCheckedChange = { allowHttp = it }, enabled = !busy)
            }
            if (insecure) {
                Notice(tone = NoticeTone.Warning, text = stringResource(R.string.connection_http_hint))
            }
            TextButton(onClick = { details = !details }) {
                Text(stringResource(R.string.connection_details))
            }
            if (details && (saved != null || attemptedUrl != null)) {
                saved?.let {
                    Text(
                        stringResource(R.string.connection_auth_scheme, it.authScheme.name),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (attemptedUrl != null) {
                    Text(
                        stringResource(R.string.connection_attempt_url, attemptedUrl),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
