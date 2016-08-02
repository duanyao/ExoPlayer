package com.google.android.exoplayer2.util;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;

import java.nio.ByteBuffer;

/**
 * Synthesize a encoded key frame.
 */
public class KeyFrameSynthesizer {
  private static final String TAG = "KeyFrameSynthesizer";

  /**
   * Synthesize a encoded key frame.
   * H.264 video with intra-refresh enabled only has one key frame at the start; if the key frame was
   * missed (e.g. start to play a live stream at the middle), Some implementations of MediaCodec can't
   * handle the stream. So we synthesize a key frame to make them happy. Note that some implementations
   * of MediaCodec still can't decode with a synthesized key frame.
   * <p/>
   * The key frame is encoded by a MediaCodec encoder. However not all resolutions that MediaCodec
   * decoders support are also supported by MediaCodec encoders. It is also possible that the encoding
   * takes too long time and we decide not to wait anymore. If the MediaCodec encoder failed to encode
   * a key frame, the buffer is kept untouched.
   *
   * @param buffer the buffer to receive encoded key frame.Existing data may be erased.
   * @param codecMime the codec's mime type, e.g. "video/avc"
   * @param width frame width
   * @param width frame height
   * @return the data of encoded key frame. Use param buffer if it has enough capacity, otherwise
   * a new ByteBuffer.If failed to create a key frame, null is returned.
   */
  @TargetApi(16)
  public static ByteBuffer synthesizeKeyFrame(ByteBuffer buffer, String codecMime, int width, int height) {
    MediaCodec encoder = null;
    boolean ok = false;
    try {
      encoder = MediaCodec.createEncoderByType(codecMime);

      // Figure out what input color format should be used.
      // TODO no way to ensure the output color format is the same as the input buffer?
      int colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
      if (Util.SDK_INT >= 18) {
        int[] colorFormats
            = encoder.getCodecInfo().getCapabilitiesForType(codecMime).colorFormats;
        for (int cf : colorFormats) {
          if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible ||
              cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
              cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            colorFormat = cf;
            break;
          }
        }
      }

      MediaFormat mf = MediaFormat.createVideoFormat(codecMime, width,
          height);
      mf.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
      mf.setInteger(MediaFormat.KEY_BIT_RATE, 500000);
      mf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
      mf.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
      encoder.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      encoder.start();
      MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();

      // It unusually requires more than one input frames (2 on some devices) to produces the first
      // output frame. Not sure about the upper limits on all devices, so retry up to 10 times.
      // Also note that dequeueInputBuffer(n)/dequeueOutputBuffer(n) (n > 0) is not reliable on all
      // devices so we use dequeueInputBuffer(0)/dequeueOutputBuffer(0) and Thread.sleep(n) instead.
      outer:
      for (int i = 0; i < 10; i++) {
        int inputIdx = -1;
        for (int j = 0; j < 10; j++) {
          inputIdx = encoder.dequeueInputBuffer(0);
          if (inputIdx < 0) {
            Log.d(TAG, "synthesizeKeyFrame: dequeueInputBuffer failed: round=" + j);
            try {
              Thread.sleep(50);
            } catch (Exception e) {}
          } else {
            Log.d(TAG, "synthesizeKeyFrame: dequeueInputBuffer ok");
            ByteBuffer inBuf = encoder.getInputBuffers()[inputIdx];
            encoder.queueInputBuffer(inputIdx, 0, inBuf.limit(), 0, 0);
            break;
          }
        }
        if (inputIdx < 0) {
          Log.w(TAG, "synthesizeKeyFrame: can't dequeueInputBuffer, abort!");
          break;
        }
        for (int k = 0; k < 20; k++) {
          int outputIdx = encoder.dequeueOutputBuffer(bufInfo, 0);
          if (outputIdx < 0) {
            Log.d(TAG, "synthesizeKeyFrame: dequeueOutputBuffer failed: round=" + k);
            try {
              Thread.sleep(50);
            } catch (Exception e) {}
            continue;
          }
          Log.d(TAG, "synthesizeKeyFrame: dequeueOutputBuffer ok: flags=" + bufInfo.flags);
          if ((bufInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
            ByteBuffer outBuf = encoder.getOutputBuffers()[outputIdx];
            if (buffer.capacity() < bufInfo.size) {
              buffer = ByteBuffer.allocate(bufInfo.size);
            }
            buffer.clear();
            // On some devices, outBuf's limit is inconsistent with bufInfo.size
            outBuf.limit(outBuf.position() + bufInfo.size);
            buffer.put(outBuf);
            buffer.flip();
            ok = true;
            encoder.releaseOutputBuffer(outputIdx, false);
            Log.d(TAG, "synthesizeKeyFrame: add key frame: size=" + buffer.limit());
            break outer;
          } else {
            encoder.releaseOutputBuffer(outputIdx, false);
            break;
          }
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      if (encoder != null) {
        try {
          encoder.stop();
          encoder.release();
        } catch (Exception e) {
        }
      }
    }
    if (ok) {
      return buffer;
    } else {
      Log.w(TAG, "synthesizeKeyFrame: failed, intra-refresh video may not play!");
      return null;
    }
  }
}
