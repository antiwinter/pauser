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
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.extractor.AvcConfig;
import androidx.media3.extractor.HevcConfig;
import androidx.media3.extractor.TrackOutput;

/** Parses video tags from an FLV stream. Supports H.264 (codec 7) and H.265/HEVC (codec 12). */
final class VideoTagPayloadReader extends TagPayloadReader {

  // Video codecs.
  private static final int VIDEO_CODEC_AVC = 7;
  private static final int VIDEO_CODEC_HEVC = 12;

  // Frame types.
  private static final int VIDEO_FRAME_KEYFRAME = 1;
  private static final int VIDEO_FRAME_VIDEO_INFO = 5;

  // Packet types (same for AVC and HEVC in legacy FLV).
  private static final int PACKET_TYPE_SEQUENCE_HEADER = 0;
  private static final int PACKET_TYPE_NALU = 1;

  // Temporary arrays.
  private final ParsableByteArray nalStartCode;
  private final ParsableByteArray nalLength;
  private int nalUnitLengthFieldLength;

  // State variables.
  private boolean hasOutputFormat;
  private boolean hasOutputKeyframe;
  private int frameType;
  private int videoCodec;

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
    int header = data.readUnsignedByte();
    int frameType = (header >> 4) & 0x0F;
    int videoCodec = (header & 0x0F);
    if (videoCodec != VIDEO_CODEC_AVC && videoCodec != VIDEO_CODEC_HEVC) {
      throw new UnsupportedFormatException("Video format not supported: " + videoCodec);
    }
    this.frameType = frameType;
    this.videoCodec = videoCodec;
    return (frameType != VIDEO_FRAME_VIDEO_INFO);
  }

  @Override
  protected boolean parsePayload(ParsableByteArray data, long timeUs) throws ParserException {
    int packetType = data.readUnsignedByte();
    int compositionTimeMs = data.readInt24();

    timeUs += compositionTimeMs * 1000L;

    if (packetType == PACKET_TYPE_SEQUENCE_HEADER && !hasOutputFormat) {
      ParsableByteArray videoSequence = new ParsableByteArray(new byte[data.bytesLeft()]);
      data.readBytes(videoSequence.getData(), 0, data.bytesLeft());

      Format format;
      if (videoCodec == VIDEO_CODEC_HEVC) {
        HevcConfig hevcConfig = HevcConfig.parse(videoSequence);
        nalUnitLengthFieldLength = hevcConfig.nalUnitLengthFieldLength;
        format =
            new Format.Builder()
                .setContainerMimeType(MimeTypes.VIDEO_FLV)
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(hevcConfig.codecs)
                .setWidth(hevcConfig.width)
                .setHeight(hevcConfig.height)
                .setInitializationData(hevcConfig.initializationData)
                .build();
      } else {
        AvcConfig avcConfig = AvcConfig.parse(videoSequence);
        nalUnitLengthFieldLength = avcConfig.nalUnitLengthFieldLength;
        format =
            new Format.Builder()
                .setContainerMimeType(MimeTypes.VIDEO_FLV)
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setCodecs(avcConfig.codecs)
                .setWidth(avcConfig.width)
                .setHeight(avcConfig.height)
                .setPixelWidthHeightRatio(avcConfig.pixelWidthHeightRatio)
                .setInitializationData(avcConfig.initializationData)
                .build();
      }
      output.format(format);
      hasOutputFormat = true;
      return false;
    } else if (packetType == PACKET_TYPE_NALU && hasOutputFormat) {
      boolean isKeyframe = frameType == VIDEO_FRAME_KEYFRAME;
      if (!hasOutputKeyframe && !isKeyframe) {
        return false;
      }
      // Convert length-delimited NAL units to start-code-delimited format.
      byte[] nalLengthData = nalLength.getData();
      nalLengthData[0] = 0;
      nalLengthData[1] = 0;
      nalLengthData[2] = 0;
      int nalUnitLengthFieldLengthDiff = 4 - nalUnitLengthFieldLength;
      int bytesWritten = 0;
      int bytesToWrite;
      while (data.bytesLeft() > 0) {
        data.readBytes(nalLength.getData(), nalUnitLengthFieldLengthDiff, nalUnitLengthFieldLength);
        nalLength.setPosition(0);
        bytesToWrite = nalLength.readUnsignedIntToInt();

        nalStartCode.setPosition(0);
        output.sampleData(nalStartCode, 4);
        bytesWritten += 4;

        output.sampleData(data, bytesToWrite);
        bytesWritten += bytesToWrite;
      }
      output.sampleMetadata(
          timeUs, isKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0, bytesWritten, 0, null);
      hasOutputKeyframe = true;
      return true;
    } else {
      return false;
    }
  }
}
