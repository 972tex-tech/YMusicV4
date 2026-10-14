package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_sort
import io.ymusic.ui.resources.cd_sort_ascending
import io.ymusic.ui.resources.cd_sort_descending
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One sort choice: a localized [label] and the ascending [comparator] it sorts by. */
internal class SortOption<T>(val label: StringResource, val comparator: Comparator<T>)

/** The selected sort option (index into a tab's options) + direction. Saveable so it survives tab
 *  switches and rotation; per the product decision it resets on process death (no settings store). */
internal class SortState(index: Int = 0, descending: Boolean = false) {
    var index by mutableStateOf(index)
    var descending by mutableStateOf(descending)

    fun <T> sort(items: List<T>, options: List<SortOption<T>>): List<T> {
        val sorted = items.sortedWith(options.getOrElse(index) { options.first() }.comparator)
        return if (descending) sorted.asReversed() else sorted
    }

    companion object {
        val Saver = listSaver<SortState, Any>(
            save = { listOf(it.index, it.descending) },
            restore = { SortState(it[0] as Int, it[1] as Boolean) },
        )
    }
}

@Composable
internal fun rememberSortState(key: String): SortState =
    rememberSaveable(key, saver = SortState.Saver) { SortState() }

/** A full-width row that right-aligns the [SortControl] — used by tabs where sort is the only
 *  toolbar. The Folders tab instead drops [SortControl] into its scan row. */
@Composable
internal fun SortBar(labels: List<StringResource>, state: SortState, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(LibrarySubToolbarHeight).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        SortControl(labels, state)
    }
}

/** The sort control: a chip naming the current key (tap → menu) + a direction toggle. Picking the
 *  current key in the menu flips direction; picking another selects it ascending. */
@Composable
internal fun SortControl(labels: List<StringResource>, state: SortState) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { open = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.Sort, stringResource(Res.string.cd_sort), Modifier.size(16.dp), tint = DesignColors.InkSoft)
                Text(
                    stringResource(labels[state.index]),
                    style = TextStyle(fontSize = 12.5.sp, color = DesignColors.Ink),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                labels.forEachIndexed { i, label ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = {
                            if (i == state.index) {
                                Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = DesignColors.Accent)
                            }
                        },
                        onClick = {
                            if (i == state.index) state.descending = !state.descending else {
                                state.index = i
                                state.descending = false
                            }
                            open = false
                        },
                    )
                }
            }
        }
        IconButton(onClick = { state.descending = !state.descending }, modifier = Modifier.size(32.dp)) {
            Icon(
                if (state.descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                stringResource(if (state.descending) Res.string.cd_sort_descending else Res.string.cd_sort_ascending),
                Modifier.size(17.dp),
                tint = DesignColors.InkSoft,
            )
        }
    }
}
