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
package androidx.media3.extractor.ts;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link Av3aReader}. */
@RunWith(AndroidJUnit4.class)
public final class Av3aReaderTest {

  @Test
  public void consume_cctv8kTenChannelHeader_outputsFormatAndCompleteSample() {
    // Captured from cctv8k.m3u8 (stream_type 0xD5, PID 0x201).
    assertLiveFrame(new byte[] {(byte) 0xFF, (byte) 0xF2, 0x10, 0x5A, (byte) 0x82, 0x11, (byte) 0xD4});
  }

  @Test
  public void consume_cctv4kAdvertisedTenChannelHeader_outputsFormatAndCompleteSample() {
    // Captured from cctv4k.m3u8 (stream_type 0xD5, PID 0x101). Its native decoder is known to
    // return six PCM channels even though the bitstream channel configuration is 5.1.4.
    assertLiveFrame(new byte[] {(byte) 0xFF, (byte) 0xF2, 0x10, 0x5C, (byte) 0x82, 0x11, (byte) 0xE7});
  }

  private static void assertLiveFrame(byte[] header) {
    byte[] frame = new byte[1024]; // 384 kbit/s * 1024 samples / 48 kHz / 8.
    System.arraycopy(header, 0, frame, 0, header.length);

    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    Av3aReader reader = new Av3aReader("zh", C.ROLE_FLAG_MAIN);
    reader.createTracks(extractorOutput, new TrackIdGenerator(0, 1));
    reader.packetStarted(1_000_000, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR);

    reader.consume(new ParsableByteArray(Arrays.copyOfRange(frame, 0, 5)));
    reader.consume(new ParsableByteArray(Arrays.copyOfRange(frame, 5, frame.length)));

    FakeTrackOutput output = extractorOutput.trackOutputs.get(0);
    Format format = output.lastFormat;
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.AUDIO_AV3A);
    assertThat(format.sampleRate).isEqualTo(48_000);
    assertThat(format.channelCount).isEqualTo(10);
    assertThat(format.averageBitrate).isEqualTo(384_000);
    assertThat(format.language).isEqualTo("zh");
    output.assertSample(0, frame, 1_000_000, C.BUFFER_FLAG_KEY_FRAME, null);
  }
}
