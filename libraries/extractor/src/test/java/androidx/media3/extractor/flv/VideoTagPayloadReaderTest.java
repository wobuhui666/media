/*
 * Copyright 2026 The Android Open Source Project
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

import static androidx.media3.common.util.Util.getBytesFromHexString;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link VideoTagPayloadReader}. */
@RunWith(AndroidJUnit4.class)
public final class VideoTagPayloadReaderTest {

  @Test
  public void consume_legacyHevcSequenceHeader_outputsHevcFormat() throws Exception {
    FakeTrackOutput output = newOutput();
    VideoTagPayloadReader reader = new VideoTagPayloadReader(output);

    reader.consume(new ParsableByteArray(hex(LEGACY_HEVC_SEQUENCE_HEADER)), 0);
    reader.consume(new ParsableByteArray(hex(LEGACY_HEVC_KEYFRAME)), 123_000);

    assertHevcFormat(output.lastFormat);
    output.assertSample(0, hex("00000001010203"), 123_000, C.BUFFER_FLAG_KEY_FRAME, null);
  }

  @Test
  public void consume_enhancedHevcCodedFrames_outputsFormatAndCompositionTimestamp()
      throws Exception {
    FakeTrackOutput output = newOutput();
    VideoTagPayloadReader reader = new VideoTagPayloadReader(output);

    reader.consume(new ParsableByteArray(hex(ENHANCED_HEVC_SEQUENCE_START)), 0);
    reader.consume(
        new ParsableByteArray(
            hex("9168766331" + "000002" + "00000003" + "010203")),
        123_000);

    assertHevcFormat(output.lastFormat);
    output.assertSample(0, hex("00000001010203"), 125_000, C.BUFFER_FLAG_KEY_FRAME, null);
  }

  @Test
  public void consume_enhancedHevcCodedFramesX_usesZeroCompositionOffset() throws Exception {
    FakeTrackOutput output = newOutput();
    VideoTagPayloadReader reader = new VideoTagPayloadReader(output);

    reader.consume(new ParsableByteArray(hex(ENHANCED_HEVC_SEQUENCE_START)), 0);
    reader.consume(
        new ParsableByteArray(hex("9368766331" + "00000003" + "010203")), 456_000);

    output.assertSample(0, hex("00000001010203"), 456_000, C.BUFFER_FLAG_KEY_FRAME, null);
  }

  private static FakeTrackOutput newOutput() {
    return new FakeTrackOutput(C.TRACK_TYPE_VIDEO, /* deduplicateConsecutiveFormats= */ false);
  }

  private static byte[] hex(String value) {
    return getBytesFromHexString(value);
  }

  private static void assertHevcFormat(Format format) {
    assertThat(format).isNotNull();
    assertThat(format.containerMimeType).isEqualTo(MimeTypes.VIDEO_FLV);
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H265);
    assertThat(format.codecs).startsWith("hvc1.1.6.L153");
    assertThat(format.width).isEqualTo(3840);
    assertThat(format.height).isEqualTo(2160);
    assertThat(format.initializationData).hasSize(1);
  }

  private static final String HEVC_CONFIG =
      "01016000000090000000000099F001FFFDF8F800000F03A00001001840010C01FFFF016000000300900000030000030099913024"
          + "A10001003A420101016000000300900000030000030099A001E020021C59644D3930BC05A80808082000007D0000186A0C008FBDC0000AA32400003B9104"
          + "40A2000100064401C073C189";

  private static final String LEGACY_HEVC_SEQUENCE_HEADER = "1C00" + "000000" + HEVC_CONFIG;
  private static final String LEGACY_HEVC_KEYFRAME =
      "1C01" + "000000" + "00000003" + "010203";
  private static final String ENHANCED_HEVC_SEQUENCE_START = "9068766331" + HEVC_CONFIG;
}
