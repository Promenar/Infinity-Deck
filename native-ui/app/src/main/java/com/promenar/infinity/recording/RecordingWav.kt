package com.promenar.infinity.recording

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun updateRecordingWavHeader(file: RandomAccessFile, size: Long) {
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()).putInt((size + 36).toInt()).put("WAVEfmt ".toByteArray())
    header.putInt(16).putShort(1).putShort(1).putInt(RecordingCapturePolicy.SAMPLE_RATE)
    header.putInt(RecordingCapturePolicy.SAMPLE_RATE * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(size.toInt())
    file.seek(0)
    file.write(header.array())
    file.seek(44 + size)
}
