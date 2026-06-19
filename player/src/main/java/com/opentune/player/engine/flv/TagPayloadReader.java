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

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.TrackOutput;

/** Extracts individual samples from FLV tags, preserving original order. */
abstract class TagPayloadReader {

  /** Thrown when the format is not supported. */
  public static final class UnsupportedFormatException extends ParserException {

    public UnsupportedFormatException(String msg) {
      super(msg, /* cause= */ null, /* contentIsMalformed= */ false, C.DATA_TYPE_MEDIA);
    }
  }

  protected final TrackOutput output;

  protected TagPayloadReader(TrackOutput output) {
    this.output = output;
  }

  public abstract void seek();

  public final boolean consume(ParsableByteArray data, long timeUs) throws ParserException {
    return parseHeader(data) && parsePayload(data, timeUs);
  }

  protected abstract boolean parseHeader(ParsableByteArray data) throws ParserException;

  protected abstract boolean parsePayload(ParsableByteArray data, long timeUs)
      throws ParserException;
}
