package com.insomnia.content.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.navigation.NavHostController
import com.insomnia.content.contract.QueryOptions
import com.insomnia.content.ui.catalog.browse.QuerySpec
import com.insomnia.content.ui.catalog.browse.SearchModal
import com.insomnia.content.ui.catalog.browse.SearchScope
import com.insomnia.content.ui.catalog.browse.recentMultiSpec
import com.insomnia.core.osd.gOSD
import com.insomnia.storage.EndpointEntity
import com.insomnia.storage.StorageBindingsHolder
import kotlinx.coroutines.launch

/**
 * Top bar shown on every browse screen. Left: endpoint dropdown (with "Recent" / each
 * endpoint / "+ Add new"). Center-left: search. Right: OSD test, settings.
 *
 * Endpoint selection is a stack-replacing action: the new browse route is pushed with
 * `popUpTo(startDestinationId, inclusive = false)` so the start destination remains as
 * the back-stack base. Pressing Back from the new endpoint's browse returns to the start
 * destination (Recent) — not to the previously browsed endpoint — avoiding the confusing
 * "browse A → select B → browse B → Back → A" flow.
 *
 * The dropdown label tracks the last selection (kept in local state). On first
 * composition, the state is seeded from the current route's spec.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Header(nav: NavHostController) {
    val endpoints by StorageBindingsHolder.get().endpointDao.observeAll()
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var searchModalOpen by remember { mutableStateOf(false) }

    var selectedEndpointId by remember { mutableStateOf<String?>(null) }

    // First-mount redirect: if we landed on the bare start route (no spec), the start
    // destination is just a placeholder — Header owns the "default location" decision,
    // builds the spec from RECENT_ROOT_LOCATION, and consumes the placeholder.
    LaunchedEffect(nav) {
        val specJson = nav.currentBackStackEntry?.arguments?.getString("querySpecJson")
        if (!specJson.isNullOrEmpty()) return@LaunchedEffect
        nav.navigate(Routes.browse(recentMultiSpec())) {
            popUpTo(nav.graph.startDestinationId) { inclusive = true }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EndpointDropdown(
            endpoints = endpoints,
            selectedEndpointId = selectedEndpointId,
            onSelect = { id ->
                selectedEndpointId = id
                if (id == null) {
                    scope.launch {
                        nav.navigate(Routes.browse(recentMultiSpec())) {
                            popUpTo(nav.graph.startDestinationId) { inclusive = false }
                        }
                    }
                } else {
                    nav.navigate(Routes.browse(listOf(QuerySpec(id, null, QueryOptions())))) {
                        popUpTo(nav.graph.startDestinationId) { inclusive = false }
                    }
                }
            },
            onAddNew = { nav.navigate(Routes.ADD_ENDPOINT) },
        )

        Button(
            onClick = { searchModalOpen = true },
            colors = headerButtonColors(),
        ) { Text("Search") }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { gOSD.msg("OSD test message") },
            colors = headerButtonColors(),
        ) { Text("[OSD]") }

        Button(
            onClick = { nav.navigate(Routes.SETTINGS) },
            colors = headerButtonColors(),
        ) { Text("Settings") }
    }

    if (searchModalOpen) {
        SearchModal(
            onDismiss = { searchModalOpen = false },
            onConfirm = { term, searchScope ->
                searchModalOpen = false
                val specs = buildSearchSpecs(term, searchScope, selectedEndpointId, endpoints)
                if (specs.isNotEmpty()) {
                    nav.navigate(Routes.browse(specs))
                }
            },
        )
    }
}

private fun buildSearchSpecs(
    term: String,
    scope: SearchScope,
    selectedEndpointId: String?,
    endpoints: List<EndpointEntity>,
): List<QuerySpec> = when (scope) {
    SearchScope.Global -> endpoints.map {
        QuerySpec(it.endpointId, null, QueryOptions(searchTerm = term, recursive = true))
    }
    SearchScope.Current -> {
        val id = selectedEndpointId
            ?: return buildSearchSpecs(term, SearchScope.Global, null, endpoints)
        listOf(QuerySpec(id, null, QueryOptions(searchTerm = term, recursive = true)))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EndpointDropdown(
    endpoints: List<EndpointEntity>,
    selectedEndpointId: String?,
    onSelect: (String?) -> Unit,
    onAddNew: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = selectedEndpointId
        ?.let { id -> endpoints.firstOrNull { it.endpointId == id }?.displayName ?: "Recent" }
        ?: "Recent"

    Box {
        Button(
            onClick = { expanded = true },
            colors = headerButtonColors(),
        ) { Text(label) }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Recent") },
                onClick = { onSelect(null); expanded = false },
            )
            endpoints.forEach { ep ->
                DropdownMenuItem(
                    text = { Text(ep.displayName) },
                    onClick = { onSelect(ep.endpointId); expanded = false },
                )
            }
            DropdownMenuItem(
                text = { Text("+ Add new") },
                onClick = { onAddNew(); expanded = false },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun headerButtonColors() = ButtonDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
    contentColor = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = MaterialTheme.colorScheme.primary,
    focusedContentColor = MaterialTheme.colorScheme.onPrimary,
    pressedContainerColor = MaterialTheme.colorScheme.primary,
    pressedContentColor = MaterialTheme.colorScheme.onPrimary,
)
