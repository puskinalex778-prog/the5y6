package com.vibe.radio.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vibe.radio.data.Station
import com.vibe.radio.data.Track

@Composable
fun ScreenTitle(title: String, subtitle: String, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(
                title,
                style = TextStyle(
                    brush = Brush.linearGradient(listOf(Neon.Cyan, Neon.Pink)),
                    fontSize = 30.sp, fontWeight = FontWeight.Black,
                ),
            )
            Text(subtitle, color = Neon.Text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
fun SearchField(query: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        placeholder = { Text(hint, color = Neon.Text2) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Neon.Text2) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, null, tint = Neon.Text2) }
        },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Neon.Pink,
            unfocusedBorderColor = Neon.Line,
            focusedContainerColor = Neon.Card,
            unfocusedContainerColor = Neon.Card,
            focusedTextColor = Neon.Text,
            unfocusedTextColor = Neon.Text,
            cursorColor = Neon.Pink,
        ),
    )
}

@Composable
fun Chip(text: String, active: Boolean, modifier: Modifier = Modifier, leading: String? = null, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Neon.accent else Neon.cardBrush)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Text(leading, fontSize = 15.sp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) Neon.OnAccent else Neon.Text,
            fontSize = 14.sp,
        )
    }
}

@Composable
fun TrackArtImage(track: Track?, size: Dp, corner: Dp = 12.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(Neon.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = Neon.OnAccent.copy(alpha = 0.85f), modifier = Modifier.size(size * 0.45f))
        if (track != null && track.uri.isNotBlank()) {
            AsyncImage(
                model = TrackArt(track.uri, track.fromMediaStore, track.albumId),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun StationLogo(st: Station, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Neon.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(st.name.take(1).uppercase(), fontWeight = FontWeight.Black, fontSize = (size.value * 0.42f).sp, color = Neon.OnAccent)
        if (st.favicon.isNotBlank()) {
            AsyncImage(
                model = st.favicon, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun MiniEq(modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "eq")
    Row(modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(380, 520, 300, 450).forEachIndexed { i, ms ->
            val h by tr.animateFloat(0.2f, 1f, infiniteRepeatable(tween(ms), RepeatMode.Reverse), label = "b$i")
            Box(Modifier.width(3.dp).height((18 * h).dp).clip(RoundedCornerShape(2.dp)).background(Neon.Cyan))
        }
    }
}

@Composable
fun PlayButton(vm: PlayerViewModel, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Neon.accent).clickable { vm.togglePlay() },
        contentAlignment = Alignment.Center,
    ) {
        if (vm.isBuffering) CircularProgressIndicator(Modifier.size(size * 0.55f), color = Neon.OnAccent, strokeWidth = 3.dp)
        else Icon(
            if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null,
            tint = Neon.OnAccent, modifier = Modifier.size(size * 0.55f),
        )
    }
}

@Composable
fun EmptyHint(emoji: String, text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, fontSize = 48.sp)
        Spacer(Modifier.height(10.dp))
        Text(text, color = Neon.Text2, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

