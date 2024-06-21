/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapFromFocusedGlFramebuffer;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createGlTextureFromBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.maybeSaveTestBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.media3.test.utils.TestUtil.PSNR_THRESHOLD;
import static androidx.media3.test.utils.TestUtil.assertBitmapsAreSimilar;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import androidx.media3.common.C;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/**
 * Tests for {@link LanczosResample}.
 *
 * <p>Expected images are generated by ffmpeg, using {@code -vf scale=WxH:flags=lanczos:param0=3}.
 */
@RunWith(AndroidJUnit4.class)
public class LanczosResampleTest {
  @Rule public final TestName testName = new TestName();

  private static final String ORIGINAL_JPG_ASSET_PATH = "media/jpeg/ultraHDR.jpg";
  private static final String SMALLER_JPG_ASSET_PATH = "media/jpeg/london.jpg";
  private static final String DOWNSCALED_6X_PNG_ASSET_PATH =
      "test-generated-goldens/LanczosResampleTest/ultraHDR_512x680.png";
  private static final String UPSCALED_3X_PNG_ASSET_PATH =
      "test-generated-goldens/LanczosResampleTest/london_3060x2304.jpg";

  private final Context context = getApplicationContext();

  private String testId;
  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull EGLContext eglContext;
  private @MonotonicNonNull EGLSurface placeholderEglSurface;
  private @MonotonicNonNull GlShaderProgram lanczosShaderProgram;

  @Before
  public void createGlObjects() throws Exception {
    eglDisplay = GlUtil.getDefaultEglDisplay();
    eglContext = GlUtil.createEglContext(eglDisplay);
    placeholderEglSurface = GlUtil.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
  }

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @After
  public void release() throws GlUtil.GlException, VideoFrameProcessingException {
    if (lanczosShaderProgram != null) {
      lanczosShaderProgram.release();
    }
    GlUtil.destroyEglContext(eglDisplay, eglContext);
  }

  @Test
  public void queueInputFrame_with6xDownscale_matchesGoldenFile() throws Exception {
    GlTextureInfo inputTextureInfo = setupInputTexture(ORIGINAL_JPG_ASSET_PATH);
    float scale = 1f / 6;
    Size outputSize =
        new Size((int) (inputTextureInfo.width * scale), (int) (inputTextureInfo.height * scale));
    lanczosShaderProgram =
        LanczosResample.scaleToFit(outputSize.getWidth(), outputSize.getHeight())
            .toGlShaderProgram(context, /* useHdr= */ false);
    setupOutputTexture(outputSize.getWidth(), outputSize.getHeight());
    Bitmap expectedBitmap = readBitmap(DOWNSCALED_6X_PNG_ASSET_PATH);

    lanczosShaderProgram.queueInputFrame(
        new DefaultGlObjectsProvider(eglContext), inputTextureInfo, /* presentationTimeUs= */ 0);
    Bitmap actualBitmap =
        createArgb8888BitmapFromFocusedGlFramebuffer(outputSize.getWidth(), outputSize.getHeight());

    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);
    assertBitmapsAreSimilar(expectedBitmap, actualBitmap, PSNR_THRESHOLD);
  }

  @Test
  public void queueInputFrame_with3xUpscale_matchesGoldenFile() throws Exception {
    GlTextureInfo inputTextureInfo = setupInputTexture(SMALLER_JPG_ASSET_PATH);
    float scale = 3;
    Size outputSize =
        new Size((int) (inputTextureInfo.width * scale), (int) (inputTextureInfo.height * scale));
    lanczosShaderProgram =
        LanczosResample.scaleToFit(outputSize.getWidth(), outputSize.getHeight())
            .toGlShaderProgram(context, /* useHdr= */ false);
    setupOutputTexture(outputSize.getWidth(), outputSize.getHeight());
    Bitmap expectedBitmap = readBitmap(UPSCALED_3X_PNG_ASSET_PATH);

    lanczosShaderProgram.queueInputFrame(
        new DefaultGlObjectsProvider(eglContext), inputTextureInfo, /* presentationTimeUs= */ 0);
    Bitmap actualBitmap =
        createArgb8888BitmapFromFocusedGlFramebuffer(outputSize.getWidth(), outputSize.getHeight());

    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);
    assertBitmapsAreSimilar(expectedBitmap, actualBitmap, PSNR_THRESHOLD);
  }

  @Test
  public void isNoOp_whenSizeDoesntChange_returnsTrue() {
    LanczosResample lanczosResample = LanczosResample.scaleToFit(720, 1280);

    assertThat(lanczosResample.isNoOp(720, 1280)).isTrue();
  }

  @Test
  public void isNoOp_forSmallScalingFactors_returnsTrue() {
    LanczosResample lanczosResample = LanczosResample.scaleToFit(1920, 1072);

    assertThat(lanczosResample.isNoOp(1920, 1080)).isTrue();
  }

  @Test
  public void isNoOp_forLargeScalingFactors_returnsTrue() {
    LanczosResample lanczosResample = LanczosResample.scaleToFit(1920, 1068);

    assertThat(lanczosResample.isNoOp(1920, 1080)).isFalse();
  }

  private static GlTextureInfo setupInputTexture(String path) throws Exception {
    Bitmap inputBitmap = readBitmap(path);
    return new GlTextureInfo(
        createGlTextureFromBitmap(inputBitmap),
        /* fboId= */ C.INDEX_UNSET,
        /* rboId= */ C.INDEX_UNSET,
        inputBitmap.getWidth(),
        inputBitmap.getHeight());
  }

  private void setupOutputTexture(int outputWidth, int outputHeight) throws Exception {
    int outputTexId =
        GlUtil.createTexture(
            outputWidth, outputHeight, /* useHighPrecisionColorComponents= */ false);
    int frameBuffer = GlUtil.createFboForTexture(outputTexId);
    GlUtil.focusFramebuffer(
        checkNotNull(eglDisplay),
        checkNotNull(eglContext),
        checkNotNull(placeholderEglSurface),
        frameBuffer,
        outputWidth,
        outputHeight);
  }
}
