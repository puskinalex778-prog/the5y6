@file:OptIn(ExperimentalLayoutApi::class)

package com.vibe.radio.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.radio.audio.AudioFx
import com.vibe.radio.data.AppSettings
import com.vibe.radio.data.RadioQuality
import com.vibe.radio.data.Region
import com.vibe.radio.data.StartSection
import com.vibe.radio.data.ThemeMode

@Composable
fun SettingsScreen(vm: PlayerViewModel) {
    val actions = rememberLibraryActions(vm)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { ScreenTitle("Настройки", "PLAY pay · версия 2.0") }

        // ---------------- Оформление ----------------
        item { Section("🎨 Оформление") }
        item {
            Card {
                Text("Цвет", color = Neon.Text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Accents.forEachIndexed { i, a ->
                        ColorDot(Brush.linearGradient(listOf(a.deep, a.main)), a.title, AppSettings.accentIndex == i) {
                            AppSettings.update { accentIndex = i }
                        }
                    }
                    ColorDot(
                        Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)),
                        "Свой", AppSettings.accentIndex >= Accents.size,
                    ) { AppSettings.update { accentIndex = Accents.size } }
                }
                if (AppSettings.accentIndex >= Accents.size) {
                    Spacer(Modifier.height(8.dp))
                    Text("Оттенок", color = Neon.Text2, fontSize = 12.sp)
                    Slider(
                        value = AppSettings.customHue,
                        onValueChange = { AppSettings.customHue = it },
                        onValueChangeFinished = { AppSettings.save() },
                        valueRange = 0f..359f,
                        colors = sliderColors(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Тема", color = Neon.Text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        Chip(m.title, active = AppSettings.themeMode == m) { AppSettings.update { themeMode = m } }
                    }
                }
            }
        }
        item {
            Card {
                SwitchRow("Обложка в плеере", "Иначе — танцующие персонажи. Можно переключать касанием в плеере", AppSettings.showCover) {
                    AppSettings.update { showCover = it }
                }
                SwitchRow("Анимации", "Выключите, чтобы экономить батарею", AppSettings.animations) {
                    AppSettings.update { animations = it }
                }
                SwitchRow("Не гасить экран в плеере", "Удобно, когда смотрите на танцы", AppSettings.keepScreenOn) {
                    AppSettings.update { keepScreenOn = it }
                }
            }
        }

        // ---------------- Медиатека ----------------
        item { Section("📂 Медиатека") }
        item {
            Card {
                SwitchRow("Сканировать при запуске", "Находить новые треки автоматически", AppSettings.scanOnStart) {
                    AppSettings.update { scanOnStart = it }
                }
                SwitchRow("Скрывать мессенджеры и записи", "WhatsApp, Telegram, диктофон, рингтоны", AppSettings.hideMessengers) {
                    AppSettings.update { hideMessengers = it }
                }
                Spacer(Modifier.height(6.dp))
                Text("Пропускать файлы короче", color = Neon.Text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Нет", 15 to "15 с", 30 to "30 с", 60 to "1 мин").forEach { (sec, label) ->
                        Chip(label, active = AppSettings.minDurationSec == sec) { AppSettings.update { minDurationSec = sec } }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Свои папки", color = Neon.Text, fontWeight = FontWeight.SemiBold)
                Text(
                    "Если музыка с карты памяти не нашлась при сканировании — добавьте папку вручную",
                    color = Neon.Text2, fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                vm.folders.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, tint = Neon.Cyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(folderLabel(f), color = Neon.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { vm.removeFolder(f) }) { Icon(Icons.Rounded.Close, null, tint = Neon.Text2) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("＋ Папка", active = false, onClick = actions.addFolder)
                    Chip("＋ Файлы", active = false, onClick = actions.addFiles)
                    Chip(if (vm.scanning) "Сканирую…" else "Пересканировать", active = true, onClick = actions.scan)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Вернуть треки, убранные из медиатеки",
                    color = Neon.Cyan, fontSize = 13.sp,
                    modifier = Modifier.clickable { vm.resetHidden() }.padding(vertical = 6.dp),
                )
            }
        }

        // ---------------- Воспроизведение ----------------
        item { Section("▶️ Воспроизведение") }
        item {
            Card {
                SwitchRow("Пауза при отключении наушников", null, AppSettings.pauseOnDisconnect) {
                    AppSettings.update { pauseOnDisconnect = it }
                    vm.onPlaybackSettingsChanged()
                }
                SwitchRow("Пропускать тишину", "Убирает паузы в начале и конце треков", AppSettings.skipSilence) {
                    AppSettings.update { skipSilence = it }
                    vm.onPlaybackSettingsChanged()
                }
                SwitchRow("Продолжать с того же места", "Восстанавливать очередь после перезапуска", AppSettings.restoreQueue) {
                    AppSettings.update { restoreQueue = it }
                }
                Spacer(Modifier.height(6.dp))
                Text("Открывать при запуске", color = Neon.Text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StartSection.entries.forEach { s ->
                        Chip(s.title, active = AppSettings.startSection == s) { AppSettings.update { startSection = s } }
                    }
                }
            }
        }

        // ---------------- Эквалайзер ----------------
        item { Section("🎚️ Эквалайзер") }
        item { EqualizerCard(vm) }

        // ---------------- Радио ----------------
        item { Section("📻 Радио") }
        item {
            Card {
                Text("Качество (битрейт)", color = Neon.Text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioQuality.entries.forEach { q ->
                        Chip(q.title, active = AppSettings.radioQuality == q) {
                            AppSettings.update { radioQuality = q }
                            vm.onRadioSettingsChanged()
                        }
                    }
                }
                Text(AppSettings.radioQuality.hint, color = Neon.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "Если у станции несколько потоков, качество можно сменить прямо в плеере",
                    color = Neon.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                SwitchRow("Экономить мобильный трафик", "В мобильной сети — не выше 64 kbps", AppSettings.saveDataOnMobile) {
                    AppSettings.update { saveDataOnMobile = it }
                    vm.onRadioSettingsChanged()
                }
                SwitchRow("Переключать недоступную станцию", "Автоматически включать следующую", AppSettings.autoNextStation) {
                    AppSettings.update { autoNextStation = it }
                }
                Spacer(Modifier.height(6.dp))
                Text("Страны во вкладках", color = Neon.Text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Region.entries.forEach { r ->
                        val on = r.code in AppSettings.regions
                        Chip(r.title, active = on, leading = r.flag) {
                            AppSettings.update {
                                regions = if (on) regions - r.code else regions + r.code
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "PLAY pay — музыкальный плеер с радио, эффектами, танцами и мини-играми",
                color = Neon.Text2, fontSize = 12.sp,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

private fun folderLabel(uri: String): String {
    val decoded = Uri.decode(uri)
    val part = decoded.substringAfterLast("/tree/").substringBefore("/document/")
    val volume = part.substringBefore(':')
    val path = part.substringAfter(':', "")
    val where = if (volume == "primary") "Телефон" else "SD-карта"
    return if (path.isBlank()) where else "$where / $path"
}

@Composable
private fun EqualizerCard(vm: PlayerViewModel) {
    val info = AudioFx.eqInfo
    Card {
        SwitchRow("Включить эквалайзер", null, AppSettings.eqEnabled) {
            AppSettings.update { eqEnabled = it }
            vm.onPlaybackSettingsChanged()
        }
        if (info == null) {
            Text(
                "Эквалайзер станет доступен после первого запуска воспроизведения (зависит от телефона)",
                color = Neon.Text2, fontSize = 12.sp,
            )
            return@Card
        }
        var levels by remember { mutableStateOf(AudioFx.currentLevels()) }
        if (info.presets.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Пресеты", color = Neon.Text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Свой", active = AppSettings.eqPreset < 0) {
                    AppSettings.update { eqPreset = -1 }
                    vm.onPlaybackSettingsChanged()
                    levels = AudioFx.currentLevels()
                }
                info.presets.forEachIndexed { i, name ->
                    Chip(name, active = AppSettings.eqPreset == i) {
                        AppSettings.update { eqPreset = i; eqEnabled = true }
                        vm.onPlaybackSettingsChanged()
                        levels = AudioFx.currentLevels()
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        for (b in 0 until info.bands) {
            val f = info.freqsHz.getOrElse(b) { 0 }
            val label = if (f >= 1000) "${f / 1000} кГц" else "$f Гц"
            val v = levels.getOrElse(b) { 0 }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Neon.Text2, fontSize = 12.sp, modifier = Modifier.width(58.dp))
                Slider(
                    value = v.toFloat(),
                    onValueChange = { nv ->
                        val list = levels.toMutableList()
                        while (list.size < info.bands) list.add(0)
                        list[b] = nv.toInt()
                        levels = list
                    },
                    onValueChangeFinished = {
                        AppSettings.update {
                            eqPreset = -1
                            eqEnabled = true
                            eqBands = levels.joinToString(",")
                        }
                        vm.onPlaybackSettingsChanged()
                    },
                    valueRange = info.minLevel.toFloat()..info.maxLevel.toFloat(),
                    colors = sliderColors(),
                    modifier = Modifier.weight(1f),
                )
                Text("${v / 100} дБ", color = Neon.Text2, fontSize = 12.sp, modifier = Modifier.width(44.dp))
            }
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Neon.Pink,
    activeTrackColor = Neon.Pink,
    inactiveTrackColor = Neon.Line,
)

@Composable
private fun Section(title: String) {
    Text(
        title, color = Neon.Text2, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Neon.Card)
            .border(1.dp, Neon.Line, RoundedCornerShape(20.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Neon.Text, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Neon.Text2, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Neon.OnAccent,
                checkedTrackColor = Neon.Pink,
                uncheckedTrackColor = Neon.Card,
                uncheckedBorderColor = Neon.Line,
                uncheckedThumbColor = Neon.Text2,
            ),
        )
    }
}

@Composable
private fun ColorDot(brush: Brush, title: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(brush)
                .border(if (selected) 3.dp else 1.dp, if (selected) Neon.Text else Neon.Line, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White)
        }
        Text(title, color = Neon.Text2, fontSize = 10.sp, maxLines = 1)
    }
}
