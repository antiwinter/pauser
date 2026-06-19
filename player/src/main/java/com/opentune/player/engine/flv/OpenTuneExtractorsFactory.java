package com.opentune.player.engine.flv;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.amr.AmrExtractor;
import androidx.media3.extractor.avi.AviExtractor;
import androidx.media3.extractor.avif.AvifExtractor;
import androidx.media3.extractor.bmp.BmpExtractor;
import androidx.media3.extractor.flac.FlacExtractor;
import androidx.media3.extractor.heif.HeifExtractor;
import androidx.media3.extractor.jpeg.JpegExtractor;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.mp3.Mp3Extractor;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.ogg.OggExtractor;
import androidx.media3.extractor.png.PngExtractor;
import androidx.media3.extractor.ts.Ac3Extractor;
import androidx.media3.extractor.ts.Ac4Extractor;
import androidx.media3.extractor.ts.AdtsExtractor;
import androidx.media3.extractor.ts.PsExtractor;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.extractor.wav.WavExtractor;
import androidx.media3.extractor.webp.WebpExtractor;

/**
 * Custom extractors factory that substitutes the stock FlvExtractor with our
 * HEVC-capable version. All other extractors are unchanged.
 */
@UnstableApi
public final class OpenTuneExtractorsFactory implements ExtractorsFactory {

  @Override
  public Extractor[] createExtractors() {
    return new Extractor[] {
        new MatroskaExtractor(),
        new FragmentedMp4Extractor(),
        new Mp4Extractor(),
        new PsExtractor(),
        new TsExtractor(),
        new FlvExtractor(),  // our HEVC-capable version
        new OggExtractor(),
        new WavExtractor(),
        new FlacExtractor(),
        new AmrExtractor(),
        new Ac3Extractor(),
        new Ac4Extractor(),
        new AdtsExtractor(),
        new Mp3Extractor(),
        new AviExtractor(),
        new JpegExtractor(),
        new PngExtractor(),
        new WebpExtractor(),
        new BmpExtractor(),
        new HeifExtractor(),
        new AvifExtractor(),
    };
  }
}
