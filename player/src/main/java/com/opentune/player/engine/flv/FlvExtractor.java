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
package com.opentune.player.engine.flv;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.max;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.IndexSeekMap;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Extracts data from the FLV container format.
 *
 * <p>Extended version that supports H.265/HEVC (codec ID 12) in addition to H.264 (codec ID 7).
 */
@UnstableApi
public final class FlvExtractor implements Extractor {

  /** Factory for {@link FlvExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new FlvExtractor()};

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    STATE_READING_FLV_HEADER,
    STATE_SKIPPING_TO_TAG_HEADER,
    STATE_READING_TAG_HEADER,
    STATE_READING_TAG_DATA
  })
  private @interface States {}

  private static final int STATE_READING_FLV_HEADER = 1;
  private static final int STATE_SKIPPING_TO_TAG_HEADER = 2;
  private static final int STATE_READING_TAG_HEADER = 3;
  private static final int STATE_READING_TAG_DATA = 4;

  private static final int FLV_HEADER_SIZE = 9;
  private static final int FLV_TAG_HEADER_SIZE = 11;

  private static final int TAG_TYPE_AUDIO = 8;
  private static final int TAG_TYPE_VIDEO = 9;
  private static final int TAG_TYPE_SCRIPT_DATA = 18;

  private static final int FLV_TAG = 0x00464c56;

  private final ParsableByteArray scratch;
  private final ParsableByteArray headerBuffer;
  private final ParsableByteArray tagHeaderBuffer;
  private final ParsableByteArray tagData;
  private final ScriptTagPayloadReader metadataReader;

  private @Nullable ExtractorOutput extractorOutput;
  private @States int state;
  private boolean outputFirstSample;
  private long mediaTagTimestampOffsetUs;
  private int bytesToNextTagHeader;
  private int tagType;
  private int tagDataSize;
  private long tagTimestampUs;
  private boolean outputSeekMap;
  private @Nullable AudioTagPayloadReader audioReader;
  private @Nullable VideoTagPayloadReader videoReader;

  public FlvExtractor() {
    scratch = new ParsableByteArray(4);
    headerBuffer = new ParsableByteArray(FLV_HEADER_SIZE);
    tagHeaderBuffer = new ParsableByteArray(FLV_TAG_HEADER_SIZE);
    tagData = new ParsableByteArray();
    metadataReader = new ScriptTagPayloadReader();
    state = STATE_READING_FLV_HEADER;
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    input.peekFully(scratch.getData(), 0, 3);
    scratch.setPosition(0);
    if (scratch.readUnsignedInt24() != FLV_TAG) {
      return false;
    }

    input.peekFully(scratch.getData(), 0, 2);
    scratch.setPosition(0);
    if ((scratch.readUnsignedShort() & 0xFA) != 0) {
      return false;
    }

    input.peekFully(scratch.getData(), 0, 4);
    scratch.setPosition(0);
    int dataOffset = scratch.readInt();

    input.resetPeekPosition();
    input.advancePeekPosition(dataOffset);

    input.peekFully(scratch.getData(), 0, 4);
    scratch.setPosition(0);

    return scratch.readInt() == 0;
  }

  @Override
  public void init(ExtractorOutput output) {
    this.extractorOutput = output;
  }

  @Override
  public void seek(long position, long timeUs) {
    if (position == 0) {
      state = STATE_READING_FLV_HEADER;
      outputFirstSample = false;
    } else {
      state = STATE_READING_TAG_HEADER;
    }
    bytesToNextTagHeader = 0;
  }

  @Override
  public void release() {
    // Do nothing
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    checkNotNull(extractorOutput);
    while (true) {
      switch (state) {
        case STATE_READING_FLV_HEADER:
          if (!readFlvHeader(input)) {
            return RESULT_END_OF_INPUT;
          }
          break;
        case STATE_SKIPPING_TO_TAG_HEADER:
          skipToTagHeader(input);
          break;
        case STATE_READING_TAG_HEADER:
          if (!readTagHeader(input)) {
            return RESULT_END_OF_INPUT;
          }
          break;
        case STATE_READING_TAG_DATA:
          if (readTagData(input)) {
            return RESULT_CONTINUE;
          }
          break;
        default:
          throw new IllegalStateException();
      }
    }
  }


  private boolean readFlvHeader(ExtractorInput input) throws IOException {
    if (!input.readFully(headerBuffer.getData(), 0, FLV_HEADER_SIZE, true)) {
      return false;
    }

    headerBuffer.setPosition(0);
    headerBuffer.skipBytes(4);
    int flags = headerBuffer.readUnsignedByte();
    boolean hasAudio = (flags & 0x04) != 0;
    boolean hasVideo = (flags & 0x01) != 0;
    if (hasAudio && audioReader == null) {
      audioReader =
          new AudioTagPayloadReader(extractorOutput.track(TAG_TYPE_AUDIO, C.TRACK_TYPE_AUDIO));
    }
    if (hasVideo && videoReader == null) {
      videoReader =
          new VideoTagPayloadReader(extractorOutput.track(TAG_TYPE_VIDEO, C.TRACK_TYPE_VIDEO));
    }
    extractorOutput.endTracks();

    bytesToNextTagHeader = headerBuffer.readInt() - FLV_HEADER_SIZE + 4;
    state = STATE_SKIPPING_TO_TAG_HEADER;
    return true;
  }

  private void skipToTagHeader(ExtractorInput input) throws IOException {
    input.skipFully(bytesToNextTagHeader);
    bytesToNextTagHeader = 0;
    state = STATE_READING_TAG_HEADER;
  }

  private boolean readTagHeader(ExtractorInput input) throws IOException {
    if (!input.readFully(tagHeaderBuffer.getData(), 0, FLV_TAG_HEADER_SIZE, true)) {
      return false;
    }

    tagHeaderBuffer.setPosition(0);
    tagType = tagHeaderBuffer.readUnsignedByte();
    tagDataSize = tagHeaderBuffer.readUnsignedInt24();
    tagTimestampUs = tagHeaderBuffer.readUnsignedInt24();
    tagTimestampUs = ((tagHeaderBuffer.readUnsignedByte() << 24) | tagTimestampUs) * 1000L;
    tagHeaderBuffer.skipBytes(3); // streamId
    state = STATE_READING_TAG_DATA;
    return true;
  }


  private boolean readTagData(ExtractorInput input) throws IOException {
    boolean wasConsumed = true;
    boolean wasSampleOutput = false;
    long timestampUs = getCurrentTimestampUs();
    if (tagType == TAG_TYPE_AUDIO && audioReader != null) {
      ensureReadyForMediaOutput();
      wasSampleOutput = audioReader.consume(prepareTagData(input), timestampUs);
    } else if (tagType == TAG_TYPE_VIDEO && videoReader != null) {
      ensureReadyForMediaOutput();
      wasSampleOutput = videoReader.consume(prepareTagData(input), timestampUs);
    } else if (tagType == TAG_TYPE_SCRIPT_DATA && !outputSeekMap) {
      wasSampleOutput = metadataReader.consume(prepareTagData(input), timestampUs);
      long durationUs = metadataReader.getDurationUs();
      if (durationUs != C.TIME_UNSET) {
        extractorOutput.seekMap(
            new IndexSeekMap(
                metadataReader.getKeyFrameTagPositions(),
                metadataReader.getKeyFrameTimesUs(),
                durationUs));
        outputSeekMap = true;
      }
    } else {
      input.skipFully(tagDataSize);
      wasConsumed = false;
    }
    if (!outputFirstSample && wasSampleOutput) {
      outputFirstSample = true;
      mediaTagTimestampOffsetUs =
          metadataReader.getDurationUs() == C.TIME_UNSET ? -tagTimestampUs : 0;
    }
    bytesToNextTagHeader = 4;
    state = STATE_SKIPPING_TO_TAG_HEADER;
    return wasConsumed;
  }

  private ParsableByteArray prepareTagData(ExtractorInput input) throws IOException {
    if (tagDataSize > tagData.capacity()) {
      tagData.reset(new byte[max(tagData.capacity() * 2, tagDataSize)], 0);
    } else {
      tagData.setPosition(0);
    }
    tagData.setLimit(tagDataSize);
    input.readFully(tagData.getData(), 0, tagDataSize);
    return tagData;
  }


  private void ensureReadyForMediaOutput() {
    if (!outputSeekMap) {
      extractorOutput.seekMap(new SeekMap.Unseekable(C.TIME_UNSET));
      outputSeekMap = true;
    }
  }

  private long getCurrentTimestampUs() {
    return outputFirstSample
        ? (mediaTagTimestampOffsetUs + tagTimestampUs)
        : (metadataReader.getDurationUs() == C.TIME_UNSET ? 0 : tagTimestampUs);
  }
}
