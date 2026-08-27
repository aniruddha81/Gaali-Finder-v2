package com.aniruddha81.gaalifinderv2.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aniruddha81.gaalifinderv2.R
import com.aniruddha81.gaalifinderv2.domain.model.ClipFilter

/**
 * Category chips.
 *
 * Filters with nothing behind them are hidden rather than disabled — an empty "Downloaded" chip
 * is noise for a user who has never synced.
 */
@Composable
fun ClipFilterRow(
    selected: ClipFilter,
    availableFilters: List<ClipFilter>,
    onFilterChange: (ClipFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(availableFilters, key = { it.name }) { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onFilterChange(filter) },
                label = { Text(stringResource(filter.labelRes)) },
                shape = FilterChipDefaults.shape,
            )
        }
    }
}

private val ClipFilter.labelRes: Int
    get() = when (this) {
        ClipFilter.All -> R.string.filter_all
        ClipFilter.New -> R.string.filter_new
        ClipFilter.Downloaded -> R.string.filter_downloaded
        ClipFilter.MyClips -> R.string.filter_mine
    }
