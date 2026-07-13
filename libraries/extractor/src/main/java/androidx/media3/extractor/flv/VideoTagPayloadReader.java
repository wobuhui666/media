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
package androidx.media3.extractor.flv;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.extractor.AvcConfig;
import androidx.media3.extractor.HevcConfig;
import androidx.media3.extractor.TrackOutput;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Parses legacy and enhanced-RTMP video tags and extracts H.264/H.265 NAL units. */
/* package */ final class VideoTagPayloadReader extends TagPayloadReader {

  // Video codec.
  private static final int VIDEO_CODEC_AVC = 7;
  private static final int VIDEO_CODEC_HEVC = 12;

  // Enhanced RTMP video header constants.
  private static final int VIDEO_HEADER_EX_MASK = 0x80;
  private static final int VIDEO_FOURCC_HEVC = 0x68766331; // "hvc1"

  // Frame types.
  private static final int VIDEO_FRAME_KEYFRAME = 1;
  private static final int VIDEO_FRAME_VIDEO_INFO = 5;

  // Packet types.
  private static final int AVC_PACKET_TYPE_SEQUENCE_HEADER = 0;
  private static final int AVC_PACKET_TYPE_AVC_NALU = 1;
  private static final int VIDEO_PACKET_TYPE_CODED_FRAMES_X = 3;

  // Temporary arrays.
  private final ParsableByteArray nalStartCode;
  private final ParsableByteArray nalLength;
  private int nalUnitLengthFieldLength;

  // State variables.
  private boolean hasOutputFormat;
  private boolean hasOutputKeyframe;
  private @MonotonicNonNull Format format;
  private @MonotonicNonNull Format pendingFormat;
  private int frameType;
  private int videoCodec;
  private int packetType;
  private boolean enhancedHeader;

  /**
   * @param output A {@link TrackOutput} to which samples should be written.
   */
  public VideoTagPayloadReader(TrackOutput output) {
    super(output);
    nalStartCode = new ParsableByteArray(NalUnitUtil.NAL_START_CODE);
    nalLength = new ParsableByteArray(4);
  }

  @Override
  public void seek() {
    hasOutputKeyframe = false;
  }

  @Override
  protected boolean parseHeader(ParsableByteArray data) throws UnsupportedFormatException {
    if (data.bytesLeft() < 1) {
      throw new UnsupportedFormatException("Missing FLV video tag header");
    }
    int header = data.readUnsignedByte();
    enhancedHeader = (header & VIDEO_HEADER_EX_MASK) != 0;
    frameType = (header >> 4) & (enhancedHeader ? 0x07 : 0x0F);
    if (enhancedHeader) {
      packetType = header & 0x0F;
      if (data.bytesLeft() < 4) {
        throw new UnsupportedFormatException("Missing enhanced FLV video FourCC");
      }
      int videoFourCc = data.readInt();
      if (videoFourCc != VIDEO_FOURCC_HEVC) {
        throw new UnsupportedFormatException(
            "Enhanced video format not supported: " + Integer.toHexString(videoFourCc));
      }
      videoCodec = VIDEO_CODEC_HEVC;
      return frameType != VIDEO_FRAME_VIDEO_INFO;
    }
    videoCodec = (header & 0x0F);
    if (videoCodec != VIDEO_CODEC_AVC && videoCodec != VIDEO_CODEC_HEVC) {
      throw new UnsupportedFormatException("Video format not supported: " + videoCodec);
    }
    return (frameType != VIDEO_FRAME_VIDEO_INFO);
  }

  @Override
  protected boolean parsePayload(ParsableByteArray data, long timeUs) throws ParserException {
    int compositionTimeMs = 0;
    if (!enhancedHeader) {
      if (data.bytesLeft() < 4) {
        throw ParserException.createForMalformedContainer("Truncated FLV video packet", null);
      }
      packetType = data.readUnsignedByte();
      compositionTimeMs = data.readInt24();
    } else if (packetType == AVC_PACKET_TYPE_AVC_NALU) {
      if (data.bytesLeft() < 3) {
        throw ParserException.createForMalformedContainer(
            "Missing enhanced FLV composition time", null);
      }
      compositionTimeMs = data.readInt24();
    }

    timeUs += compositionTimeMs * 1000L;
    // Parse sequence headers in case this was not done before, or in case the stream updates them.
    if (packetType == AVC_PACKET_TYPE_SEQUENCE_HEADER) {
      ParsableByteArray videoSequence = new ParsableByteArray(new byte[data.bytesLeft()]);
      data.readBytes(videoSequence.getData(), 0, data.bytesLeft());
      Format newFormat = videoCodec == VIDEO_CODEC_AVC ? parseAvcFormat(videoSequence) : parseHevcFormat(videoSequence);
      if (!newFormat.equals(format)) {
        pendingFormat = newFormat;
      }
      hasOutputKeyframe = false;
      return false;
    } else if ((packetType == AVC_PACKET_TYPE_AVC_NALU
            || (enhancedHeader && packetType == VIDEO_PACKET_TYPE_CODED_FRAMES_X))
        && (hasOutputFormat || pendingFormat != null)) {
      boolean isKeyframe = frameType == VIDEO_FRAME_KEYFRAME;
      if (!hasOutputKeyframe && !isKeyframe) {
        return false;
      }
      if (pendingFormat != null) {
        format = pendingFormat;
        pendingFormat = null;
        output.format(format);
        hasOutputFormat = true;
      }
      // TODO: Deduplicate with Mp4Extractor.
      // Zero the top three bytes of the array that we'll use to decode nal unit lengths, in case
      // they're only 1 or 2 bytes long.
      byte[] nalLengthData = nalLength.getData();
      nalLengthData[0] = 0;
      nalLengthData[1] = 0;
      nalLengthData[2] = 0;
      int nalUnitLengthFieldLengthDiff = 4 - nalUnitLengthFieldLength;
      // NAL units are length delimited, but the decoder requires start code delimited units.
      // Loop until we've written the sample to the track output, replacing length delimiters with
      // start codes as we encounter them.
      int bytesWritten = 0;
      int bytesToWrite;
      while (data.bytesLeft() > 0) {
        if (data.bytesLeft() < nalUnitLengthFieldLength) {
          throw ParserException.createForMalformedContainer(
              "Truncated FLV NAL unit length", null);
        }
        // Read the NAL length so that we know where we find the next one.
        data.readBytes(nalLength.getData(), nalUnitLengthFieldLengthDiff, nalUnitLengthFieldLength);
        nalLength.setPosition(0);
        bytesToWrite = nalLength.readUnsignedIntToInt();
        if (bytesToWrite == 0) {
          continue;
        }
        if (bytesToWrite > data.bytesLeft()) {
          throw ParserException.createForMalformedContainer(
              "FLV NAL unit length exceeds tag payload", null);
        }

        // Write a start code for the current NAL unit.
        nalStartCode.setPosition(0);
        output.sampleData(nalStartCode, 4);
        bytesWritten += 4;

        // Write the payload of the NAL unit.
        output.sampleData(data, bytesToWrite);
        bytesWritten += bytesToWrite;
      }
      output.sampleMetadata(timeUs, isKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0, bytesWritten, 0, null);
      hasOutputKeyframe = true;
      return true;
    } else {
      return false;
    }
  }

  private Format parseAvcFormat(ParsableByteArray videoSequence) throws ParserException {
    AvcConfig avcConfig = AvcConfig.parse(videoSequence);
    nalUnitLengthFieldLength = avcConfig.nalUnitLengthFieldLength;
    return new Format.Builder()
        .setContainerMimeType(MimeTypes.VIDEO_FLV)
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setCodecs(avcConfig.codecs)
        .setWidth(avcConfig.width)
        .setHeight(avcConfig.height)
        .setPixelWidthHeightRatio(avcConfig.pixelWidthHeightRatio)
        .setInitializationData(avcConfig.initializationData)
        .build();
  }

  private Format parseHevcFormat(ParsableByteArray videoSequence) throws ParserException {
    HevcConfig hevcConfig = HevcConfig.parse(videoSequence);
    nalUnitLengthFieldLength = hevcConfig.nalUnitLengthFieldLength;
    return new Format.Builder()
        .setContainerMimeType(MimeTypes.VIDEO_FLV)
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setCodecs(hevcConfig.codecs)
        .setWidth(hevcConfig.width)
        .setHeight(hevcConfig.height)
        .setPixelWidthHeightRatio(hevcConfig.pixelWidthHeightRatio)
        .setInitializationData(hevcConfig.initializationData)
        .build();
  }
}
