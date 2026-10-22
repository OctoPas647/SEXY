package com.octopas.sexy.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octopas.sexy.EditView
import com.octopas.sexy.GhStage
import com.octopas.sexy.GitHubViewModel
import com.octopas.sexy.data.DiffLine
import com.octopas.sexy.data.DiffType

private val AddGreen = Color(0xFF22C55E)
private val DelRed = Color(0xFFEF4444)

@Composable
fun GitHubScreen(vm: GitHubViewModel) {
    BackHandler(enabled = vm.stage !is GhStage.NeedToken && vm.stage !is GhStage.Repos) { vm.back() }
    when (val s = vm.stage) {
        GhStage.NeedToken -> TokenScreen(vm)
        GhStage.Repos -> RepoListScreen(vm)
        GhStage.Repo -> RepoScreen(vm)
        GhStage.Running -> RunningScreen(vm)
        GhStage.Review -> ReviewScreen(vm)
        is GhStage.Done -> DoneScreen(vm, s)
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GhTopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { actions() },
    )
}

@Composable
private fun ErrorCard(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------- token ----------------

@Composable
private fun TokenScreen(vm: GitHubViewModel) {
    val context = LocalContext.current
    var token by rememberSaveable { mutableStateOf("") }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { GhTopBar("GitHub") }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Let your PC's AI edit your repos", style = MaterialTheme.typography.headlineSmall)
            Text(
                "It never touches your main branch. Every change goes to a new branch and a pull request, so you (and Claude) review it first.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Create a token (2 minutes)", fontWeight = FontWeight.SemiBold)
                    listOf(
                        "1. Tap the button below and sign in to GitHub.",
                        "2. Name: SEXY · Expiration: 90 days.",
                        "3. Repository access → Only select repositories → pick your apps (e.g. BingO).",
                        "4. Permissions → Repository → Contents: Read and write, Pull requests: Read and write.",
                        "5. Generate token, copy it, paste it here.",
                    ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            OutlinedButton(onClick = { openUrl(context, "https://github.com/settings/personal-access-tokens/new") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create token on GitHub")
            }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("GitHub token (github_pat_…)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            vm.error?.let { ErrorCard(it) }
            Button(onClick = { vm.saveToken(token) }, enabled = token.isNotBlank() && !vm.loading, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (vm.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Save & connect")
            }
            Text(
                "The token is stored encrypted on this phone only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------- repo list ----------------

@Composable
private fun RepoListScreen(vm: GitHubViewModel) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GhTopBar("Repositories", vm.login?.let { "@$it" }) {
                IconButton(onClick = { vm.loadRepos() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = vm.repoQuery,
                onValueChange = { vm.repoQuery = it },
                placeholder = { Text("Search repos") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let { Box(Modifier.padding(16.dp)) { ErrorCard(it) } }
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.filteredRepos, key = { it.fullName }) { r ->
                    ListItem(
                        leadingContent = { Icon(if (r.private) Icons.Outlined.Lock else Icons.Outlined.Public, null) },
                        headlineContent = { Text(r.name, fontWeight = FontWeight.Medium) },
                        supportingContent = {
                            Text(r.description ?: r.fullName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = { r.pushedAt?.take(10)?.let { Text(it, style = MaterialTheme.typography.labelSmall) } },
                        modifier = Modifier.clickable { vm.openRepo(r) },
                    )
                }
                if (!vm.loading && vm.repos.isEmpty() && vm.error == null) {
                    item {
                        Text(
                            "No repos visible. Make sure the token has access to your repositories.",
                            Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------------- one repo: task + file picker ----------------

@Composable
private fun RepoScreen(vm: GitHubViewModel) {
    val repo = vm.repo ?: return
    val context = LocalContext.current
    var branchMenu by remember { mutableStateOf(false) }
    val tokens = vm.selectedTokens
    val tooMuch = tokens > GitHubViewModel.MAX_INPUT_TOKENS

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GhTopBar(repo.name, repo.owner.login, onBack = { vm.back() }) {
                IconButton(onClick = { openUrl(context, repo.htmlUrl) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open on GitHub") }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${vm.selected.size} files selected · ~${"%.1f".format(tokens / 1000f)}k of ${GitHubViewModel.MAX_INPUT_TOKENS / 1000}k tokens",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (tooMuch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { vm.runAi() },
                        enabled = vm.task.isNotBlank() && !tooMuch && !vm.loading,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Let the AI do it")
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box {
                        OutlinedButton(onClick = { branchMenu = true }) {
                            Icon(Icons.Outlined.AccountTree, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Branch: ${vm.branch}")
                            Icon(Icons.Outlined.ExpandMore, null)
                        }
                        DropdownMenu(expanded = branchMenu, onDismissRequest = { branchMenu = false }) {
                            vm.branches.forEach { b ->
                                DropdownMenuItem(text = { Text(b.name) }, onClick = { vm.selectBranch(b.name); branchMenu = false })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = vm.task,
                        onValueChange = { vm.task = it },
                        label = { Text("What should the AI do?") },
                        placeholder = { Text("e.g. Add a dark-mode switch to the settings screen and remember the choice") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    vm.agentsFile?.let {
                        Text("✓ $it is always included (your rules for the AI)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    vm.error?.let { ErrorCard(it) }
                    Text("Files the AI should read and may change", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Pick only the files that matter for the task - fewer files = faster and smarter answers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = vm.fileQuery,
                        onValueChange = { vm.fileQuery = it },
                        placeholder = { Text("Filter, e.g. settings kt") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = vm.showSelectedOnly, onClick = { vm.showSelectedOnly = !vm.showSelectedOnly }, label = { Text("Selected only") })
                        if (vm.selected.isNotEmpty()) TextButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                    }
                }
            }
            if (vm.loading && vm.files.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            items(vm.visibleFiles, key = { it.path }) { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.toggle(f.path) }.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = f.path in vm.selected, onCheckedChange = { vm.toggle(f.path) })
                    Column(Modifier.weight(1f)) {
                        Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val dir = f.path.substringBeforeLast('/', "")
                        if (dir.isNotEmpty()) {
                            Text(dir, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(
                        "${((f.size ?: 0) + 512) / 1024} KB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }
    }
}

// ---------------- running ----------------

@Composable
private fun RunningScreen(vm: GitHubViewModel) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { GhTopBar("AI is working…", vm.repo?.name) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                OutlinedButton(onClick = { vm.stopRun() }, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Stop") }
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(vm.phase, style = MaterialTheme.typography.titleMedium)
            vm.stats?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (vm.reasoning.isNotEmpty()) ThinkingBlock(vm.reasoning, live = vm.output.isEmpty())
            if (vm.output.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        vm.output.takeLast(3000),
                        Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            Text(
                "Big tasks can take a few minutes. You can switch tabs - it keeps working while the app is open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------- review ----------------

@Composable
private fun ReviewScreen(vm: GitHubViewModel) {
    val added = vm.edits.sumOf { it.added }
    val removed = vm.edits.sumOf { it.removed }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { GhTopBar("Review changes", "${vm.edits.size} file${if (vm.edits.size == 1) "" else "s"} · +$added −$removed", onBack = { vm.discard() }) },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            vm.error?.let { item { ErrorCard(it) } }
            if (vm.summary.isNotBlank()) {
                item {
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text("AI summary", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                            MarkdownText(vm.summary)
                        }
                    }
                }
            }
            vm.edits.forEach { v ->
                item(key = "h:" + v.edit.path) { FileHeader(v, open = v.edit.path in vm.expanded) { vm.toggleExpanded(v.edit.path) } }
                if (v.edit.path in vm.expanded) {
                    items(v.lines.size, key = { i -> "l:${v.edit.path}:$i" }) { i -> DiffRow(v.lines[i]) }
                }
            }
            item { ReviseAndCommit(vm) }
        }
    }
}

@Composable
private fun FileHeader(v: EditView, open: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = when {
                v.edit.newContent == null -> Icons.Outlined.Delete to DelRed
                v.isNew -> Icons.Outlined.Add to AddGreen
                else -> Icons.Outlined.Edit to MaterialTheme.colorScheme.primary
            }
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
            Text(v.edit.path, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("+${v.added}", color = AddGreen, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            Text("−${v.removed}", color = DelRed, style = MaterialTheme.typography.labelMedium)
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
    }
}

@Composable
private fun DiffRow(l: DiffLine) {
    if (l.type == DiffType.GAP) {
        Text(
            "⋯ ${l.text}",
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        return
    }
    val bg = when (l.type) {
        DiffType.ADD -> AddGreen.copy(alpha = 0.16f)
        DiffType.DEL -> DelRed.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    val sign = when (l.type) { DiffType.ADD -> "+"; DiffType.DEL -> "−"; else -> " " }
    Row(Modifier.fillMaxWidth().background(bg)) {
        Text(
            (l.newNo ?: l.oldNo)?.toString() ?: "",
            Modifier.width(38.dp).padding(end = 4.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.End,
        )
        Text("$sign ${l.text}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun ReviseAndCommit(vm: GitHubViewModel) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Not right?", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = vm.revision,
            onValueChange = { vm.revision = it },
            placeholder = { Text("Tell the AI what to fix, e.g. \"also update the strings file\"") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { vm.revise() }, enabled = vm.revision.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Ask the AI to revise")
        }
        if (vm.edits.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Looks good?", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = vm.newBranch,
                onValueChange = { vm.newBranch = it },
                label = { Text("New branch") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.prTitle,
                onValueChange = { vm.prTitle = it },
                label = { Text("Pull request title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.createPr() },
                enabled = !vm.creating && vm.newBranch.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (vm.creating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Commit & open pull request")
            }
            Text(
                "Commits to the new branch only - '${vm.branch}' is not touched until you merge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { vm.discard() }, modifier = Modifier.fillMaxWidth()) { Text("Discard changes") }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- done ----------------

@Composable
private fun DoneScreen(vm: GitHubViewModel, done: GhStage.Done) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { GhTopBar("Pull request opened", vm.repo?.name) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(72.dp), tint = AddGreen)
            Text("PR #${done.pr.number}${if (done.pr.draft) " (draft)" else ""}", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${done.branch} → ${done.base}",
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openUrl(context, done.pr.htmlUrl) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open on GitHub")
            }
            OutlinedButton(
                onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            "Please review this pull request, made by my local AI model: ${done.pr.htmlUrl}\n" +
                                "Check for bugs, things that won't compile, Android lifecycle/threading issues and anything risky. " +
                                "Then fix and improve it, and tell me if it's safe to merge."
                        )
                    )
                    Toast.makeText(context, "Copied - paste it to Claude", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy review request for Claude")
            }
            TextButton(onClick = { vm.newTask() }) { Text("New task") }
        }
    }
}
