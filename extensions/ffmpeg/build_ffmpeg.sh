#!/bin/sh
#cd /D/project/ExoPlayer.git/extensions/ffmpeg

NDK_PATH="/d/software/android-ndk-r12b/"

NDK_PATH_WIN="d:\software\android-ndk-r12b"

EXOPLAYER_ROOT="$(pwd)/../../"
FFMPEG_EXT_PATH="$(pwd)/src/main/"

config_audio() {
echo config_audio
cd "${FFMPEG_EXT_PATH}/jni/ffmpeg" && \
./configure \
    --libdir=android-libs/armeabi-v7a \
    --arch=arm \
    --cpu=armv7-a \
    --cross-prefix="${NDK_PATH}/toolchains/arm-linux-androideabi-4.9/prebuilt/windows-x86_64/bin/arm-linux-androideabi-" \
    --target-os=android \
    --sysroot="${NDK_PATH}/platforms/android-9/arch-arm/" \
    --extra-cflags="-march=armv7-a -mfloat-abi=softfp" \
    --extra-ldflags="-Wl,--fix-cortex-a8" \
    --extra-ldexeflags=-pie \
    --disable-static \
    --enable-shared \
    --disable-doc \
    --disable-programs \
    --disable-everything \
    --disable-avdevice \
    --disable-avformat \
    --disable-swscale \
    --disable-postproc \
    --disable-avfilter \
    --disable-symver \
    --enable-avresample \
    --enable-decoder=vorbis \
    --enable-decoder=opus \
    --enable-decoder=flac
}

config_video() {
echo config_video
cd "${FFMPEG_EXT_PATH}/jni/ffmpeg" && \
./configure \
    --libdir=android-libs/armeabi-v7a \
    --arch=arm \
    --cpu=armv7-a \
    --cross-prefix="${NDK_PATH}/toolchains/arm-linux-androideabi-4.9/prebuilt/windows-x86_64/bin/arm-linux-androideabi-" \
    --target-os=android \
    --sysroot="${NDK_PATH}/platforms/android-9/arch-arm/" \
    --extra-cflags="-march=armv7-a -mfloat-abi=softfp" \
    --extra-ldflags="-Wl,--fix-cortex-a8" \
    --extra-ldexeflags=-pie \
    --enable-static \
    --disable-shared \
    --disable-doc \
    --disable-programs \
    --disable-everything \
    --disable-avdevice \
    --disable-avformat \
    --disable-swscale-alpha \
    --disable-postproc \
    --disable-avfilter \
    --disable-symver \
    --enable-avresample \
    --enable-decoder=h264
}

compile_ffmpeg() {
echo compile_ffmpeg
cd "${FFMPEG_EXT_PATH}/jni/ffmpeg" && make-old && make-old install-libs
}

compile_jni() {
echo compile_jni
cd "${FFMPEG_EXT_PATH}"/jni && cmd /c "${NDK_PATH_WIN}\ndk-build.cmd APP_ABI=armeabi-v7a -j4"
}

#config_audio
#config_jni
#config_video
#compile_ffmpeg
compile_jni

# TODO
# AudioDecodeException
# ordering
# log