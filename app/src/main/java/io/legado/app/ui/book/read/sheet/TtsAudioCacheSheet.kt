package io.legado.app.ui.book.read.sheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.TtsAudioCacheModel
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.utils.StringUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TtsAudioCacheSheet(
    onDismissRequest: () -> Unit,
    onStart: (indices: IntArray) -> Unit,
    onCancel: () -> Unit,
) {
    val progress by TtsAudioCacheModel.progressFlow.collectAsState()
    val book = ReadBook.book
    val durIndex = book?.durChapterIndex ?: 0
    val isHttpTts = remember(ReadAloud.ttsEngine) {
        val engine = ReadAloud.ttsEngine
        if (engine.isNullOrBlank() || !StringUtils.isNumeric(engine)) {
            false
        } else {
            ReadAloud.httpTTS != null ||
                    runCatching {
                        appDb.httpTTSDao.get(engine.toLong())
                    }.getOrNull() != null
        }
    }

    var chapters by remember { mutableStateOf<List<BookChapter>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf(durIndex)) }
    val listState = rememberLazyListState()

    LaunchedEffect(book?.bookUrl) {
        val url = book?.bookUrl ?: return@LaunchedEffect
        chapters = withContext(Dispatchers.IO) {
            appDb.bookChapterDao.getChapterList(url)
        }
        if (selected.isEmpty() && chapters.isNotEmpty()) {
            selected = setOf(durIndex.coerceIn(0, chapters.lastIndex))
        }
        val scrollTo = chapters.indexOfFirst { it.index == durIndex }.coerceAtLeast(0)
        if (scrollTo > 0) {
            listState.scrollToItem(scrollTo)
        }
    }

    fun selectRange(from: Int, to: Int) {
        val last = chapters.maxOfOrNull { it.index } ?: 0
        val start = from.coerceIn(0, last)
        val end = to.coerceIn(start, last)
        selected = (start..end).toSet()
    }

    AppModalBottomSheet(
        show = true,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.tts_audio_cache),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (!isHttpTts) {
                Text(
                    text = stringResource(R.string.tts_audio_cache_need_http),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.tts_audio_cache_selected_count,
                        selected.size,
                        chapters.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { selectRange(durIndex, durIndex) },
                        label = { Text(stringResource(R.string.tts_audio_cache_current)) },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val last = chapters.maxOfOrNull { it.index } ?: durIndex
                            selectRange(durIndex, last)
                        },
                        label = { Text(stringResource(R.string.tts_audio_cache_from_current)) },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val last = (durIndex + 9).coerceAtMost(
                                chapters.maxOfOrNull { it.index } ?: durIndex
                            )
                            selectRange(durIndex, last)
                        },
                        label = { Text(stringResource(R.string.tts_audio_cache_next_10)) },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val last = chapters.maxOfOrNull { it.index } ?: 0
                            selectRange(0, last)
                        },
                        label = { Text(stringResource(R.string.tts_audio_cache_entire)) },
                    )
                    FilterChip(
                        selected = false,
                        onClick = { selected = emptySet() },
                        label = { Text(stringResource(R.string.tts_audio_cache_clear_sel)) },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(chapters, key = { it.index }) { chapter ->
                        val checked = chapter.index in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) {
                                        selected - chapter.index
                                    } else {
                                        selected + chapter.index
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    selected = if (on) {
                                        selected + chapter.index
                                    } else {
                                        selected - chapter.index
                                    }
                                },
                            )
                            Text(
                                text = "${chapter.index + 1}. ${chapter.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            if (progress.running || progress.summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress.summary.ifBlank {
                        stringResource(R.string.tts_audio_cache_running)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (progress.running && progress.totalCount > 0) {
                    LinearProgressIndicator(
                        progress = {
                            progress.doneCount.toFloat() / progress.totalCount.toFloat()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (progress.running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (progress.running) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            } else if (isHttpTts) {
                TextButton(
                    onClick = {
                        if (selected.isEmpty()) return@TextButton
                        onStart(selected.sorted().toIntArray())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selected.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.tts_audio_cache_start))
                }
            }
        }
    }
}
