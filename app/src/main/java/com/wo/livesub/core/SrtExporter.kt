package com.wo.livesub.core

/**
 * 极简 SRT 导出。时间戳是「相对本次采集会话」的偏移，不是墙钟时间，
 * 也不是端到端字幕延迟 —— 只保证格式可被播放器读取。
 */
object SrtExporter {

    fun export(lines: List<SubtitleLine>, displayMode: DisplayMode): String = buildString {
        // 序号按导出顺序连续编号：line.index 是会话内自增 id，补译/过载跳号时直接 +1 会出现空号。
        lines.forEachIndexed { position, line ->
            append(position + 1)
            append('\n')
            append(formatTime(line.startMs))
            append(" --> ")
            append(formatTime(line.endMs))
            append('\n')
            append(renderLine(line, displayMode))
            append("\n\n")
        }
    }

    private fun formatTime(msRaw: Long): String {
        val ms = msRaw.coerceAtLeast(0L)
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1_000
        val millis = ms % 1_000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}
