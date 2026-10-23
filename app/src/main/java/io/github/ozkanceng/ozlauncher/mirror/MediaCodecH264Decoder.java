package io.github.ozkanceng.ozlauncher.mirror;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * High-performance hardware-accelerated H.264 video decoder.
 * Uses MediaCodec to render directly to a Surface with minimal latency and 60 FPS smoothness.
 */
public final class MediaCodecH264Decoder {
    private static final String TAG = "MediaCodecH264Decoder";
    private static final String MIME_TYPE = "video/avc";

    private MediaCodec codec;
    private Surface surface;
    private boolean configured;
    private int width = 1920;
    private int height = 1080;

    public synchronized void init(Surface surface, int width, int height) {
        this.surface = surface;
        this.width = width > 0 ? width : 1920;
        this.height = height > 0 ? height : 1080;
        stop();
        try {
            codec = MediaCodec.createDecoderByType(MIME_TYPE);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, this.width, this.height);
            // Low-latency configuration
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            codec.configure(format, surface, null, 0);
            codec.start();
            configured = true;
            Log.i(TAG, "MediaCodec AVC decoder initialized at " + this.width + "x" + this.height);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to initialize MediaCodec", e);
            stop();
        }
    }

    public synchronized void decode(byte[] data, int offset, int length, long presentationTimeUs) {
        if (!configured || codec == null) return;
        try {
            int inputIndex = codec.dequeueInputBuffer(10_000); // 10ms timeout
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data, offset, length);
                    codec.queueInputBuffer(inputIndex, 0, length, presentationTimeUs, 0);
                }
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0);
            while (outputIndex >= 0) {
                // Render directly to surface
                codec.releaseOutputBuffer(outputIndex, true);
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0);
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "Decode state error: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (codec != null) {
            try {
                if (configured) {
                    codec.stop();
                }
                codec.release();
            } catch (Exception ignored) { }
            codec = null;
        }
        configured = false;
    }
}
