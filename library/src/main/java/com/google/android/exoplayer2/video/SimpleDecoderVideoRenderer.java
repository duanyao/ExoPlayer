/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.video;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;

import com.google.android.exoplayer2.BaseRenderer;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.audio.AudioDecoderException;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.SimpleDecoder;
import com.google.android.exoplayer2.decoder.SimpleOutputBuffer;
import com.google.android.exoplayer2.util.TraceUtil;
import com.google.android.exoplayer2.video.VideoRendererEventListener.EventDispatcher;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Decodes and renders audio using a {@link SimpleDecoder}.
 */
public abstract class SimpleDecoderVideoRenderer extends BaseRenderer {

    private final EventDispatcher eventDispatcher;
    private final FormatHolder formatHolder;

    private DecoderCounters codecCounters;
    private Format inputFormat;
    private SimpleDecoder<DecoderInputBuffer, ? extends SimpleOutputBuffer,
            ? extends AudioDecoderException> decoder;
    private PriorityQueue<SimpleOutputBuffer> outputBufferQueue;
    private Bitmap bitmap;
    private Surface surface;

    private long lastInputTime;
    private long currentPositionUs;
    private long previusPositionUs;
    private boolean allowPositionDiscontinuity;
    private boolean inputStreamEnded;
    private boolean outputStreamEnded;
    private boolean renderedFirstFrame;
    private boolean drawnToSurface;
    long startTime;

    public SimpleDecoderVideoRenderer() {
        this(null, null);
    }

    /**
     * @param eventHandler  A handler to use when delivering events to {@code eventListener}. May be
     *                      null if delivery of events is not required.
     * @param eventListener A listener of events. May be null if delivery of events is not required.
     */
    public SimpleDecoderVideoRenderer(Handler eventHandler,
                                     VideoRendererEventListener eventListener) {
        super(C.TRACK_TYPE_VIDEO);
        eventDispatcher = new EventDispatcher(eventHandler, eventListener);
        formatHolder = new FormatHolder();
        outputBufferQueue = new PriorityQueue<SimpleOutputBuffer>(16, new Comparator<SimpleOutputBuffer>() {
            @Override
            public int compare(SimpleOutputBuffer lhs, SimpleOutputBuffer rhs) {
                return (int)(lhs.timeUs - rhs.timeUs);
            }
        });
        startTime = System.currentTimeMillis();
        System.out.println(">>>>SimpleDecoderVideoRenderer<init>,t=" + startTime);
    }

    // similar to: MediaCodecTrackRenderer.render()
    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
        if (isEnded()) {
            return;
        }

        // Try and read a format if we don't have one already.
        if (inputFormat == null && !readFormat()) {
            // We can't make progress without one.
            return;
        }

        // If we don't have a decoder yet, we need to instantiate one.
        maybeInitCodec();

        // Rendering loop.
        try {
            TraceUtil.beginSection("drainAndFeed");
            //System.out.println(">>>before dummy input");
            DecoderInputBuffer input = decoder.dequeueInputBuffer();
            if (input != null) {
                input.clear();
                input.data.limit(0);
                //System.out.println(">>>queue dummy input");
                decoder.queueInputBuffer(input);
            }
            while (drainOutputBuffer(positionUs, elapsedRealtimeUs)) {
            }
            while (feedInputBuffer()) {
            }
            TraceUtil.endSection();
        } catch (AudioDecoderException e) {
            throw ExoPlaybackException.createForRenderer(e, getIndex());
        }
        codecCounters.ensureUpdated();
        //System.out.println(">>>>Leave render:ts=" + positionUs + ",dt=" + (System.currentTimeMillis() - startTime));
    }

    protected abstract SimpleDecoder<DecoderInputBuffer, ? extends SimpleOutputBuffer,
            ? extends AudioDecoderException> createDecoder(Format format) throws AudioDecoderException;


    /**
     * @param surface The surface to set.
     * @throws ExoPlaybackException
     */
    private void setSurface(Surface surface) throws ExoPlaybackException {
        if (this.surface == surface) {
            return;
        }
        this.surface = surface;
        //this.reportedDrawnToSurface = false;
        int state = getState();
        if (state == Renderer.STATE_ENABLED || state == Renderer.STATE_STARTED) {
            releaseCodec();
            maybeInitCodec();
        }
        drawnToSurface = false;
    }

    private void releaseCodec() {
        try {
            if (decoder != null) {
                decoder.release();
                decoder = null;
                codecCounters.decoderReleaseCount++;
            }
        } finally {
            codecCounters.ensureUpdated();
            eventDispatcher.disabled(codecCounters);
        }
    }

    private void maybeInitCodec() throws ExoPlaybackException {
        if (decoder == null) {
            try {
                long codecInitializingTimestamp = SystemClock.elapsedRealtime();
                TraceUtil.beginSection("createVideoDecoder");
                decoder = createDecoder(inputFormat);
                TraceUtil.endSection();
                long codecInitializedTimestamp = SystemClock.elapsedRealtime();
                eventDispatcher.decoderInitialized(decoder.getName(), codecInitializedTimestamp,
                        codecInitializedTimestamp - codecInitializingTimestamp);
                codecCounters.decoderInitCount++;
            } catch (AudioDecoderException e) {
                throw ExoPlaybackException.createForRenderer(e, getIndex());
            }
        }
    }

    // similar to: MediaCodecTrackRenderer.drainOutputBuffer() and AudioDecoderTrackRenderer.drainOutputBuffer()
    private boolean drainOutputBuffer(long positionUs, long elapsedRealtimeUs) throws AudioDecoderException {
        if (isEnded()) {
            return false;
        }

        SimpleOutputBuffer buffer;

        while ((buffer = decoder.dequeueOutputBuffer()) != null) {
            if (buffer.isEndOfStream()) {
                System.out.println(">>>>drainOutputBuffer:output eos:" + buffer.timeUs);
                outputStreamEnded = true;
                buffer.release();
            } else if (buffer.data.remaining() != 0) {
                outputBufferQueue.add(buffer);
                System.out.println(">>>>drainOutputBuffer:add to q, ts=" + buffer.timeUs);
                codecCounters.skippedOutputBufferCount += buffer.skippedOutputBufferCount;
            } else {
                buffer.release();
            }
        }

        buffer = outputBufferQueue.peek();
        if (buffer == null) {
            return false;
        }

        // Compute how many microseconds it is until the buffer's presentation time.
        long elapsedSinceStartOfLoopUs = (SystemClock.elapsedRealtime() * 1000) - elapsedRealtimeUs;
        long earlyUs = buffer.timeUs - positionUs - elapsedSinceStartOfLoopUs;
//        System.out.println(">>>>drainOutputBuffer:ts=" + buffer.timeUs + ", earlyUs="
//                + earlyUs + ", positionUs=" + positionUs + ", elapsedSinceStartOfLoopUs=" + elapsedSinceStartOfLoopUs
//                + ", dt=" + (System.currentTimeMillis() -startTime)
//                + ", oq size=" + outputBufferQueue.size());
        if (earlyUs < 30000 || !renderedFirstFrame) {
            outputBufferQueue.poll();
            if (earlyUs < - 30000 && renderedFirstFrame) {
                buffer.release();
                codecCounters.skippedOutputBufferCount++;
                System.out.println(">>>>drainOutputBuffer:skipped, earlyUs=" + earlyUs + ", dt=");
                return true;
            }
        } else {
            return false;
        }

        if (getState() == Renderer.STATE_STARTED) {
            renderBuffer(buffer, earlyUs / 1000);
        } else {
            System.out.println(">>>>drainOutputBuffer:skipped, state=" + getState() + ", ts=" + buffer.timeUs);
            buffer.release();
        }
        return true;
    }

    private void renderBuffer(SimpleOutputBuffer buffer, long earlyMs) {
        long t0 = SystemClock.elapsedRealtime();
        if (surface != null) {
            codecCounters.renderedOutputBufferCount++;
            if (bitmap == null || bitmap.getWidth() != buffer.width
                    || bitmap.getHeight() != buffer.height) {
                bitmap = Bitmap.createBitmap(buffer.width, buffer.height, buffer.pixelFormat);
            }
            bitmap.copyPixelsFromBuffer(buffer.data);
            Canvas canvas = surface.lockCanvas(null);
            int cw = canvas.getWidth(), ch = canvas.getHeight(), bw = bitmap.getWidth(), bh = bitmap.getHeight();
            float scale = Math.min(cw / (float) bw, ch / (float) bh);
            float tx = (cw - bw * scale) / 2, ty = (ch - scale * bh) / 2;
            canvas.scale(scale, scale);
            canvas.translate(tx, ty);

            long remaining = earlyMs - (SystemClock.elapsedRealtime() - t0);
            if (renderedFirstFrame && remaining > 11) {
                try {
                    Thread.sleep(remaining - 10);
                } catch (InterruptedException e) { }
            }

            canvas.drawBitmap(bitmap, 0, 0, null);
            surface.unlockCanvasAndPost(canvas);

            System.out.println(">>>>renderBuffer:n=" + codecCounters.renderedOutputBufferCount
                + ",timestamp=" + buffer.timeUs + ",remaining(ms)=" + remaining
                + ",dt=" + (System.currentTimeMillis() -startTime));
            if (previusPositionUs > buffer.timeUs) {
                System.err.println(">>>>renderBuffer:reversed timestamp");
            }
            previusPositionUs = buffer.timeUs;

            if (!drawnToSurface) {
                drawnToSurface = true;
                eventDispatcher.drawnToSurface(surface);
            }
        } else {
            codecCounters.skippedOutputBufferCount++;
        }
        renderedFirstFrame = true;
        buffer.release();
    }

    // similar to: AudioDecoderTrackRenderer.feedInputBuffer()
    private boolean feedInputBuffer() throws AudioDecoderException {
        if (inputStreamEnded) {
            return false;
        }

        DecoderInputBuffer inputBuffer = decoder.dequeueInputBuffer();
        if (inputBuffer == null) {
            return false;
        }

        int result = readSource(formatHolder, inputBuffer);
        inputStreamEnded = inputBuffer.isEndOfStream();
        inputBuffer.flip();
        decoder.queueInputBuffer(inputBuffer);

        if (result == C.RESULT_NOTHING_READ) {
            return false;
        } else if (result == C.RESULT_FORMAT_READ) {
            onInputFormatChanged(formatHolder.format);
            return true;
        } else if (inputStreamEnded) {
            return false;
        } else {
            lastInputTime = SystemClock.elapsedRealtime();
            return true;
        }
    }

    private void flushDecoder() {
        SimpleOutputBuffer output;
        while((output = outputBufferQueue.poll()) != null) {
            output.release();
        }
        decoder.flush();
    }

    @Override
    public boolean isEnded() {
        return outputStreamEnded && outputBufferQueue.size() == 0;
    }

    @Override
    public boolean isReady() {
        return inputFormat != null && (isSourceReady() || outputBufferQueue.size() > 0
            || queuedInputRecently());
    }

    @Override
    protected void onEnabled(boolean joining) throws ExoPlaybackException {
        codecCounters = new DecoderCounters();
        eventDispatcher.enabled(codecCounters);
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining) {
        currentPositionUs = positionUs;
        allowPositionDiscontinuity = true;
        inputStreamEnded = false;
        outputStreamEnded = false;
        renderedFirstFrame = false;
        if (decoder != null) {
            flushDecoder();
        }
    }

    @Override
    protected void onDisabled() {
        inputFormat = null;
        outputBufferQueue.clear();
        releaseCodec();
    }

    private boolean readFormat() {
        int result = readSource(formatHolder, null);
        if (result == C.RESULT_FORMAT_READ) {
            onInputFormatChanged(formatHolder.format);
            return true;
        }
        return false;
    }

    private void onInputFormatChanged(Format newFormat) {
        inputFormat = newFormat;
        eventDispatcher.inputFormatChanged(newFormat);
    }

    private boolean queuedInputRecently() {
        return SystemClock.elapsedRealtime() - lastInputTime < 500;
    }

    @Override
    public void handleMessage(int messageType, Object message) throws ExoPlaybackException {
        if (messageType == C.MSG_SET_SURFACE) {
            setSurface((Surface) message);
        } else {
            super.handleMessage(messageType, message);
        }
    }

}
