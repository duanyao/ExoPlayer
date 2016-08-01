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
package com.google.android.exoplayer2.ext.ffmpeg;


import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.SimpleDecoder;
import com.google.android.exoplayer2.decoder.SimpleOutputBuffer;
import com.google.android.exoplayer2.util.MimeTypes;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * JNI wrapper for FFmpeg. Only audio decoding is supported.
 */
/* package */ final class FfmpegDecoder extends
    SimpleDecoder<DecoderInputBuffer, SimpleOutputBuffer, FfmpegDecoderException> {

  private static final String TAG = "FfmpegDecoder";

  /**
   * End of file. See AVERROR_EOF in ffmpeg/libavutil/error.h
   */
  private static final int AVERROR_EOF = makeErrorNumber('E', 'O', 'F', ' ');

  /**
   * Whether the underlying FFmpeg library is available.
   */
  public static final boolean IS_AVAILABLE;
  static {
    boolean isAvailable;
    try {
      System.loadLibrary("ffmpeg");
      isAvailable = true;
    } catch (UnsatisfiedLinkError exception) {
      isAvailable = false;
    }
    IS_AVAILABLE = isAvailable;
  }

  /**
   * Port of FFERRTAG() in ffmpeg/libavutil/error.h
   */
  private static int makeErrorNumber(char a, char b, char c, char d) {
    return  -((a & 0xFF) | ((b & 0xFF) << 8) | ((c & 0xFF) << 16) | ((d & 0xFF) << 24));
  }

  /**
   * Returns whether this decoder can decode samples in the specified MIME type.
   */
  public static boolean supportsFormat(String mimeType) {
    String codecName = getCodecName(mimeType);
    return codecName != null && nativeHasDecoder(codecName);
  }

  // Space for 64 ms of 6 channel 48 kHz 16-bit PCM audio.
  private static final int AUDIO_OUTPUT_BUFFER_SIZE = 1536 * 6 * 2 * 2;
  // for pointer to AVFrame
  private static final int VIDEO_OUTPUT_BUFFER_SIZE = 8;
  private final String codecName;
  private final boolean isVideo;
  private final boolean isAudio;
  private final byte[] extraData;
  private long nativeContext; // May be reassigned on resetting the codec.
  private boolean hasOutputFormat;
  private volatile int channelCount;
  private volatile int sampleRate;
  private int width;
  private int height;
  private int scaledWidth;
  private int scaledHeight;
  private int scaledSize;
  private long avFrame;
  private long swsContext;
  private ByteBuffer scaledFrame;
  private boolean isDecodeOnly;

  public FfmpegDecoder(int numInputBuffers, int numOutputBuffers, int initialInputBufferSize,
      String mimeType, List<byte[]> initializationData) throws FfmpegDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new SimpleOutputBuffer[numOutputBuffers]);
    codecName = getCodecName(mimeType);
    isAudio = MimeTypes.isAudio(mimeType);
    isVideo = MimeTypes.isVideo(mimeType);
    extraData = getExtraData(mimeType, initializationData);
    nativeContext = nativeInitialize(codecName, extraData);
    if (nativeContext == 0) {
      throw new FfmpegDecoderException("Initialization failed.");
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  @Override
  public String getName() {
    return "ffmpeg" + nativeGetFfmpegVersion() + "-" + codecName;
  }

  @Override
  public DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
  }

  @Override
  public SimpleOutputBuffer createOutputBuffer() {
    return new SimpleOutputBuffer(this);
  }

  @Override
  public FfmpegDecoderException decode(DecoderInputBuffer inputBuffer,
      SimpleOutputBuffer outputBuffer, boolean reset) {
    //System.out.println(">>>>FfmpegDecoder:decode:input:(size,ts):\t" + inputBuffer.data.limit() + "\t" + inputBuffer.timeUs);
    outputBuffer.clear();
    outputBuffer.timeUs = Long.MIN_VALUE;
    if (outputBuffer.data != null) {
      outputBuffer.data.limit(0);
    }

    if (reset) {
      isDecodeOnly = false;
      nativeContext = nativeReset(nativeContext, extraData);
      if (nativeContext == 0) {
        return new FfmpegDecoderException("Error resetting (see logcat).");
      }
    }

    isDecodeOnly = inputBuffer.isDecodeOnly();
    ByteBuffer inputData = inputBuffer.data;
    int inputSize = inputData.limit();

    if (inputBuffer.isEndOfStream()) {
      inputData.limit(0);
      inputSize = 0;
      System.out.println(">>>>FfmpegDecoder:decode:input EOS: input ts=" + inputBuffer.timeUs);
    }

    ByteBuffer outputData = outputBuffer.init(Long.MIN_VALUE, isAudio? AUDIO_OUTPUT_BUFFER_SIZE : VIDEO_OUTPUT_BUFFER_SIZE);
    int result = nativeDecode(nativeContext, inputData, inputSize, inputBuffer.timeUs, inputBuffer.isEndOfStream(), outputData, outputData.limit());
    if (result < 0) {
      outputData.limit(0);
      if (result == AVERROR_EOF) {
        outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
      } else {
        return new FfmpegDecoderException("Error decoding (see logcat). Code: " + result);
      }
    } else {
      outputData.position(0);
      outputData.limit(result);
    }

    if (isAudio && !hasOutputFormat) {
        channelCount = nativeGetChannelCount(nativeContext);
        sampleRate = nativeGetSampleRate(nativeContext);
        hasOutputFormat = true;
    }

    if (isVideo) {
      if (result == VIDEO_OUTPUT_BUFFER_SIZE) {
        System.out.println(">>>>FfmpegDecoder:got video frame");
        outputData.order(ByteOrder.nativeOrder());
        avFrame = outputData.getLong();
        outputBuffer.timeUs = nativeGetPresentationTime(avFrame);
        maybeUpdateDimension();
        if (outputData.capacity() >= scaledSize) {
          scaledFrame = outputData;
        } else {
          scaledFrame = ByteBuffer.allocateDirect(scaledSize);
        }
        int scaledLineSize = scaledWidth * 2; //RGB565
        int scaleResult = nativeScaleFrame(swsContext, avFrame, scaledFrame, scaledLineSize);
        nativeFreeFrame(avFrame);
        avFrame = 0;
        if (scaleResult != scaledHeight) {
          System.out.println(">>>>nativeScaleFrame failed:" + scaleResult);
          outputData.limit(0);
        } else {
          scaledFrame.position(0);
          scaledFrame.limit(scaledSize);
          outputBuffer.data = scaledFrame;
          outputBuffer.width = scaledWidth;
          outputBuffer.height = scaledHeight;
          outputBuffer.pixelFormat = Bitmap.Config.RGB_565;
        }
      } else {
        System.out.println(">>>>FfmpegDecoder:not got video frame");
        outputData.limit(0);
      }
    } else if (inputSize > 0) {
        outputBuffer.timeUs = inputBuffer.timeUs;
    }

    if (outputBuffer.isEndOfStream()) {
      System.out.println(">>>>FfmpegDecoder:decode:out eos=" + outputBuffer.timeUs);
    }
    if (isDecodeOnly) {
      outputBuffer.setFlags(C.BUFFER_FLAG_DECODE_ONLY);
    }
    return null;
  }

  private void maybeUpdateDimension() {
    int w = nativeGetWidth(avFrame), h = nativeGetHeight(avFrame);
    if (!hasOutputFormat) {
      hasOutputFormat = true;
      System.out.println(">>>>FfmpegDecoder:got video dim:width=" + w + ",height=" + h);
    }

    if (w != width || h != height) {
      width = w;
      height = h;
      scaledWidth = w;
      scaledHeight = h;
      scaledSize = 2 * scaledWidth * scaledHeight; // RGB565, 16bits/pixel
      nativeFreeSws(swsContext);
      swsContext = nativeCreateSws(avFrame, scaledWidth, scaledHeight);
      if (swsContext == 0) throw new OutOfMemoryError();
    }
  }

  @Override
  public void release() {
    super.release();
    nativeRelease(nativeContext);
    nativeContext = 0;
    nativeFreeSws(swsContext);
    swsContext = 0;
    nativeFreeFrame(avFrame);
    avFrame = 0;
  }

  /**
   * Returns the channel count of output audio. May only be called after {@link #decode}.
   */
  public int getChannelCount() {
    return channelCount;
  }

  /**
   * Returns the sample rate of output audio. May only be called after {@link #decode}.
   */
  public int getSampleRate() {
    return sampleRate;
  }

  /**
   * Returns FFmpeg-compatible codec-specific initialization data ("extra data"), or {@code null} if
   * not required.
   */
  private static byte[] getExtraData(String mimeType, List<byte[]> initializationData) {
    byte[] extraData;
    switch (mimeType) {
      case MimeTypes.AUDIO_AAC:
      case MimeTypes.AUDIO_OPUS:
        return initializationData.get(0);
      case MimeTypes.AUDIO_VORBIS:
        byte[] header0 = initializationData.get(0);
        byte[] header1 = initializationData.get(1);
        extraData = new byte[header0.length + header1.length + 6];
        extraData[0] = (byte) (header0.length >> 8);
        extraData[1] = (byte) (header0.length & 0xFF);
        System.arraycopy(header0, 0, extraData, 2, header0.length);
        extraData[header0.length + 2] = 0;
        extraData[header0.length + 3] = 0;
        extraData[header0.length + 4] =  (byte) (header1.length >> 8);
        extraData[header0.length + 5] = (byte) (header1.length & 0xFF);
        System.arraycopy(header1, 0, extraData, header0.length + 6, header1.length);
        return extraData;
      case MimeTypes.VIDEO_H264:
        if (initializationData == null || initializationData.size() == 0) return null;
        byte[] a1 = initializationData.get(0), a2 = initializationData.get(1);
        extraData = new byte[a1.length + a2.length];
        System.arraycopy(a1, 0, extraData, 0, a1.length);
        System.arraycopy(a2, 0, extraData, a1.length, a2.length);
        return extraData;
      default:
        // Other codecs do not require extra data.
        return null;
    }
  }

  /**
   * Returns the name of the FFmpeg decoder that could be used to decode {@code mimeType}. The codec
   * can only be used if {@link #nativeHasDecoder(String)} returns true for the returned codec name.
   */
  private static String getCodecName(String mimeType) {
    switch (mimeType) {
      case MimeTypes.AUDIO_AAC:
        return "aac";
      case MimeTypes.AUDIO_MPEG:
      case MimeTypes.AUDIO_MPEG_L1:
      case MimeTypes.AUDIO_MPEG_L2:
        return "mp3";
      case MimeTypes.AUDIO_AC3:
        return "ac3";
      case MimeTypes.AUDIO_E_AC3:
        return "eac3";
      case MimeTypes.AUDIO_TRUEHD:
        return "truehd";
      case MimeTypes.AUDIO_DTS:
      case MimeTypes.AUDIO_DTS_HD:
        return "dca";
      case MimeTypes.AUDIO_VORBIS:
        return "vorbis";
      case MimeTypes.AUDIO_OPUS:
        return "opus";
      case MimeTypes.AUDIO_AMR_NB:
        return "amrnb";
      case MimeTypes.AUDIO_AMR_WB:
        return "amrwb";
      case MimeTypes.AUDIO_FLAC:
        return "flac";
      case MimeTypes.VIDEO_H264:
        return "h264";
      default:
        return null;
    }
  }

  private static native String nativeGetFfmpegVersion();
  private static native boolean nativeHasDecoder(String codecName);
  private native long nativeInitialize(String codecName, byte[] extraData);
  private native int nativeDecode(long context, ByteBuffer inputData, int inputSize, long pts,
      boolean endOfInput, ByteBuffer outputData, int outputSize);
  private native int nativeGetChannelCount(long context);
  private native int nativeGetSampleRate(long context);
  private native int nativeGetWidth(long avFramePtr);
  private native int nativeGetHeight(long avFramePtr);
  private native long nativeGetPresentationTime(long avFramePtr);
  private native long nativeCreateSws(long avFramePtr, int scaledWidth, int scaledHeight);
  private native void nativeFreeSws(long swsPtr);
  private native int nativeScaleFrame(long swsPtr, long avFramePtr, ByteBuffer outputData,
      int outputLineSize);
  private native void nativeFreeFrame(long avFramePtr);
  private native long nativeReset(long context, byte[] extraData);
  private native void nativeRelease(long context);
}
