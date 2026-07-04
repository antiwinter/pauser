package com.insomnia.player.manager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.insomnia.player.PlaybackSource
import com.insomnia.player.PlaybackSpec

class SourceManager(private val spec: PlaybackSpec) {
    val sourceCount: Int = spec.sources.size
    val sources: List<PlaybackSource> = spec.sources

    var selectedIndex by mutableIntStateOf(spec.state.sourceIndex)
        private set

    var onSourceSelected: ((PlaybackSpec) -> Unit)? = null

    val menuEntry: PlayerMenuEntry = PlayerMenuEntry(
        label = { "Select source" },
        children = ::buildChildren,
    )

    fun selectSource(index: Int) {
        if (index in spec.sources.indices && index != selectedIndex) {
            selectedIndex = index
            onSourceSelected?.invoke(spec.withSourceIndex(index))
        }
    }

    private fun buildChildren(): List<PlayerMenuEntry> =
        spec.sources.mapIndexed { i, src ->
            PlayerMenuEntry(
                label = { "Source ${i + 1}" },
                children = { emptyList() },
                isSelected = { selectedIndex == i },
                onSelect = { selectSource(i) },
            )
        }
}
