/*
 *  Copyright 2016 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import org.webrtc.CameraEnumerationAndroid.CaptureFormat;
import org.webrtc.Metrics.Histogram;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.util.Range;
import android.view.Surface;
import android.view.WindowManager;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@TargetApi(21)
public class Camera2Session implements CameraSession {
  private static final String TAG = "Camera2Session";

  private static final Histogram camera2StartTimeMsHistogram =
      Histogram.createCounts("WebRTC.Android.Camera2.StartTimeMs", 1, 10000, 50);
  private static final Histogram camera2StopTimeMsHistogram =
      Histogram.createCounts("WebRTC.Android.Camera2.StopTimeMs", 1, 10000, 50);

  private static enum SessionState { RUNNING, STOPPED };

  private final Handler cameraThreadHandler;
  private final CameraManager cameraManager;
  private final CreateSessionCallback callback;
  private final CameraVideoCapturer.CameraEventsHandler eventsHandler;
  private final Context applicationContext;
  private final CameraVideoCapturer.CapturerObserver capturerObserver;
  private final SurfaceTextureHelper surfaceTextureHelper;
  private final String cameraId;
  private final int width;
  private final int height;
  private final int framerate;

  // Initialized at start
  private CameraCharacteristics cameraCharacteristics;
  private int cameraOrientation;
  private boolean isCameraFrontFacing;
  private int fpsUnitFactor;
  private CaptureFormat captureFormat;

  // Initialized when camera opens
  private CameraDevice cameraDevice;
  private Surface surface;

  // Initialized when capture session is created
  private CameraCaptureSession captureSession;
  private CameraVideoCapturer.CameraStatistics cameraStatistics;

  // State
  private SessionState state = SessionState.RUNNING;
  private boolean firstFrameReported = false;

  // Used only for stats. Only used on the camera thread.
  private final long constructionTimeNs; // Construction time of this class.

  private class CameraStateCallback extends CameraDevice.StateCallback {
    private String getErrorDescription(int errorCode) {
      switch (errorCode) {
        case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
          return "Camera device has encountered a fatal error.";
        case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
          return "Camera device could not be opened due to a device policy.";
        case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
          return "Camera device is in use already.";
        case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
          return "Camera service has encountered a fatal error.";
        case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
          return "Camera device could not be opened because"
              + " there are too many other open camera devices.";
        default:
          return "Unknown camera error: " + errorCode;
      }
    }

    @Override
    public void onDisconnected(CameraDevice camera) {
      checkIsOnCameraThread();
      reportError("Camera disconnected.");
    }

    @Override
    public void onError(CameraDevice camera, int errorCode) {
      checkIsOnCameraThread();
      reportError(getErrorDescription(errorCode));
    }

    @Override
    public void onOpened(CameraDevice camera) {
      checkIsOnCameraThread();

      Logging.d(TAG, "Camera opened.");
      cameraDevice = camera;

      final SurfaceTexture surfaceTexture = surfaceTextureHelper.getSurfaceTexture();
      surfaceTexture.setDefaultBufferSize(captureFormat.width, captureFormat.height);
      surface = new Surface(surfaceTexture);
      try {
        camera.createCaptureSession(
            Arrays.asList(surface), new CaptureSessionCallback(), cameraThreadHandler);
      } catch (CameraAccessException e) {
        reportError("Failed to create capture session. " + e);
        return;
      }
    }

    @Override
    public void onClosed(CameraDevice camera) {
      checkIsOnCameraThread();

      Logging.d(TAG, "Camera device closed.");
      eventsHandler.onCameraClosed();
    }
  }

  private class CaptureSessionCallback extends CameraCaptureSession.StateCallback {
    @Override
    public void onConfigureFailed(CameraCaptureSession session) {
      checkIsOnCameraThread();
      session.close();
      reportError("Failed to configure capture session.");
    }

    @Override
    public void onConfigured(CameraCaptureSession session) {
      checkIsOnCameraThread();
      Logging.d(TAG, "Camera capture session configured.");
      captureSession = session;
      try {
        /*
         * The viable options for video capture requests are:
         * TEMPLATE_PREVIEW: High frame rate is given priority over the highest-quality
         *   post-processing.
         * TEMPLATE_RECORD: Stable frame rate is used, and post-processing is set for recording
         *   quality.
         */
        final CaptureRequest.Builder captureRequestBuilder =
            cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        // Set auto exposure fps range.
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<Integer>(
            captureFormat.framerate.min / fpsUnitFactor,
            captureFormat.framerate.max / fpsUnitFactor));
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_ON);
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);

        captureRequestBuilder.addTarget(surface);
        session.setRepeatingRequest(
            captureRequestBuilder.build(), new CameraCaptureCallback(), cameraThreadHandler);
      } catch (CameraAccessException e) {
        reportError("Failed to start capture request. " + e);
        return;
      }

      surfaceTextureHelper.startListening(
          new SurfaceTextureHelper.OnTextureFrameAvailableListener() {
            @Override
            public void onTextureFrameAvailable(
                int oesTextureId, float[] transformMatrix, long timestampNs) {
              checkIsOnCameraThread();

              if (state != SessionState.RUNNING) {
                Logging.d(TAG, "Texture frame captured but camera is no longer running.");
                surfaceTextureHelper.returnTextureFrame();
                return;
              }

              if (!firstFrameReported) {
                eventsHandler.onFirstFrameAvailable();
                firstFrameReported = true;
                final int startTimeMs =
                    (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
                camera2StartTimeMsHistogram.addSample(startTimeMs);
              }

              int rotation = getFrameOrientation();
              if (isCameraFrontFacing) {
                // Undo the mirror that the OS "helps" us with.
                // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
                transformMatrix = RendererCommon.multiplyMatrices(
                    transformMatrix, RendererCommon.horizontalFlipMatrix());
              }

              // Undo camera orientation - we report it as rotation instead.
              transformMatrix = RendererCommon.rotateTextureMatrix(
                  transformMatrix, -cameraOrientation);

              cameraStatistics.addFrame();
              capturerObserver.onTextureFrameCaptured(captureFormat.width, captureFormat.height,
                  oesTextureId, transformMatrix, rotation, timestampNs);
            }
          });
      capturerObserver.onCapturerStarted(true /* success */);
      cameraStatistics = new CameraVideoCapturer.CameraStatistics(
          surfaceTextureHelper, eventsHandler);
      Logging.d(TAG, "Camera device successfully started.");
      callback.onDone(Camera2Session.this);
    }
  }

  private class CameraCaptureCallback extends CameraCaptureSession.CaptureCallback {
    @Override
    public void onCaptureFailed(
        CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
      Logging.d(TAG, "Capture failed: " + failure);
    }
  }

  public static void create(
      CameraManager cameraManager, CreateSessionCallback callback,
      CameraVideoCapturer.CameraEventsHandler eventsHandler, Context applicationContext,
      CameraVideoCapturer.CapturerObserver capturerObserver,
      SurfaceTextureHelper surfaceTextureHelper,
      String cameraId, int width, int height, int framerate) {
    new Camera2Session(
        cameraManager, callback,
        eventsHandler, applicationContext,
        capturerObserver,
        surfaceTextureHelper,
        cameraId, width, height, framerate);
  }

  private Camera2Session(
      CameraManager cameraManager, CreateSessionCallback callback,
      CameraVideoCapturer.CameraEventsHandler eventsHandler, Context applicationContext,
      CameraVideoCapturer.CapturerObserver capturerObserver,
      SurfaceTextureHelper surfaceTextureHelper,
      String cameraId, int width, int height, int framerate) {
    Logging.d(TAG, "Create new camera2 session on camera " + cameraId);

    constructionTimeNs = System.nanoTime();

    this.cameraThreadHandler = new Handler();
    this.cameraManager = cameraManager;
    this.callback = callback;
    this.eventsHandler = eventsHandler;
    this.applicationContext = applicationContext;
    this.capturerObserver = capturerObserver;
    this.surfaceTextureHelper = surfaceTextureHelper;
    this.cameraId = cameraId;
    this.width = width;
    this.height = height;
    this.framerate = framerate;

    start();
  }

  private void start() {
    checkIsOnCameraThread();
    Logging.d(TAG, "start");

    try {
      cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
    } catch (final CameraAccessException e) {
      reportError("getCameraCharacteristics(): " + e.getMessage());
    }
    cameraOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    isCameraFrontFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
        == CameraMetadata.LENS_FACING_FRONT;

    findCaptureFormat();
    openCamera();
  }

  private void findCaptureFormat() {
    checkIsOnCameraThread();

    Range<Integer>[] fpsRanges =
        cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
    fpsUnitFactor = Camera2Enumerator.getFpsUnitFactor(fpsRanges);
    List<CaptureFormat.FramerateRange> framerateRanges =
        Camera2Enumerator.convertFramerates(fpsRanges, fpsUnitFactor);
    List<Size> sizes = Camera2Enumerator.getSupportedSizes(cameraCharacteristics);

    if (framerateRanges.isEmpty() || sizes.isEmpty()) {
      reportError("No supported capture formats.");
    }

    final CaptureFormat.FramerateRange bestFpsRange =
        CameraEnumerationAndroid.getClosestSupportedFramerateRange(
            framerateRanges, framerate);

    final Size bestSize = CameraEnumerationAndroid.getClosestSupportedSize(
        sizes, width, height);

    captureFormat = new CaptureFormat(bestSize.width, bestSize.height, bestFpsRange);
    Logging.d(TAG, "Using capture format: " + captureFormat);
  }

  private void openCamera() {
    checkIsOnCameraThread();

    Logging.d(TAG, "Opening camera " + cameraId);
    eventsHandler.onCameraOpening(cameraId);

    try {
      cameraManager.openCamera(cameraId, new CameraStateCallback(), cameraThreadHandler);
    } catch (CameraAccessException e) {
      reportError("Failed to open camera: " + e);
    }
  }

  @Override
  public void stop() {
    final long stopStartTime = System.nanoTime();
    Logging.d(TAG, "Stop camera2 session on camera " + cameraId);
    if (Thread.currentThread() == cameraThreadHandler.getLooper().getThread()) {
      if (state != SessionState.STOPPED) {
        state = SessionState.STOPPED;
        capturerObserver.onCapturerStopped();
        // Post the stopInternal to return earlier.
        cameraThreadHandler.post(new Runnable() {
          @Override
          public void run() {
            stopInternal();
            final int stopTimeMs =
                (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
            camera2StopTimeMsHistogram.addSample(stopTimeMs);
          }
        });
      }
    } else {
      final CountDownLatch stopLatch = new CountDownLatch(1);

      cameraThreadHandler.post(new Runnable() {
        @Override
        public void run() {
          if (state != SessionState.STOPPED) {
            state = SessionState.STOPPED;
            capturerObserver.onCapturerStopped();
            stopLatch.countDown();
            stopInternal();
            final int stopTimeMs =
                (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
            camera2StopTimeMsHistogram.addSample(stopTimeMs);
          }
        }
      });

      ThreadUtils.awaitUninterruptibly(stopLatch);
    }
  }

  private void stopInternal() {
    Logging.d(TAG, "Stop internal");
    checkIsOnCameraThread();

    surfaceTextureHelper.stopListening();
    cameraStatistics.release();

    captureSession.close();
    captureSession = null;
    surface.release();
    surface = null;
    cameraDevice.close();
    cameraDevice = null;

    Logging.d(TAG, "Stop done");
  }

  private void reportError(String error) {
    checkIsOnCameraThread();
    Logging.e(TAG, "Error: " + error);

    if (captureSession == null) {
      if (cameraDevice != null) {
        cameraDevice.close();
        cameraDevice = null;
      }

      state = SessionState.STOPPED;
      callback.onFailure(error);
      capturerObserver.onCapturerStarted(false /* success */);
    } else {
      eventsHandler.onCameraError(error);
    }
  }

  private int getDeviceOrientation() {
    int orientation = 0;

    WindowManager wm = (WindowManager) applicationContext.getSystemService(
        Context.WINDOW_SERVICE);
    switch(wm.getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_90:
        orientation = 90;
        break;
      case Surface.ROTATION_180:
        orientation = 180;
        break;
      case Surface.ROTATION_270:
        orientation = 270;
        break;
      case Surface.ROTATION_0:
      default:
        orientation = 0;
        break;
    }
    return orientation;
  }

  private int getFrameOrientation() {
    int rotation = getDeviceOrientation();
    if (!isCameraFrontFacing) {
      rotation = 360 - rotation;
    }
    return (cameraOrientation + rotation) % 360;
  }

  private void checkIsOnCameraThread() {
    if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
      throw new IllegalStateException("Wrong thread");
    }
  }
}
