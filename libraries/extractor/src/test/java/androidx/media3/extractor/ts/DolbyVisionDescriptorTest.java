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

import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.container.DolbyVisionConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DolbyVisionDescriptor}. */
@RunWith(AndroidJUnit4.class)
public final class DolbyVisionDescriptorTest {

  @Test
  public void parseFromDescriptorBytes_profile8Level9_preservesLiveDescriptorFlags() {
    // tag=B0, length=5, version=1.0, profile=8, level=9, RPU+BL present, compatibility id=4.
    byte[] descriptorBytes = {
      (byte) 0xB0, 0x05, 0x01, 0x00, 0x10, 0x4D, 0x40
    };

    DolbyVisionDescriptor descriptor =
        DolbyVisionDescriptor.parseFromDescriptorBytes(descriptorBytes);

    assertThat(descriptor).isNotNull();
    assertThat(descriptor.profile).isEqualTo(8);
    assertThat(descriptor.level).isEqualTo(9);
    assertThat(descriptor.rpuPresentFlag).isTrue();
    assertThat(descriptor.elPresentFlag).isFalse();
    assertThat(descriptor.blPresentFlag).isTrue();
    assertThat(descriptor.blSignalCompatibilityId).isEqualTo(4);

    DolbyVisionConfig config =
        DolbyVisionConfig.parse(new ParsableByteArray(descriptor.buildInitializationData()));
    assertThat(config).isNotNull();
    assertThat(config.profile).isEqualTo(8);
    assertThat(config.level).isEqualTo(9);
    assertThat(config.codecs).isEqualTo("dvhe.08.09");
  }

  @Test
  public void buildInitializationData_allReferenceAppProfiles_buildsExpectedCodecStrings() {
    int[] profiles = {4, 5, 7, 8, 9, 10};
    String[] expectedCodecStrings = {
      "dvhe.04.09", "dvhe.05.09", "dvhe.07.09", "dvhe.08.09", "dvav.09.09", "dav1.10.09"
    };

    for (int i = 0; i < profiles.length; i++) {
      byte[] initializationData =
          DolbyVisionDescriptor.buildInitializationData(
              /* dvVersionMajor= */ 1,
              /* dvVersionMinor= */ 0,
              profiles[i],
              /* level= */ 9,
              /* rpuPresentFlag= */ true,
              /* elPresentFlag= */ profiles[i] == 4 || profiles[i] == 7,
              /* blPresentFlag= */ true,
              /* blSignalCompatibilityId= */ 0,
              /* mdCompression= */ 0);

      DolbyVisionConfig config =
          DolbyVisionConfig.parse(new ParsableByteArray(initializationData));

      assertThat(config).isNotNull();
      assertThat(config.profile).isEqualTo(profiles[i]);
      assertThat(config.level).isEqualTo(9);
      assertThat(config.codecs).isEqualTo(expectedCodecStrings[i]);
    }
  }

  @Test
  public void buildInitializationData_profilesOutsideReferenceAppMatrix_areRejected() {
    int[] unsupportedProfiles = {0, 1, 2, 3, 6, 11};

    for (int profile : unsupportedProfiles) {
      byte[] initializationData =
          DolbyVisionDescriptor.buildInitializationData(
              /* dvVersionMajor= */ 1,
              /* dvVersionMinor= */ 0,
              profile,
              /* level= */ 9,
              /* rpuPresentFlag= */ true,
              /* elPresentFlag= */ false,
              /* blPresentFlag= */ true,
              /* blSignalCompatibilityId= */ 0,
              /* mdCompression= */ 0);

      assertThat(DolbyVisionConfig.parse(new ParsableByteArray(initializationData))).isNull();
    }
  }
}
