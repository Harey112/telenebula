package com.telenebula.app

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

/** The AGENTS.md rules that a compiler cannot check. */
class ArchitectureTest {
    private val app = Konsist.scopeFromProduction("app")

    /** Only the theme, the always-dark call surfaces and overlays drawn over media or camera may write a colour. */
    private val literalColourAllowed = listOf(
        "/ui/theme/",
        "/ui/screens/call/",
        "/ui/fragments/CallControls.kt",
        "/ui/fragments/Media.kt",
        "/ui/fragments/MessageBubble.kt",
        "/ui/screens/newcontact/NewContactScreen.kt", // the QR scanner's viewfinder scrim, drawn over the camera
    )

    @Test
    fun `colours come from the theme tokens`() {
        app.files
            .filterNot { file -> literalColourAllowed.any { file.unixPath.contains(it) } }
            .assertFalse { file ->
                file.text.contains("Color(0x") || file.text.contains("colorScheme") || file.text.contains("isSystemInDarkTheme")
            }
    }

    /** Material3 is a primitive supplier only; chrome (Scaffold, TopAppBar, Card, Button, dialogs, sheets) stays ours. */
    private val material3Allowed = setOf(
        "Text", "Switch", "SwitchDefaults", "Slider", "SliderDefaults", "HorizontalDivider", "CircularProgressIndicator",
        "Icon", "IconButton", "MaterialTheme", "LocalContentColor", "LocalTextStyle", "lightColorScheme", "darkColorScheme",
        // the clock face for quiet hours, the one piece of chrome worth more than a hand-built copy
        "TimePicker", "TimePickerDefaults", "rememberTimePickerState", "ExperimentalMaterial3Api",
    )

    @Test
    fun `material3 imports stay on the primitive allow-list`() {
        app.imports
            .filter { it.name.startsWith("androidx.compose.material3.") }
            .assertTrue { it.name.substringAfterLast('.') in material3Allowed }
    }

    /**
     * JSON is for what leaves the process: the wire, the database columns that hold it, the files
     * on disk and the config handed to nebula. Inside, data is Kotlin types — a screen or a view
     * model that encodes or parses JSON has put a boundary where there is none.
     */
    private val jsonAllowed = listOf(
        "/core/src/main/kotlin/com/telenebula/core/protocol/",
        "/core/src/main/kotlin/com/telenebula/core/db/",
        "/core/src/main/kotlin/com/telenebula/core/nebula/",
        "/core/src/main/kotlin/com/telenebula/core/backup/",
        "/core/src/main/kotlin/com/telenebula/core/model/",
        "/core/src/main/kotlin/com/telenebula/core/CoreJson.kt",
        // the notification cache the receivers read while the app is not running is a file on disk
        "/core/src/main/kotlin/com/telenebula/core/notify/",
        "/vpn/",
        // reads and writes files: prefs.json, profile.json, the emoji catalog, the update feed,
        // the diagnostics report the user exports
        "/app/src/main/kotlin/com/telenebula/app/platform/",
        "/app/src/main/kotlin/com/telenebula/app/ui/screens/diagnostics/",
        // hands the one Json instance to the platform stores; it never encodes anything itself
        "/app/src/main/kotlin/com/telenebula/app/AppGraph.kt",
    )

    @Test
    fun `json stays at the edges of the process`() {
        Konsist.scopeFromProduction().files
            .filterNot { file -> jsonAllowed.any { file.unixPath.contains(it) } }
            .assertFalse { file ->
                file.imports.any { it.name.startsWith("kotlinx.serialization.json") || it.name == "com.telenebula.core.CoreJson" } ||
                    file.text.contains("Json.encodeToString(") || file.text.contains("Json.decodeFromString(")
            }
    }

    /** A view model is its own file, named after itself, so the rest of the layer can be found. */
    @Test
    fun `view models live in their own files`() {
        app.classes()
            .filter { it.name.endsWith("ViewModel") && it.hasParentWithName("ViewModel") }
            .assertTrue { it.containingFile.name == it.name }
    }

    /** One `StateFlow` of one UiState per view model; nothing derivable is stored twice. */
    @Test
    fun `view models expose one state flow`() {
        app.classes()
            .filter { it.name.endsWith("ViewModel") && it.hasParentWithName("ViewModel") }
            .assertTrue { vm -> vm.properties().count { it.hasPublicOrDefaultModifier && it.type?.name?.startsWith("StateFlow") == true } <= 1 }
    }

    /** The Kotlin rules a compiler cannot check: no `!!`, no unstructured concurrency, no threads. */
    @Test
    fun `no double bang, global scope, runBlocking or raw threads`() {
        Konsist.scopeFromProduction().files
            .assertFalse { file ->
                val code = file.text.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
                Regex("[A-Za-z0-9_)\\]]!!").containsMatchIn(code) || "GlobalScope" in code || "runBlocking" in code || Regex("\\bThread\\(").containsMatchIn(code)
            }
    }

    /** Screens read one state and call the view model; state lives in the view model, not in composables. */
    @Test
    fun `screen composables hold no mutable state`() {
        app.files
            .filter { it.unixPath.contains("/ui/screens/") && it.nameWithExtension.endsWith("Screen.kt") }
            .assertFalse { file -> file.imports.any { it.name == "androidx.compose.runtime.mutableStateOf" } }
    }

    /** Fragments are stateless: parameters in, lambdas out, and never a store, a view model or the graph. */
    @Test
    fun `fragments read no store or view model`() {
        app.files
            .filter { it.unixPath.contains("/ui/fragments/") }
            .assertFalse { file ->
                file.imports.any {
                    it.name == "com.telenebula.app.LocalAppGraph" ||
                        it.name == "androidx.lifecycle.ViewModel" ||
                        it.name == "androidx.lifecycle.viewmodel.compose.viewModel" ||
                        it.name.endsWith(".collectAsStateWithLifecycle")
                }
            }
    }

    /** Only a screen's entry point sees the view model; its private composables take data and lambdas. */
    @Test
    fun `screen private composables take no view model`() {
        app.files
            .filter { it.unixPath.contains("/ui/screens/") }
            .flatMap { it.functions() }
            .filter { it.hasPrivateModifier }
            .assertFalse { fn -> fn.parameters.any { it.type.name.endsWith("ViewModel") } }
    }

    /** Feedback is a NoticeCenter concern; nothing else pops system UI, and no overlay gets its own window. */
    private val windowOverlays = setOf(
        "android.widget.Toast",
        "androidx.compose.material3.AlertDialog",
        "androidx.compose.material3.ModalBottomSheet",
        "androidx.compose.ui.window.Dialog",
        "androidx.compose.ui.window.Popup",
    )

    @Test
    fun `no toasts, dialogs or popups outside the runtime`() {
        app.files
            .filterNot { it.unixPath.contains("/runtime/AppRuntime.kt") }
            .assertFalse { file -> file.imports.any { it.name in windowOverlays } }
    }
}

private val com.lemonappdev.konsist.api.declaration.KoFileDeclaration.unixPath: String get() = path.replace('\\', '/')
