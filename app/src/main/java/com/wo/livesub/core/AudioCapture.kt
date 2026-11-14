package com.wo.livesub.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log

/**
 * 系统声音采集：MediaProjection + AudioPlaybackCapture（Android 10 / API 29+）。
 *
 * 官方硬约束（AudioPlaybackCaptureConfiguration）：
 * - 被抓 App 的 usage 必须是 USAGE_UNKNOWN / USAGE_GAME / USAGE_MEDIA，其他 usage 抓不到；
 * - 被抓 App 的 capture policy 取 AudioManager 与 AudioAttributes 里最严格的一个，
 *   必须 ALLOW_CAPTURE_BY_ALL；allowAudioPlaybackCapture=false / ALLOW_CAPTURE_BY_NONE 一票否决；
 * - 必须同一 user profile；必须由用户先授权 createScreenCaptureIntent()。
 * 因此「浮层在但没有字幕」是合法结果，不是 bug，也不做合规绕过。
 */
class AudioCapture private constructor(
    private val record: AudioRecord,
    private val projection: MediaProjection
) {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "LiveSubAudio"

        fun create(projection: MediaProjection): AudioCapture? {
            return try {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .build()

                val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
                if (minBytes <= 0) return null
                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL)
                            .build()
                    )
                    .setBufferSizeInBytes(minBytes * 2)
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return null
                }
                AudioCapture(record, projection)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 用户撤回投屏授权：音频会变静音，交给 Service 收尾。
            try { record.stop() } catch (_: Throwable) {}
        }
    }

    init {
        // 必须注册 Callback；忘记注销会在第二次授权时抛异常。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.registerCallback(projectionCallback, null)
        }
    }

    fun startReading(onSamples: (FloatArray) -> Unit) {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "startRecording failed state=${record.recordingState}")
            return
        }
        val buffer = ShortArray(SAMPLE_RATE / 10) // 100ms
        var errorStreak = 0
        while (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val n = record.read(buffer, 0, buffer.size)
            if (n > 0) {
                errorStreak = 0
                val floats = FloatArray(n) { buffer[it] / 32768.0f }
                onSamples(floats)
                continue
            }
            if (n == 0) continue
            // 负数是 AudioRecord 错误码（DEAD_OBJECT / INVALID_OPERATION 等）。
            // 旧写法对 n<=0 一律 continue，采集失效后会在这里空转烧 CPU；连续出错直接退出。
            errorStreak++
            Log.w(TAG, "read error=$n streak=$errorStreak")
            if (errorStreak >= 5) break
        }
    }

    fun stop() {
        try { record.stop() } catch (_: Throwable) {}
    }

    fun close() {
        stop()
        try { projection.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { record.release() } catch (_: Throwable) {}
        // MediaProjection 由 Service 统一 stop()，此处不重复 stop。
    }
}
