/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.os.Handler;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.google.android.exoplayer2.video.SimpleDecoderVideoRenderer;
import com.google.android.exoplayer2.util.MimeTypes;

/**
 * Decodes and renders audio using FFmpeg.
 */
public final class FfmpegVideoRenderer extends SimpleDecoderVideoRenderer {
  private static final int NUM_BUFFERS = 4;
  private static final int INITIAL_INPUT_BUFFER_SIZE = 400 * 1024;

  private FfmpegDecoder decoder;

  public FfmpegVideoRenderer() {
    this(null, null);
  }

  public FfmpegVideoRenderer(Handler eventHandler,
                                  VideoRendererEventListener eventListener) {
    super(eventHandler, eventListener);
  }

  @Override
  public int supportsFormat(Format format) {
    String mimeType = format.sampleMimeType;
    if (!MimeTypes.isVideo(mimeType) || !FfmpegDecoder.IS_AVAILABLE) {
      return FORMAT_UNSUPPORTED_TYPE;
    }
    return FfmpegDecoder.supportsFormat(mimeType) ? FORMAT_HANDLED : FORMAT_UNSUPPORTED_SUBTYPE;
  }

  @Override
  protected FfmpegDecoder createDecoder(Format format) throws FfmpegDecoderException {
    decoder = new FfmpegDecoder(NUM_BUFFERS, NUM_BUFFERS, INITIAL_INPUT_BUFFER_SIZE,
        format.sampleMimeType, format.initializationData);
    return decoder;
  }
}
