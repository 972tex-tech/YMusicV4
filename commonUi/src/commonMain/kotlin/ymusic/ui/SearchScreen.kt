package ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.search
import ymusic.dynamicengine.yt.searchSuggestions
import ymusic.ui.resources.Res
import ymusic.ui.resources.cd_back
import ymusic.ui.resources.cd_clear
import ymusic.ui.resources.search_no_results
import ymusic.ui.resources.search_type_to_search
import ymusic.ui.resources.search_youtube

/**
 * YouTube search: live suggestions while typing, submitted query → paged results. Result taps play
 * through [coordinator] (autoplay queue fill).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(graph: AppGraph, coordinator: PlaybackCoordinator, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf(listOf<String>()) }
    val pager = remember {
        Pager(scope) { continuation ->
            graph.engines.search(
                query = if (continuation == null) submitted else null,
                continuation = continuation,
                ctx = graph.ytContext,
            )
        }
    }

    // Debounced suggestions; cleared once a query is submitted.
    LaunchedEffect(query, submitted) {
        if (submitted != null || query.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        suggestions = try {
            graph.engines.searchSuggestions(query, graph.ytContext)
        } catch (e: Exception) {
            emptyList() // suggestions are best-effort sugar; the search box still works
        }
    }

    fun submit(q: String) {
        if (q.isBlank()) return
        query = q
        submitted = q
        pager.refresh()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.cd_back))
                }
            },
            title = {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; submitted = null },
                    placeholder = { Text(stringResource(Res.string.search_youtube)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; submitted = null }) {
                                Icon(Icons.Default.Close, stringResource(Res.string.cd_clear))
                            }
                        }
                    },
                )
            },
        )
        when {
            submitted == null && suggestions.isNotEmpty() -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(suggestions) { s ->
                        Text(
                            s,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { submit(s) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
            submitted == null -> CenteredBox {
                Text(stringResource(Res.string.search_type_to_search), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> FeedList(pager, coordinator, emptyMessage = stringResource(Res.string.search_no_results))
        }
    }
}
