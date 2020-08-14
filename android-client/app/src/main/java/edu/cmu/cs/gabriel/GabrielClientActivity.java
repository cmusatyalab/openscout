// Copyright 2020 Carnegie Mellon University
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package edu.cmu.cs.gabriel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.net.URI;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.location.Location;
import android.location.LocationManager;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;

import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.MediaController;
import android.widget.Toast;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.DisplayMetrics;
import android.content.Context;
import android.os.Environment;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.media.MediaActionSound;
import android.text.method.ScrollingMovementMethod;
import android.text.method.TextKeyListener;

import androidx.core.content.ContextCompat;

import edu.cmu.cs.gabriel.network.EngineInput;
import edu.cmu.cs.gabriel.network.FrameSupplier;
import edu.cmu.cs.gabriel.network.NetworkProtocol;
import edu.cmu.cs.gabriel.network.OpenScoutComm;
import edu.cmu.cs.gabriel.util.Screenshot;
import edu.cmu.cs.openscout.R;


public class GabrielClientActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final String LOG_TAG = "GabrielClientActivity";
    private static final int REQUEST_CODE = 1000;
    private static int DISPLAY_WIDTH = 640;
    private static int DISPLAY_HEIGHT = 480;
    private static int BITRATE = 1*1024*1024;
    private static final int MEDIA_TYPE_IMAGE = 1;
    private static final int MEDIA_TYPE_VIDEO = 2;

    // major components for streaming sensor data and receiving information
    String serverIP = null;

    OpenScoutComm openscoutcomm;

    private boolean isRunning = false;
    private boolean isFirstExperiment = true;

    //private CameraPreview preview = null;
    private Camera mCamera = null;
    private List<int[]> supportingFPS = null;
    private List<Camera.Size> supportingSize = null;
    private boolean isSurfaceReady = false;
    private boolean waitingToStart = false;
    private boolean isPreviewing = false;
    public byte[] reusedBuffer = null;

    private TextureView preview;
    private SurfaceTexture mSurfaceTexture;

    private MediaController mediaController = null;
    private int mScreenDensity;
    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;
    private boolean capturingScreen = false;
    private boolean recordingInitiated = false;
    private String mOutputPath = null;

    private FileWriter controlLogWriter = null;

    // views
    private TextView resultsView = null;
    private Handler fpsHandler = null;
    private Handler timer = null;
    private int cameraId = 0;
    private boolean imageRotate = false;
    private TextView fpsLabel = null;

    private int detections = 0;
    private EngineInput engineInput;
    final private Object engineInputLock = new Object();
    private FrameSupplier frameSupplier = new FrameSupplier(this);

    // Background threads based on
    // https://github.com/googlesamples/android-Camera2Basic/blob/master/Application/src/main/java/com/example/android/camera2basic/Camera2BasicFragment.java#L652
    /**
     * Thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread backgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler backgroundHandler;

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("ImageUpload");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        backgroundHandler.post(imageUpload);
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        openscoutcomm.stop();
        backgroundThread.quitSafely();

        // Will stop backgroundThread.join() from blocking if backgroundThread is currently blocked
        // on a call to wait()
        backgroundThread.interrupt();

        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            Log.e(LOG_TAG, "Problem stopping background thread", e);
        }
    }

    public EngineInput getEngineInput() {
        EngineInput engineInput;
        synchronized (this.engineInputLock) {
            try {
                while (isRunning && this.engineInput == null) {
                    engineInputLock.wait();
                }

                engineInput = this.engineInput;
                this.engineInput = null;  // Prevent sending the same frame again
            } catch (/* InterruptedException */ Exception e) {
                Log.e(LOG_TAG, "Error waiting for engine input", e);
                return null;
            }
        }
        return engineInput;
    }

    private Runnable imageUpload = new Runnable() {
        @Override
        public void run() {
            openscoutcomm.sendSupplier(GabrielClientActivity.this.frameSupplier);

            if (isRunning) {
                backgroundHandler.post(imageUpload);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(LOG_TAG, "++onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED+
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON+
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        resultsView = (TextView) findViewById(R.id.resultsText);
        resultsView.setFocusableInTouchMode(false);
        resultsView.clearFocus();
        resultsView.setMovementMethod(new ScrollingMovementMethod());
        fpsLabel = (TextView) findViewById(R.id.fpsLabel);

        final ImageView toggleResultsButton = findViewById(R.id.imgResultsToggle);
        toggleResultsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(resultsView.getVisibility() == View.GONE ) {
                    findViewById(R.id.resultsText).setVisibility(View.VISIBLE);
                    toggleResultsButton.setImageDrawable(ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_results_off));
                 } else {
                    findViewById(R.id.resultsText).setVisibility(View.GONE);
                    toggleResultsButton.setImageDrawable(ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_results_on));
                }
                toggleResultsButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }
        });

        if(Const.SHOW_RECORDER) {
            final ImageView recButton = findViewById(R.id.imgRecord);
            recButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(capturingScreen) {
                        ((ImageView) findViewById(R.id.imgRecord)).setImageDrawable(getResources().getDrawable(R.drawable.ic_baseline_videocam_24px));
                        stopRecording();
                        MediaActionSound m = new MediaActionSound();
                        m.play(MediaActionSound.STOP_VIDEO_RECORDING);
                    } else {
                        recordingInitiated = true;
                        MediaActionSound m = new MediaActionSound();
                        m.play(MediaActionSound.START_VIDEO_RECORDING);
                        ((ImageView) findViewById(R.id.imgRecord)).setImageDrawable(getResources().getDrawable(R.drawable.ic_baseline_videocam_off_24px));
                        initRecorder();
                        shareScreen();
                    }
                    recButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                }
            });
            final ImageView screenshotButton = (ImageView) findViewById(R.id.imgScreenshot);
            screenshotButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Bitmap b = Screenshot.takescreenshotOfRootView(preview);
                    storeScreenshot(b,getOutputMediaFile(MEDIA_TYPE_IMAGE).getPath());
                    screenshotButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);

                    }

            });


        } else {
            findViewById(R.id.imgRecord).setVisibility(View.GONE);
            findViewById(R.id.imgScreenshot).setVisibility(View.GONE);
        }


        final ImageView trainingButton = (ImageView) findViewById(R.id.imgTrain);
        trainingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Context context = v.getContext();
                AlertDialog.Builder builder = new AlertDialog.Builder(context, AlertDialog.THEME_HOLO_DARK);
                LayoutInflater inflater = getLayoutInflater();

                builder.setMessage(R.string.training_name_prompt)
                        .setTitle(context.getString(R.string.training_dialog_title));
                final EditText input = new EditText(context);
               // input.setKeyListener(TextKeyListener.getInstance("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_"));
                input.setInputType(InputType.TYPE_TEXT_VARIATION_FILTER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT);
                input.setLayoutParams(lp);
                builder.setView(input);

                builder.setPositiveButton(R.string.train, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {

                        Const.IS_TRAINING = true;
                        Log.i(LOG_TAG, input.getText().toString());
                        Const.TRAINING_NAME = input.getText().toString();
                        Toast.makeText(context, context.getString(R.string.training_in_progress, Const.TRAINING_NAME),
                                Toast.LENGTH_SHORT).show();
                        timer = new Handler();
                        timer.postDelayed(training_timer, Const.TRAIN_TIME);
                    }
                });
                builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        Toast.makeText(context, String.format("%s", context.getString(R.string.training_canceled)),
                                Toast.LENGTH_SHORT).show();
                    }
                });
                AlertDialog trainDialog = builder.create();
                trainDialog.show();
                trainingButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);

            }

        });

        final ImageView camButton = (ImageView) findViewById(R.id.imgSwitchCam);
        final ImageView rotateButton = (ImageView) findViewById(R.id.imgRotate);
        camButton.setVisibility(View.VISIBLE);
        camButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                camButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                mCamera.setPreviewCallback(null);
                CameraClose();
                if (cameraId > 0) {
                    camButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_baseline_camera_front_24px));
                    Const.USING_FRONT_CAMERA = false;
                    cameraId = 0;
                    rotateButton.setVisibility(View.GONE);
                } else {
                    camButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_baseline_camera_rear_24px));
                    cameraId = findFrontFacingCamera();
                    Const.USING_FRONT_CAMERA = true;
                    rotateButton.setVisibility(View.VISIBLE);
                }
                mSurfaceTexture = preview.getSurfaceTexture();
                mCamera = checkCamera();
                CameraStart();
                mCamera.setPreviewCallbackWithBuffer(previewCallback);
                reusedBuffer = new byte[1920 * 1080 * 3 / 2]; // 1.5 bytes per pixel
                mCamera.addCallbackBuffer(reusedBuffer);

            }
        });

        rotateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                imageRotate = !imageRotate;
                Const.FRONT_ROTATION = !Const.FRONT_ROTATION;
                preview.setRotation(180 - preview.getRotation());
                rotateButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }
        });


        if(Const.SHOW_FPS) {
            findViewById(R.id.fpsLabel).setVisibility(View.VISIBLE);
            fpsHandler = new Handler();
            fpsHandler.postDelayed(fpsCalculator, 1000);
        }

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mScreenDensity = metrics.densityDpi;

        mMediaRecorder = new MediaRecorder();

        mProjectionManager = (MediaProjectionManager) getSystemService
                (Context.MEDIA_PROJECTION_SERVICE);

    }

    private void storeScreenshot(Bitmap bitmap, String path) {
        OutputStream out = null;
        File imageFile = new File(path);

        try {
            MediaActionSound m = new MediaActionSound();
            m.play(MediaActionSound.SHUTTER_CLICK);
            out = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,Uri.fromFile(imageFile)));
            Toast.makeText(this, getString(R.string.screenshot_taken, path), Toast.LENGTH_LONG).show();
            out.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "IOException when attempting to store screenshot: " + e.getMessage());
        }
    }

    private int findFrontFacingCamera() {
        int cameraId = -1;
        // Search for the front facing camera
        int numberOfCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    Log.d(LOG_TAG, "Front facing camera found");
                    cameraId = i;
                    break;
                }
        }
        return cameraId;
    }

    @Override
    protected void onResume() {
        Log.v(LOG_TAG, "++onResume");
        super.onResume();

        // dim the screen
//        WindowManager.LayoutParams lp = getWindow().getAttributes();
//        lp.dimAmount = 1.0f;
//        lp.screenBrightness = 0.01f;
//        getWindow().setAttributes(lp);

        initOnce();
        if (Const.IS_EXPERIMENT) { // experiment mode
            runExperiments();
        } else { // demo mode
            serverIP = Const.SERVER_IP;
            initPerRun(serverIP);
        }
    }



    @Override
    protected void onPause() {
        Log.v(LOG_TAG, "++onPause");
        if(capturingScreen)
            stopRecording();

        this.terminate();

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Log.v(LOG_TAG, "++onDestroy");
        if(capturingScreen)
            stopRecording();

        super.onDestroy();
    }

    /**
     * Creates a media file in the {@code Environment.DIRECTORY_PICTURES} directory. The directory
     * is persistent and available to other applications like gallery.
     *
     * @param type Media type. Can be video or image.
     * @return A file object pointing to the newly created file.
     */
    public  static File getOutputMediaFile(int type){
        // To be safe, you should check that the SDCard is mounted
        // using Environment.getExternalStorageState() before doing this.
        if (!Environment.getExternalStorageState().equalsIgnoreCase(Environment.MEDIA_MOUNTED)) {
            return  null;
        }

        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "OpenScout");
        // This location works best if you want the created images to be shared
        // between applications and persist after your app has been uninstalled.

        // Create the storage directory if it does not exist
        if (! mediaStorageDir.exists()){
            if (! mediaStorageDir.mkdirs()) {
                Log.d("CameraSample", "failed to create directory");
                return null;
            }
        }

        // Create a media file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File mediaFile;
        if (type == MEDIA_TYPE_IMAGE){
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
                    "IMG_"+ timeStamp + ".jpg");
        } else if(type == MEDIA_TYPE_VIDEO) {
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
                    "VID_"+ timeStamp + ".mp4");
        } else {
            return null;
        }

        return mediaFile;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) {
            Log.e(LOG_TAG, "Unknown request code: " + requestCode);
            return;
        }
        if (resultCode != RESULT_OK) {
            Toast.makeText(this,
                    "Screen Cast Permission Denied", Toast.LENGTH_SHORT).show();

            return;
        }

        mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);

        mVirtualDisplay = createVirtualDisplay();
        mMediaRecorder.start();
        capturingScreen = true;

    }

    private void shareScreen() {
        if (mMediaProjection == null) {
            startActivityForResult(mProjectionManager.createScreenCaptureIntent(), REQUEST_CODE);
            return;
        }
    }

    private VirtualDisplay createVirtualDisplay() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mScreenDensity = metrics.densityDpi;
        return mMediaProjection.createVirtualDisplay("MainActivity",
                DISPLAY_WIDTH, DISPLAY_HEIGHT, mScreenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mMediaRecorder.getSurface(), null /*Callbacks*/, null
                /*Handler*/);
    }

    private void initRecorder() {
        try {
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mOutputPath = getOutputMediaFile(MEDIA_TYPE_VIDEO).getPath();
            mMediaRecorder.setOutputFile(mOutputPath);
            mMediaRecorder.setVideoSize(DISPLAY_WIDTH, DISPLAY_HEIGHT);
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setVideoEncodingBitRate(BITRATE);
            mMediaRecorder.setVideoFrameRate(24);
            mMediaRecorder.prepare();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopRecording() {
        mMediaRecorder.stop();
        mMediaRecorder.reset();
        Log.v(LOG_TAG, "Recording Stopped");
        Toast.makeText(this,
                getString(R.string.recording_complete, mOutputPath), Toast.LENGTH_LONG).show();
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,Uri.fromFile(new File(mOutputPath))));
        mMediaProjection = null;
        stopScreenSharing();
    }

    private void stopScreenSharing() {
        if (mVirtualDisplay == null) {
            return;
        }
        mVirtualDisplay.release();
        //mMediaRecorder.release(); //If used: mMediaRecorder object cannot
        // be reused again
        destroyMediaProjection();
        capturingScreen = false;
    }

    private void destroyMediaProjection() {
        if (mMediaProjection != null) {

            mMediaProjection.stop();
            mMediaProjection = null;
        }
        Log.i(LOG_TAG, "MediaProjection Stopped");
    }

    /**
     * Does initialization for the entire application. Called only once even for multiple experiments.
     */
    private void initOnce() {
        Log.v(LOG_TAG, "++initOnce");

        if (Const.SENSOR_VIDEO) {
            preview = (TextureView) findViewById(R.id.camera_preview);

            mSurfaceTexture = preview.getSurfaceTexture();
            preview.setSurfaceTextureListener(this);
            mCamera = checkCamera();
            CameraStart();
            mCamera.setPreviewCallbackWithBuffer(previewCallback);
            reusedBuffer = new byte[1920 * 1080 * 3 / 2]; // 1.5 bytes per pixel
            mCamera.addCallbackBuffer(reusedBuffer);
        }

        Const.ROOT_DIR.mkdirs();
        Const.EXP_DIR.mkdirs();


        // Media controller
        if (mediaController == null) {
            mediaController = new MediaController(this);
        }

        isRunning = true;
    }

    /**
     * Does initialization before each run (connecting to a specific server).
     * Called once before each experiment.
     */
    private void initPerRun(String serverIP) {
        Log.v(LOG_TAG, "++initPerRun");

        if (Const.IS_EXPERIMENT) {
            if (isFirstExperiment) {
                isFirstExperiment = false;
            } else {
                try {
                    Thread.sleep(20 * 1000);
                } catch (InterruptedException e) {}
                // wait a while for ping to finish...
                try {
                    Thread.sleep(5 * 1000);
                } catch (InterruptedException e) {}
            }
        }

        if (serverIP == null) return;

        if (Const.IS_EXPERIMENT) {
            try {
                controlLogWriter = new FileWriter(Const.CONTROL_LOG_FILE);
            } catch (IOException e) {
                Log.e(LOG_TAG, "Control log file cannot be properly opened", e);
            }
        }

        this.setupComm();
        this.startBackgroundThread();
    }

    int getPort() {
        int port = URI.create(this.serverIP).getPort();
        if (port == -1) {
            return Const.PORT;
        }
        return port;
    }

    void setupComm() {
        int port = getPort();
        this.openscoutcomm = OpenScoutComm.createOpenScoutComm(
                this.serverIP, port, this, this.returnMsgHandler, Const.TOKEN_LIMIT);
    }

    void setOpenScoutComm(OpenScoutComm openscoutcomm) {
        this.openscoutcomm = openscoutcomm;
    }
    /**
     * Runs a set of experiments with different server IPs.
     * IP list is defined in the Const file.
     */
    private void runExperiments() {
        final Timer startTimer = new Timer();
        TimerTask autoStart = new TimerTask() {
            int ipIndex = 0;
            @Override
            public void run() {
                GabrielClientActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // end condition
                        if (ipIndex == Const.SERVER_IP_LIST.length) {
                            Log.d(LOG_TAG, "Finish all experiments");

                            initPerRun(null); // just to get another set of ping results

                            startTimer.cancel();
                            terminate();
                            return;
                        }

                        // make a new configuration
                        serverIP = Const.SERVER_IP_LIST[ipIndex];

                        // run the experiment
                        initPerRun(serverIP);

                        // move to the next experiment
                        ipIndex++;
                    }
                });
            }
        };

        // run 5 minutes for each experiment
        startTimer.schedule(autoStart, 1000, 5*60*1000);
    }

    private byte[] yuvToJPEGBytes(byte[] yuvFrameBytes, Camera.Parameters parameters){
        Size cameraImageSize = parameters.getPreviewSize();
        YuvImage image = new YuvImage(yuvFrameBytes,
                parameters.getPreviewFormat(), cameraImageSize.width,
                cameraImageSize.height, null);

        ByteArrayOutputStream tmpBuffer = new ByteArrayOutputStream();
        // chooses quality 67 and it roughly matches quality 5 in avconv
        image.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()),
                67, tmpBuffer);
        return tmpBuffer.toByteArray();
    }

    private double[] getGPS() {
        LocationManager lm = (LocationManager)getSystemService(LOCATION_SERVICE);
        double[] gps = new double[2];
        gps[0] = 0.0;
        gps[1] = 0.0;
        try {
            Location loc =  lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc != null) {
                gps[0] = loc.getLatitude();
                gps[1] = loc.getLongitude();
            } else {
                loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc != null) {
                    gps[0] = loc.getLatitude();
                    gps[1] = loc.getLongitude();
                }
            }
            Log.d(LOG_TAG, "Lat: " +  gps[0] + " Long: " + gps[1]);
        } catch(SecurityException e)  {
            Log.e(LOG_TAG, e.getMessage());
        }

        return gps;
    }

    private PreviewCallback previewCallback = new PreviewCallback() {
        // called whenever a new frame is captured
        public void onPreviewFrame(byte[] frame, Camera mCamera) {
            if (isRunning) {
                Camera.Parameters parameters = mCamera.getParameters();

                 if (GabrielClientActivity.this.openscoutcomm != null) {
                    synchronized (GabrielClientActivity.this.engineInputLock) {
                        GabrielClientActivity.this.engineInput = new EngineInput(frame, parameters, getGPS());
                        GabrielClientActivity.this.engineInputLock.notify();
                    }
                }

            }
            mCamera.addCallbackBuffer(frame);
        }
    };

    private Runnable training_timer = new Runnable() {
        @Override
        public void run() {
            Const.IS_TRAINING = false;
            Context c = getApplicationContext();
            Toast.makeText(c, c.getString(R.string.training_succeeded), Toast.LENGTH_SHORT).show();
        }
    };

    private Runnable fpsCalculator = new Runnable() {

        @Override
        public void run() {
            if(true){ //if(Const.SHOW_FPS) {
                if (fpsLabel.getVisibility() == View.INVISIBLE) {
                    fpsLabel.setVisibility(View.VISIBLE);

                }
                String msg= "Detections: " + detections;
                fpsLabel.setText( msg );
            }
            fpsHandler.postDelayed(this, 1000);
        }
    };

    /**
     * Handles messages passed from streaming threads and result receiving threads.
     */
    Handler returnMsgHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == NetworkProtocol.NETWORK_RET_FAILED) {
                //terminate();
                if (!recordingInitiated) {  //suppress this error when screen recording as we have to temporarily leave this activity causing a network disruption
                    AlertDialog.Builder builder = new AlertDialog.Builder(GabrielClientActivity.this, AlertDialog.THEME_HOLO_DARK);
                    builder.setMessage(msg.getData().getString("message"))
                            .setTitle(R.string.connection_error)
                            .setNegativeButton(R.string.back_button, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            GabrielClientActivity.this.finish();
                                        }
                                    }
                            )
                            .setCancelable(false);

                    AlertDialog dialog = builder.create();
                    dialog.show();
                }

            }
            if (msg.what ==  NetworkProtocol.NETWORK_RET_TEXT) {
                String result = (String) msg.obj;
                resultsView = (TextView) findViewById(R.id.resultsText);
                String prev = resultsView.getText().toString();
                StringBuilder sb = new StringBuilder();
                sb.append(result);
                sb.append("\n");
                sb.append(prev);
                detections++;
                resultsView.setText(sb.toString());
            }
            if (msg.what == NetworkProtocol.NETWORK_RET_IMAGE) {
                Bitmap feedbackImg = (Bitmap) msg.obj;

            }
        }
    };

    /**
     * Terminates all services.
     */
    private void terminate() {
        Log.v(LOG_TAG, "++terminate");

        isRunning = false;

        // Allow this.backgroundHandler to return if it is currently waiting on this.engineInputLock
        synchronized (this.engineInputLock) {
            this.engineInputLock.notify();
        }

        if (this.backgroundThread != null) {
            this.stopBackgroundThread();
        }

        if (this.openscoutcomm != null) {
            this.openscoutcomm.stop();
            this.openscoutcomm = null;
        }
        if (preview != null) {
            mCamera.setPreviewCallback(null);
            CameraClose();
            reusedBuffer = null;
            preview = null;
            mCamera = null;
        }

        if (Const.IS_EXPERIMENT) {
            try {
                controlLogWriter.close();
            } catch (IOException e) {
                Log.e(LOG_TAG, "Error in closing control log file");
            }
        }
    }

    /**************** Camera Preview ***********************/

    public Camera checkCamera() {
        Log.v(LOG_TAG , "++checkCamera");
        if (mCamera == null) {
            Log.v(LOG_TAG , "!!!!!CAMERA "+cameraId+ " START!!!!");
            mCamera = Camera.open(cameraId);
        }
        return mCamera;
    }

    public void CameraStart() {
        Log.v(LOG_TAG , "++start");
        if (mCamera == null) {
            mCamera = Camera.open(cameraId);
        }
        if (isSurfaceReady) {
            try {
                mCamera.setPreviewTexture(mSurfaceTexture);
            } catch (IOException exception) {
                Log.e(LOG_TAG, "Error in setting camera holder: " + exception.getMessage());
                CameraClose();
            }

            updateCameraConfigurations(Const.CAPTURE_FPS, Const.IMAGE_WIDTH, Const.IMAGE_HEIGHT);

        } else {
            waitingToStart = true;
        }
    }

    public void CameraClose() {
        Log.v(LOG_TAG , "++close");
        if (mCamera != null) {
            mCamera.stopPreview();
            isPreviewing = false;
            mCamera.release();
            mCamera = null;
        }
    }

    public void changeConfiguration(int[] range, Size imageSize) {
        Log.v(LOG_TAG , "++changeConfiguration");
        Camera.Parameters parameters = mCamera.getParameters();
        if (range != null){
            Log.i("Config", "frame rate configuration : " + range[0] + "," + range[1]);
            parameters.setPreviewFpsRange(range[0], range[1]);
        }
        if (imageSize != null){
            Log.i("Config", "image size configuration : " + imageSize.width + "," + imageSize.height);
            parameters.setPreviewSize(imageSize.width, imageSize.height);
        }
        //parameters.setPreviewFormat(ImageFormat.JPEG);

        mCamera.setParameters(parameters);
    }

    public void updateCameraConfigurations(int targetFps, int imgWidth, int imgHeight) {
        Log.d(LOG_TAG, "updateCameraConfigurations");
        if (mCamera != null) {
            if (targetFps != -1) {
                Const.CAPTURE_FPS = targetFps;
            }
            if (imgWidth != -1) {
                Const.IMAGE_WIDTH = imgWidth;
                Const.IMAGE_HEIGHT = imgHeight;
            }

            if (isPreviewing)
                mCamera.stopPreview();

            // set fps to capture
            int index = 0, fpsDiff = Integer.MAX_VALUE;
            for (int i = 0; i < this.supportingFPS.size(); i++){
                int[] frameRate = this.supportingFPS.get(i);
                int diff = Math.abs(Const.CAPTURE_FPS * 1000 - frameRate[0]) + Math.abs(Const.CAPTURE_FPS * 1000 - frameRate[1]);
                if (diff < fpsDiff){
                    fpsDiff = diff;
                    index = i;
                }
            }
            int[] targetRange = this.supportingFPS.get(index);

            // set resolution
            index = 0;
            int sizeDiff = Integer.MAX_VALUE;
            for (int i = 0; i < this.supportingSize.size(); i++){
                Camera.Size size = this.supportingSize.get(i);
                int diff = Math.abs(size.width - Const.IMAGE_WIDTH) + Math.abs(size.height - Const.IMAGE_HEIGHT);
                if (diff < sizeDiff){
                    sizeDiff = diff;
                    index = i;
                }
            }
            Camera.Size target_size = this.supportingSize.get(index);

            changeConfiguration(targetRange, target_size);

            mCamera.startPreview();
            isPreviewing = true;
        }
    }

    /**************** End of Camera Preview ****************/

    /**************** SurfaceTexture Listener ***********************/

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {

        /*
        mCamera = Camera.open(cameraId);

        Camera.Size previewSize = mCamera.getParameters().getPreviewSize();
        mTextureView.setLayoutParams(new FrameLayout.LayoutParams(
                previewSize.width, previewSize.height, Gravity.CENTER));

        try {
            mCamera.setPreviewTexture(surface);
        } catch (IOException t) {
        }

        mCamera.startPreview();
        */
        isSurfaceReady = true;
        if (mCamera == null) {
            mCamera = Camera.open(cameraId);
        }
        if (mCamera != null) {
            // get fps to capture
            Camera.Parameters parameters = mCamera.getParameters();
            this.supportingFPS = parameters.getSupportedPreviewFpsRange();
            for (int[] range: this.supportingFPS) {
                Log.i(LOG_TAG, "available fps ranges:" + range[0] + ", " + range[1]);
            }

            // get resolution
            this.supportingSize = parameters.getSupportedPreviewSizes();
            for (Camera.Size size: this.supportingSize) {
                Log.i(LOG_TAG, "available sizes:" + size.width + ", " + size.height);
            }

            if (waitingToStart) {
                waitingToStart = false;
                try {
                    mCamera.setPreviewTexture(surface);
                } catch (IOException exception) {
                    Log.e(LOG_TAG, "Error in setting camera holder: " + exception.getMessage());
                    CameraClose();
                }
                updateCameraConfigurations(Const.CAPTURE_FPS, Const.IMAGE_WIDTH, Const.IMAGE_HEIGHT);
            }
        } else {
            Log.w(LOG_TAG, "Camera is not open");
        }



    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // Ignored, the Camera does all the work for us
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        isSurfaceReady = false;
        CameraClose();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Update your view here!
    }

    /****************End of SurfaceTexture Listener ***********************/


}
