package com.vibe.radio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.radio.data.AppSettings
import com.vibe.radio.data.Region
import com.vibe.radio.data.Station

@Composable
fun RadioScreen(vm: PlayerViewModel) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Радио", "Станции мира · качество: ${AppSettings.radioQuality.title}") {
            IconButton(onClick = vm::reloadRadio) { Icon(Icons.Rounded.Refresh, null, tint = Neon.Text) }
        }
        SearchField(vm.radioQuery, "Найти станцию или жанр…", vm::onRadioQuery)

        val regionTabs = AppSettings.regions.mapNotNull { code -> Region.entries.firstOrNull { it.code == code } }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            items(regionTabs) { r ->
                Chip(r.title, active = vm.radioTab == r.code && vm.radioQuery.isBlank(), leading = r.flag) { vm.selectRadioTab(r.code) }
            }
            item {
                Chip("Избранное", active = vm.radioTab == FAV_TAB && vm.radioQuery.isBlank(), leading = "❤️") { vm.selectRadioTab(FAV_TAB) }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                vm.radioLoading && vm.stations.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Neon.Pink)

                vm.stations.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (vm.radioTab == FAV_TAB) "Здесь пока пусто.\nНажмите ♥ у станции, чтобы добавить." else "Станции не загрузились",
                        color = Neon.Text2,
                    )
                    if (vm.radioTab != FAV_TAB) TextButton(onClick = vm::reloadRadio) {
                        Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Повторить")
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(vm.stations, key = { it.id }) { st ->
                        StationRow(
                            st,
                            isCurrent = vm.currentStation?.id == st.id,
                            isPlaying = vm.isPlaying && vm.currentStation?.id == st.id,
                            isFav = vm.isFavorite(st),
                            onClick = { vm.playStation(st) },
                            onFav = { vm.toggleFavorite(st) },
                        )
                    }
                }
            }
            if (vm.radioLoading && vm.stations.isNotEmpty()) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.TopCenter).padding(8.dp).size(24.dp),
                    color = Neon.Cyan, strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun StationRow(
    st: Station, isCurrent: Boolean, isPlaying: Boolean, isFav: Boolean,
    onClick: () -> Unit, onFav: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isCurrent) Brush.linearGradient(listOf(Neon.Violet.copy(alpha = 0.45f), Neon.Pink.copy(alpha = 0.30f)))
                else Neon.cardBrush
            )
            .border(1.dp, if (isCurrent) Neon.Pink.copy(alpha = 0.7f) else Neon.Line, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationLogo(st, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(st.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Neon.Text)
            val rates = st.allVariants.map { it.bitrate }.filter { it > 0 }.distinct().sorted()
            val quality = when {
                rates.size > 1 -> rates.joinToString(" / ") + " kbps"
                st.bitrate > 0 -> "${st.bitrate} kbps"
                else -> null
            }
            val sub = listOfNotNull(st.tags.takeIf { it.isNotBlank() }, quality, st.codec.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, color = Neon.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isPlaying) MiniEq(Modifier.padding(horizontal = 6.dp))
        IconButton(onClick = onFav) {
            Icon(
                if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null,
                tint = if (isFav) Neon.Pink else Neon.Text2,
            )
        }
    }
}
