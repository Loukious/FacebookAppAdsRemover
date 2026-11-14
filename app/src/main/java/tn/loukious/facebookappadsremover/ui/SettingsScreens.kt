package tn.loukious.facebookappadsremover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import tn.loukious.facebookappadsremover.BuildConfig
import tn.loukious.facebookappadsremover.R

/** Everything the screens read; owned by MainActivity. */
interface SettingsModel {
    val moduleState: ModuleState
    val frameworkDetail: String?
    val bound: Boolean
    val changed: Boolean
    val keywords: String
    val launcherVisible: Boolean
    val contributors: List<Pair<Contributor, ImageBitmap?>>
    fun isOn(spec: ToggleSpec): Boolean
    fun setOn(spec: ToggleSpec, on: Boolean)
    fun updateKeywords(value: String)
    fun updateLauncherVisible(visible: Boolean)
    fun restartFacebook()
    fun sessionExport()
    fun sessionImportLatest()
    fun sessionImportFile()
    fun openUrl(url: String)
    val reportLoading: Boolean
    fun saveReport()
    fun shareReport()
}

private sealed interface Screen {
    data object Home : Screen
    data class Section(val title: String) : Screen
    data object Diagnostics : Screen
    data object About : Screen

    val route: String
        get() = when (this) {
            Home -> "home"
            is Section -> "section:$title"
            Diagnostics -> "diagnostics"
            About -> "about"
        }

    companion object {
        fun fromRoute(route: String): Screen = when {
            route == "diagnostics" -> Diagnostics
            route == "about" -> About
            route.startsWith("section:") ->
                route.removePrefix("section:").let { t ->
                    if (TOGGLE_SECTIONS.any { it.title == t }) Section(t) else Home
                }
            else -> Home
        }
    }
}

private const val REPO = "https://github.com/Loukious/FacebookAppAdsRemover"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsApp(model: SettingsModel) {
    // Saved as route strings so rotation or a theme switch keeps the page.
    val stack = rememberSaveable(
        saver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<Screen>, String>(
            save = { list -> list.map { it.route } },
            restore = { routes -> mutableStateListOf(*routes.map(Screen::fromRoute).toTypedArray()) },
        ),
    ) { mutableStateListOf<Screen>(Screen.Home) }
    // Home's scroll position survives a visit to a section page.
    val homeList = rememberLazyListState()
    BackHandler(enabled = stack.size > 1) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    val slide = with(LocalDensity.current) { 24.dp.roundToPx() }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    var depth by remember { mutableStateOf(1) }
    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = {
            // Forward slides in from the right, back from the left.
            val sign = if (stack.size >= depth) 1 else -1
            depth = stack.size
            (slideInHorizontally(spatial) { sign * slide } + fadeIn(effects)) togetherWith
                (slideOutHorizontally(spatial) { -sign * slide } + fadeOut(effects))
        },
        label = "screen",
    ) { screen ->
        val back: (() -> Unit)? = if (screen == Screen.Home) null else ({
            // AnimatedContent keeps the outgoing page clickable during exit.
            if (stack.size > 1 && stack.last() == screen) stack.removeAt(stack.lastIndex)
        })
        when (screen) {
            Screen.Home -> HomeScreen(model, homeList) { stack.add(it) }
            is Screen.Section -> SectionScreen(model, screen.title, back!!)
            Screen.Diagnostics -> DiagnosticsScreen(model, back!!)
            Screen.About -> AboutScreen(model, back!!)
        }
    }
}

/** Large flexible app bar that collapses on scroll, over a lazy list. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    listState: LazyListState = rememberLazyListState(),
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottom + 24.dp),
            content = content,
        )
    }
}

private fun summaryFor(model: SettingsModel, section: ToggleSection): String {
    if (section.toggles.isEmpty()) return when (section.extra) {
        SectionExtra.SESSION -> "Back up and restore your login"
        SectionExtra.LAUNCHER -> if (model.launcherVisible) "Launcher icon shown" else "Launcher icon hidden"
        else -> ""
    }
    val on = section.toggles.count { model.isOn(it) }
    return when {
        on == 0 -> "Off"
        section.toggles.size == 1 -> "On"
        else -> "$on of ${section.toggles.size} on"
    }
}

@Composable
private fun HomeScreen(model: SettingsModel, list: LazyListState, open: (Screen) -> Unit) {
    SettingsScaffold("Ads Remover", onBack = null, listState = list) {
        item("status") { StatusCard(model.moduleState, model.frameworkDetail) }
        item("apply") {
            // Appears only once something changed since the last restart.
            AnimatedVisibility(
                visible = model.changed,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                RowGroup(listOf { shape ->
                    NavigationRow(
                        shape, R.drawable.ic_restart, "Apply changes",
                        "Restart Facebook so new settings take effect",
                    ) { model.restartFacebook() }
                }, Modifier.padding(top = 4.dp))
            }
        }
        val sections = TOGGLE_SECTIONS
        item("filters") {
            SectionHeader("Filters")
            val rows: List<@Composable (androidx.compose.ui.graphics.Shape) -> Unit> =
                sections.take(3).map<ToggleSection, @Composable (androidx.compose.ui.graphics.Shape) -> Unit> { s ->
                    { shape ->
                        NavigationRow(shape, SECTION_INFO[s.title]?.icon, s.title, summaryFor(model, s)) {
                            open(Screen.Section(s.title))
                        }
                    }
                }
            RowGroup(rows)
        }
        item("app") {
            SectionHeader("App")
            val rows: List<@Composable (androidx.compose.ui.graphics.Shape) -> Unit> =
                sections.drop(3).map<ToggleSection, @Composable (androidx.compose.ui.graphics.Shape) -> Unit> { s ->
                    { shape ->
                        NavigationRow(shape, SECTION_INFO[s.title]?.icon, s.title, summaryFor(model, s)) {
                            open(Screen.Section(s.title))
                        }
                    }
                } + listOf(
                    { shape ->
                        NavigationRow(shape, R.drawable.ic_terminal, "Diagnostics", "Hook discovery log") {
                            open(Screen.Diagnostics)
                        }
                    },
                    { shape ->
                        NavigationRow(shape, R.drawable.ic_info, "About", "Version, contributors and links") {
                            open(Screen.About)
                        }
                    },
                )
            RowGroup(rows)
        }
    }
}

@Composable
private fun SectionScreen(model: SettingsModel, title: String, back: () -> Unit) {
    val section = TOGGLE_SECTIONS.first { it.title == title }
    var confirmHide by remember { mutableStateOf(false) }
    SettingsScaffold(title, back) {
        SECTION_INFO[title]?.intro?.let { intro ->
            item("intro") { Footnote(intro, Modifier.padding(bottom = 4.dp)) }
        }
        item("rows") {
            val rows = ArrayList<@Composable (androidx.compose.ui.graphics.Shape) -> Unit>()
            for (spec in section.toggles) {
                rows += { shape ->
                    SwitchRow(
                        shape, TOGGLE_ICONS[spec.key], spec.title, spec.subtitle,
                        checked = model.isOn(spec), enabled = model.bound,
                    ) { model.setOn(spec, it) }
                }
            }
            when (section.extra) {
                SectionExtra.KEYWORDS -> rows += { shape ->
                    ContentRow(shape) {
                        OutlinedTextField(
                            value = model.keywords,
                            onValueChange = model::updateKeywords,
                            enabled = model.bound,
                            label = { Text("Keywords") },
                            placeholder = { Text("giveaway, crypto, follow me on") },
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                SectionExtra.SESSION -> rows += { shape ->
                    ContentRow(shape) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Login backup", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Saved to Download/FacebookAppAdsRemover. Restore it on this or another " +
                                    "device, then tap Apply changes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(model::sessionExport, Modifier.weight(1f)) { Text("Export") }
                                FilledTonalButton(model::sessionImportLatest, Modifier.weight(1f)) { Text("Import latest") }
                            }
                            FilledTonalButton(model::sessionImportFile, Modifier.fillMaxWidth()) {
                                Text("Import from file…")
                            }
                        }
                    }
                }
                SectionExtra.LAUNCHER -> rows += { shape ->
                    SwitchRow(
                        shape, R.drawable.ic_apps, "Show launcher icon",
                        "Hide this app from the app drawer. You can still open it from LSPosed or Vector",
                        checked = model.launcherVisible, enabled = true,
                    ) { if (it) model.updateLauncherVisible(true) else confirmHide = true }
                }
                SectionExtra.NONE -> {}
            }
            RowGroup(rows)
        }
        if (section.toggles.isNotEmpty()) {
            item("note") { Footnote("Changes take effect after Facebook restarts.") }
        }
    }
    if (confirmHide) {
        AlertDialog(
            onDismissRequest = { confirmHide = false },
            title = { Text("Hide the launcher icon?") },
            text = {
                Text(
                    "The icon disappears from your app drawer. Facebook keeps working, and so do its hooks.\n\n" +
                        "To open this screen again: Xposed / Vector → Modules → Facebook App Ads Remover → settings.",
                )
            },
            confirmButton = {
                TextButton({ confirmHide = false; model.updateLauncherVisible(false) }) { Text("Hide") }
            },
            dismissButton = { TextButton({ confirmHide = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DiagnosticsScreen(model: SettingsModel, back: () -> Unit) {
    SettingsScaffold("Diagnostics", back) {
        item("intro") {
            Footnote(
                "A report of what the module found inside Facebook. Attach it when you report a problem. " +
                    "Facebook must have been opened at least once.",
            )
        }
        item("actions") {
            val busy = model.reportLoading
            RowGroup(listOf(
                { shape ->
                    NavigationRow(
                        shape, R.drawable.ic_download, "Save report",
                        if (busy) "Asking Facebook…" else "Save it as a text file", Trailing.None,
                    ) { model.saveReport() }
                },
                { shape ->
                    NavigationRow(
                        shape, R.drawable.ic_open_in_new, "Share report",
                        "Send the file to an issue, email or chat", Trailing.None,
                    ) { model.shareReport() }
                },
            ))
        }
    }
}

@Composable
private fun AboutScreen(model: SettingsModel, back: () -> Unit) {
    SettingsScaffold("About", back) {
        item("identity") {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenMargin),
                shape = RoundedCornerShape(Tokens.HeaderCardCorner),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MorphingBadge(Modifier.size(96.dp)) {
                        // The launcher icon's own blue background layer under its foreground.
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                        Image(
                            painterResource(R.drawable.ic_launcher_foreground), null,
                            Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Facebook App Ads Remover", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Removes ads and clutter from the Facebook app. Adds media downloads and account tools.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_tag), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Button({ model.openUrl(REPO) }, Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(painterResource(R.drawable.ic_star), null)
                        Spacer(Modifier.width(8.dp))
                        Text("Star on GitHub")
                    }
                }
            }
        }
        item("links") {
            Spacer(Modifier.height(8.dp))
            RowGroup(listOf(
                { s -> NavigationRow(s, R.drawable.ic_history, "Changelog", "Release notes for every version", Trailing.External) { model.openUrl("$REPO/releases") } },
                { s -> NavigationRow(s, R.drawable.ic_terminal, "Source code", "Loukious/FacebookAppAdsRemover", Trailing.External) { model.openUrl(REPO) } },
                { s -> NavigationRow(s, R.drawable.ic_bug, "Report a problem or suggest a feature", "Opens an issue on GitHub", Trailing.External) { model.openUrl("$REPO/issues") } },
            ))
        }
        item("contributors") {
            SectionHeader("Contributors")
            RowGroup(model.contributors.map<Pair<Contributor, ImageBitmap?>, @Composable (androidx.compose.ui.graphics.Shape) -> Unit> { (c, avatar) ->
                { shape -> ContributorRow(shape, c, avatar) { model.openUrl(c.url) } }
            })
            Footnote(
                "Original design by hxreborn",
                Modifier.clickable { model.openUrl("https://github.com/hxreborn") },
            )
        }
    }
}
