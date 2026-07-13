# Media3 AV3A decoder

This extension handles only `audio/av3a`. Its JNI library (`libav3aJNI.so`) is
built directly from the AVS3 decoder sources and does not contain or load
FFmpeg. All other audio formats remain on Media3's normal MediaCodec path.
