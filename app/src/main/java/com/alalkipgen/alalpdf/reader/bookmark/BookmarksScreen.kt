package com.alalkipgen.alalpdf.reader.bookmark

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alalkipgen.alalpdf.data.BookmarkEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    bookmarks: List<BookmarkEntity>,
    onBack: () -> Unit,
    onOpenPage: (Int) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
    onAddCurrentPage: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val visible = remember(bookmarks, query) {
        if (query.isBlank()) bookmarks
        else bookmarks.filter { (it.label ?: "").contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search bookmarks") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Bookmarks")
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddCurrentPage,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add bookmark") },
            )
        },
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Bookmarks") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Outline") })
            }
            if (selectedTab == 1) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Outline is not available yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No bookmarks yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visible, key = { it.id }) { bookmark ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    onDelete(bookmark)
                                    true
                                } else {
                                    false
                                }
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            },
                        ) {
                            BookmarkCard(bookmark) { onOpenPage(bookmark.pageIndex) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun BookmarkCard(bookmark: BookmarkEntity, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Page ${bookmark.pageIndex + 1}", style = MaterialTheme.typography.titleSmall)
                bookmark.label?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                DateUtils.getRelativeTimeSpanString(bookmark.createdAt).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
