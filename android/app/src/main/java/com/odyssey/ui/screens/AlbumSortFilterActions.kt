package com.odyssey.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.odyssey.catalog.AlbumFilter
import com.odyssey.catalog.AlbumSort

/**
 * Sort + Filter dropdown buttons used in BOTH the AIO Albums tab and
 * the YSH Albums tab. Extracted so the two screens stay in visual
 * lockstep: any future tweak (new option, label change, accessibility
 * fix) lands once.
 *
 * The caller controls which options are exposed via [availableSorts]
 * and [availableFilters] — YSH hides `HasOnBackup` because it has no
 * backup upload path yet, so showing the option would just yield an
 * empty list. testTags follow the original AIO scheme
 * (`album-sort-menu`, `album-sort-<NAME>`, `album-filter-menu`,
 * `album-filter-<NAME>`) so existing UI tests for AIO still pass; YSH
 * tests use the same scheme automatically.
 *
 * Labels live here too. If a label ever needs to differ per show
 * (e.g. "Chronological" → "Oldest first" for one of them), pass a
 * label-resolver lambda in — but right now both want the same copy.
 */
@Composable
fun AlbumSortFilterActions(
    sortMode: AlbumSort,
    onSortChange: (AlbumSort) -> Unit,
    filterMode: AlbumFilter,
    onFilterChange: (AlbumFilter) -> Unit,
    availableSorts: List<AlbumSort> = AlbumSort.values().toList(),
    availableFilters: List<AlbumFilter> = AlbumFilter.values().toList(),
) {
    var filterMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { filterMenuOpen = true },
            modifier = Modifier.testTag("album-filter-menu"),
        ) {
            Icon(Icons.Default.FilterList, contentDescription = "Filter albums")
        }
        DropdownMenu(
            expanded = filterMenuOpen,
            onDismissRequest = { filterMenuOpen = false },
        ) {
            for (f in availableFilters) {
                DropdownMenuItem(
                    text = { Text(f.label()) },
                    trailingIcon = if (f == filterMode) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        onFilterChange(f)
                        filterMenuOpen = false
                    },
                    modifier = Modifier.testTag("album-filter-${f.name}"),
                )
            }
        }
    }
    Box {
        IconButton(
            onClick = { sortMenuOpen = true },
            modifier = Modifier.testTag("album-sort-menu"),
        ) {
            Icon(Icons.Default.Sort, contentDescription = "Sort albums")
        }
        DropdownMenu(
            expanded = sortMenuOpen,
            onDismissRequest = { sortMenuOpen = false },
        ) {
            for (mode in availableSorts) {
                DropdownMenuItem(
                    text = { Text(mode.label()) },
                    trailingIcon = if (mode == sortMode) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        onSortChange(mode)
                        sortMenuOpen = false
                    },
                    modifier = Modifier.testTag("album-sort-${mode.name}"),
                )
            }
        }
    }
}

internal fun AlbumSort.label() = when (this) {
    AlbumSort.Default -> "Default"
    AlbumSort.Chronological -> "Chronological"
    AlbumSort.MostDownloaded -> "Most downloaded"
}

internal fun AlbumFilter.label() = when (this) {
    AlbumFilter.All -> "All albums"
    AlbumFilter.HasOnPhone -> "On phone"
    AlbumFilter.HasOnBackup -> "On backup"
}
