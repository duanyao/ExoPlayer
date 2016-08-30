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
#include <jni.h>
#include <stdlib.h>
#include <android/log.h>

#define ENABLE_VIDEO
//#define ENABLE_AUDIO

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavcodec/avcodec.h>

#ifdef ENABLE_AUDIO
#include <libavresample/avresample.h>
#endif

#ifdef ENABLE_VIDEO
#include <libswscale/swscale.h>
#endif

#include <libavutil/error.h>
#include <libavutil/opt.h>
}

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, \
                   __VA_ARGS__))
#define FUNC(RETURN_TYPE, NAME, ...) \
  extern "C" { \
  JNIEXPORT RETURN_TYPE \
    Java_com_google_android_exoplayer2_ext_ffmpeg_FfmpegDecoder_ ## NAME \
      (JNIEnv* env, jobject thiz, ##__VA_ARGS__);\
  } \
  JNIEXPORT RETURN_TYPE \
    Java_com_google_android_exoplayer2_ext_ffmpeg_FfmpegDecoder_ ## NAME \
      (JNIEnv* env, jobject thiz, ##__VA_ARGS__)\

#define ERROR_STRING_BUFFER_LENGTH 256

// Request a format corresponding to AudioFormat.ENCODING_PCM_16BIT.
static const AVSampleFormat OUTPUT_FORMAT = AV_SAMPLE_FMT_S16;

/**
 * Returns the AVCodec with the specified name, or NULL if it is not available.
 */
AVCodec *getCodecByName(JNIEnv* env, jstring codecName);

/**
 * Allocates and opens a new AVCodecContext for the specified codec, passing the
 * provided extraData as initialization data for the decoder if it is non-NULL.
 * Returns the created context.
 */
AVCodecContext *createContext(JNIEnv *env, AVCodec *codec,
                              jbyteArray extraData);

/**
 * Decodes the packet into the output buffer, returning the number of bytes
 * written, or a negative value in the case of an error.
 */
int decodePacket(AVCodecContext *context, AVPacket *packet,
                 jboolean endOfInput, uint8_t *outputBuffer, int outputLimit);

/**
 * Outputs a log message describing the avcodec error number.
 */
void logError(const char *functionName, int errorNumber);

/**
 * Releases the specified context.
 */
void releaseContext(AVCodecContext *context);

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  JNIEnv *env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return -1;
  }
  avcodec_register_all();
  return JNI_VERSION_1_6;
}

FUNC(jstring, nativeGetFfmpegVersion) {
  return env->NewStringUTF(LIBAVCODEC_IDENT);
}

FUNC(jboolean, nativeHasDecoder, jstring codecName) {
  return getCodecByName(env, codecName) != NULL;
}

FUNC(jlong, nativeInitialize, jstring codecName, jbyteArray extraData) {
  AVCodec *codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }
  return (jlong) createContext(env, codec, extraData);
}

FUNC(jint, nativeDecode, jlong context, jobject inputData, jint inputSize,
    jlong pts, jboolean endOfInput, jobject outputData, jint outputLimit) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return AVERROR(EINVAL);
  }
  if (!inputData || !outputData) {
    LOGE("Input and output buffers must be non-NULL.");
    return AVERROR(EINVAL);
  }
  if (inputSize < 0) {
    LOGE("Invalid input buffer size: %d.", inputSize);
    return AVERROR(EINVAL);
  }
  if (outputLimit < 0) {
    LOGE("Invalid output buffer length: %d", outputLimit);
    return AVERROR(EINVAL);
  }
  uint8_t *inputBuffer = (uint8_t *) env->GetDirectBufferAddress(inputData);
  uint8_t *outputBuffer = (uint8_t *) env->GetDirectBufferAddress(outputData);
  AVPacket packet;
  av_init_packet(&packet);
  packet.data = inputSize > 0 ? inputBuffer : NULL;
  packet.size = inputSize;
  packet.pts = pts;
  packet.dts = AV_NOPTS_VALUE;
  return decodePacket((AVCodecContext *) context, &packet, endOfInput, outputBuffer,
                      outputLimit);
}

#ifdef ENABLE_AUDIO
FUNC(jint, nativeGetChannelCount, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return AVERROR(EINVAL);
  }
  return ((AVCodecContext *) context)->channels;
}

FUNC(jint, nativeGetSampleRate, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return AVERROR(EINVAL);
  }
  return ((AVCodecContext *) context)->sample_rate;
}
#endif //#ifdef ENABLE_AUDIO

#ifdef ENABLE_VIDEO
FUNC(jint, nativeGetWidth, jlong avFrame) {
  if (!avFrame) {
    LOGE("avFrame must be non-NULL.");
    return AVERROR(EINVAL);
  }
  return ((AVFrame *) avFrame)->width;
}

FUNC(jint, nativeGetHeight, jlong avFrame) {
  if (!avFrame) {
    LOGE("avFrame must be non-NULL.");
    return AVERROR(EINVAL);
  }
  return ((AVFrame *) avFrame)->height;
}

FUNC(jlong, nativeGetPresentationTime, jlong avFrame) {
  if (!avFrame) {
    LOGE("avFrame must be non-NULL.");
    return AVERROR(EINVAL);
  }
  return ((AVFrame *) avFrame)->pkt_pts;
}

FUNC(jlong, nativeCreateSws, jlong jFrame, jint scaledWidth, jint scaledHeight) {
  AVFrame *frame = (AVFrame *) jFrame;
  //LOGE(">>>>nativeCreateSws:sw=%d,sh=%d,w=%d,h=%d,fmt=%d", scaledWidth, scaledHeight, frame->width, frame->height, frame->format);
  return (jlong)sws_getContext(
                frame->width,
                frame->height,
                (AVPixelFormat)frame->format,
                scaledWidth,
                scaledHeight,
                AV_PIX_FMT_RGB565,
                SWS_BILINEAR,
                NULL,
                NULL,
                NULL
               );

}

FUNC(void, nativeFreeSws, jlong sws) {
  sws_freeContext((SwsContext *) sws);
}

FUNC(jint, nativeScaleFrame, jlong sws, jlong jFrame, jobject outputData, jint outputLineSize) {
  AVFrame *frame = (AVFrame *) jFrame;
  SwsContext *swsContext = (SwsContext *) sws;
  uint8_t *dest[] = { (uint8_t *) env->GetDirectBufferAddress(outputData) };
  int destLineSize[] = { outputLineSize };
  return sws_scale(swsContext, frame->data, frame->linesize, 0, frame->height, dest, destLineSize);
}

FUNC(void, nativeFreeFrame, jlong jFrame) {
  if (!jFrame) {
    return;
  }
  AVFrame *frame = (AVFrame*) jFrame;
  av_frame_free(&frame);
}
#endif //#ifdef ENABLE_VIDEO

FUNC(jlong, nativeReset, jlong jContext, jbyteArray extraData) {
  AVCodecContext *context = (AVCodecContext *) jContext;
  if (!context) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }

  AVCodecID codecId = context->codec_id;
  if (codecId == AV_CODEC_ID_TRUEHD) {
    // Release and recreate the context if the codec is TrueHD.
    // TODO: Figure out why flushing doesn't work for this codec.
    releaseContext(context);
    AVCodec *codec = avcodec_find_decoder(codecId);
    if (!codec) {
      LOGE("Unexpected error finding codec %d.", codecId);
      return 0L;
    }
    return (jlong) createContext(env, codec, extraData);
  }

  avcodec_flush_buffers(context);
  return (jlong) context;
}

FUNC(void, nativeRelease, jlong context) {
  if (context) {
    releaseContext((AVCodecContext *) context);
  }
}

AVCodec *getCodecByName(JNIEnv* env, jstring codecName) {
  if (!codecName) {
    return NULL;
  }
  const char *codecNameChars = env->GetStringUTFChars(codecName, NULL);
  AVCodec *codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

AVCodecContext *createContext(JNIEnv *env, AVCodec *codec,
                              jbyteArray extraData) {
  AVCodecContext *context = avcodec_alloc_context3(codec);
  if (!context) {
    LOGE("Failed to allocate context.");
    return NULL;
  }
  context->request_sample_fmt = OUTPUT_FORMAT;
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    context->extradata_size = size;
    context->extradata =
        (uint8_t *) av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!context->extradata) {
      LOGE("Failed to allocate extradata.");
      releaseContext(context);
      return NULL;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte *) context->extradata);
  }
  int result = avcodec_open2(context, codec, NULL);
  if (result < 0) {
    logError("avcodec_open2", result);
    releaseContext(context);
    return NULL;
  }
  if (context->codec_type == AVMEDIA_TYPE_VIDEO) {
    context->delay = 0;
  }
  return context;
}

int decodePacket(AVCodecContext *context, AVPacket *packet, jboolean endOfInput,
                 uint8_t *outputBuffer, int outputLimit) {
  int result = 0;
  // Queue input data.
  //LOGE(">>>>before avcodec_send_packet.");
  if (packet->size > 0 || endOfInput) {
    result = avcodec_send_packet(context, packet);
    if (result != 0 && result != AVERROR_EOF) {
      logError("avcodec_send_packet", result);
      if (AVERROR_INVALIDDATA == result) {
        //LOGE(">>>>avcodec_send_packet: invalid data, try to continue.");
        result = 0;
      } else {
        return result;
      }
    } else {
      //LOGE(">>>>avcodec_send_packet OK. pts=%lld", packet->pts);
    }
  } else {
    //LOGE(">>>>avcodec_send_packet: empty");
  }

  // Dequeue output data until it runs out.
  int outSize = 0;
  while (true) {
    //LOGE(">>>>before av_frame_alloc.");
    AVFrame *frame = av_frame_alloc();
    if (!frame) {
      LOGE("Failed to allocate output frame.");
      return AVERROR(ENOMEM);
    }
    //LOGE(">>>>before avcodec_receive_frame");
    result = avcodec_receive_frame(context, frame);
    if (result != 0) {
      av_frame_free(&frame);
      if (result == AVERROR(EAGAIN)) {
        //LOGE(">>>>after avcodec_receive_frame: EAGAIN(%d)", EAGAIN);
        break;
      } else {
        //if (result != AVERROR_EOF) {
          logError("avcodec_receive_frame", result);
        //}
        return result;
      }
    }

    if (context->codec_type == AVMEDIA_TYPE_VIDEO) {
      //LOGE(">>>>got video frame:w=%d,h=%d,key=%d,pts=%lld,pkt_pts=%lld", frame->width, frame->height, frame->key_frame, frame->pts, frame->pkt_pts);
      outSize = sizeof(jlong);
      if (outputLimit < outSize) {
        LOGE("Output buffer size (%d) too small for output data (%d).",
             outputLimit, outSize);
        return AVERROR_BUFFER_TOO_SMALL;
      }
      *((jlong *) outputBuffer) = (jlong) frame;
      //LOGE(">>>>got video frame:addr=%lld, realAddr=%x", *((jlong*)outputBuffer), (uintptr_t)frame);
      return outSize; // for video there is no more than 1 output frame for on input packet, so no need to loop.
    } else {
#ifdef ENABLE_AUDIO
      // Resample output.
      AVSampleFormat sampleFormat = context->sample_fmt;
      int channelCount = context->channels;
      int channelLayout = context->channel_layout;
      int sampleRate = context->sample_rate;
      int sampleCount = frame->nb_samples;
      int dataSize = av_samples_get_buffer_size(NULL, channelCount, sampleCount,
                                                sampleFormat, 1);
      AVAudioResampleContext *resampleContext;
      if (context->opaque) {
        resampleContext = (AVAudioResampleContext *)context->opaque;
      } else {
        resampleContext = avresample_alloc_context();
        av_opt_set_int(resampleContext, "in_channel_layout",  channelLayout, 0);
        av_opt_set_int(resampleContext, "out_channel_layout", channelLayout, 0);
        av_opt_set_int(resampleContext, "in_sample_rate", sampleRate, 0);
        av_opt_set_int(resampleContext, "out_sample_rate", sampleRate, 0);
        av_opt_set_int(resampleContext, "in_sample_fmt", sampleFormat, 0);
        av_opt_set_int(resampleContext, "out_sample_fmt", OUTPUT_FORMAT, 0);
        result = avresample_open(resampleContext);
        if (result != 0) {
          logError("avresample_open", result);
          av_frame_free(&frame);
          return result;
        }
        context->opaque = resampleContext;
      }
      int inSampleSize = av_get_bytes_per_sample(sampleFormat);
      int outSampleSize = av_get_bytes_per_sample(OUTPUT_FORMAT);
      int outSamples = avresample_get_out_samples(resampleContext, sampleCount);
      int bufferOutSize = outSampleSize * channelCount * outSamples;
      if (outSize + bufferOutSize > outputLimit) {
        LOGE("Output buffer size (%d) too small for output data (%d).",
             outputLimit, outSize + bufferOutSize);
        av_frame_free(&frame);
        return AVERROR_BUFFER_TOO_SMALL;
      }
      result = avresample_convert(resampleContext, &outputBuffer, bufferOutSize,
                                  outSamples, frame->data, frame->linesize[0],
                                  sampleCount);
      av_frame_free(&frame);
      if (result != 0) {
        logError("avresample_convert", result);
        return result;
      }
      int available = avresample_available(resampleContext);
      if (available != 0) {
        LOGE("Expected no samples remaining after resampling, but found %d.",
             available);
        return AVERROR_BUG;
      }
      outputBuffer += bufferOutSize;
      outSize += bufferOutSize;
#else
      LOGE("Error: ENABLE_AUDIO not defined, but decoding audio.");
      av_frame_free(&frame);
#endif
    }
  }
  return outSize;
}

void logError(const char *functionName, int errorNumber) {
  char *buffer = (char *) malloc(ERROR_STRING_BUFFER_LENGTH * sizeof(char));
  av_strerror(errorNumber, buffer, ERROR_STRING_BUFFER_LENGTH);
  LOGE("Error in %s: %s", functionName, buffer);
  free(buffer);
}

void releaseContext(AVCodecContext *context) {
  if (!context) {
    return;
  }
#ifdef ENABLE_AUDIO
  AVAudioResampleContext *resampleContext;
  if (resampleContext = (AVAudioResampleContext *)context->opaque) {
    avresample_free(&resampleContext);
    context->opaque = NULL;
  }
#endif
  //LOGE(">>>>releaseContext");
  avcodec_free_context(&context);
}

