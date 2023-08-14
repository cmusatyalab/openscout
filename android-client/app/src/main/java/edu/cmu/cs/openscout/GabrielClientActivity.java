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

package edu.cmu.cs.openscout;

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
import java.util.function.Consumer;

import android.app.Activity;
import android.content.Intent;
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
import android.text.Spanned;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;

import edu.cmu.cs.gabriel.Const;
import edu.cmu.cs.gabriel.camera.CameraCapture;
import edu.cmu.cs.gabriel.camera.YuvToJPEGConverter;
import edu.cmu.cs.gabriel.client.comm.ServerComm;
import edu.cmu.cs.gabriel.client.results.ErrorType;
import edu.cmu.cs.gabriel.protocol.Protos.InputFrame;
import edu.cmu.cs.gabriel.protocol.Protos.ResultWrapper;
import edu.cmu.cs.gabriel.protocol.Protos.PayloadType;
import edu.cmu.cs.openscout.Protos.Extras;
import edu.cmu.cs.gabriel.util.Screenshot;


public class GabrielClientActivity extends AppCompatActivity {

    private static final String LOG_TAG = "GabrielClientActivity";
    private static final int REQUEST_CODE = 1000;
    private static int DISPLAY_WIDTH = Const.IMAGE_WIDTH;
    private static int DISPLAY_HEIGHT = Const.IMAGE_HEIGHT;
    private static int BITRATE = 1 * 1024 * 1024;
    private static final int MEDIA_TYPE_IMAGE = 1;
    private static final int MEDIA_TYPE_VIDEO = 2;

    // major components for streaming sensor data and receiving information
    String serverIP = null;

    ServerComm openscoutcomm;

    private YuvToJPEGConverter yuvToJPEGConverter;
    private CameraCapture cameraCapture;
    private PreviewView preview;

    private MediaController mediaController = null;
    private int mScreenDensity;
    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;
    private boolean capturingScreen = false;
    private boolean recordingInitiated = false;
    private String mOutputPath = null;

    // views
    private TextView resultsView = null;
    private Handler fpsHandler = null;
    private Handler timer = null;
    private TextView fpsLabel = null;

    private int detections = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(LOG_TAG, "++onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED +
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON +
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        yuvToJPEGConverter = new YuvToJPEGConverter(this);
        cameraCapture = new CameraCapture(this, analyzer, Const.IMAGE_WIDTH, Const.IMAGE_HEIGHT, preview, CameraSelector.DEFAULT_BACK_CAMERA);

        resultsView = (TextView) findViewById(R.id.resultsText);
        resultsView.setFocusableInTouchMode(false);
        resultsView.clearFocus();
        resultsView.setMovementMethod(new ScrollingMovementMethod());
        resultsView.setAlpha(Const.RESULTS_OPACITY);
        fpsLabel = (TextView) findViewById(R.id.fpsLabel);

        final ImageView toggleResultsButton = findViewById(R.id.imgResultsToggle);
        toggleResultsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (resultsView.getVisibility() == View.GONE) {
                    findViewById(R.id.resultsText).setVisibility(View.VISIBLE);
                    toggleResultsButton.setImageDrawable(ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_results_off));
                } else {
                    findViewById(R.id.resultsText).setVisibility(View.GONE);
                    toggleResultsButton.setImageDrawable(ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_results_on));
                }
                toggleResultsButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }
        });

        if (Const.SHOW_RECORDER) {
            final ImageView recButton = findViewById(R.id.imgRecord);
            recButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (capturingScreen) {
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
                    storeScreenshot(b, getOutputMediaFile(MEDIA_TYPE_IMAGE).getPath());
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
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                InputFilter filter = new InputFilter() {
                    @Override
                    public CharSequence filter(CharSequence source, int start, int end,
                                               Spanned dest, int dstart, int dend) {
                        for (int i = start; i < end; i++) {
                            if (!Character.isLetter(source.charAt(i))) {
                                return "";
                            }
                        }
                        return null;
                    }
                };
                input.setFilters(new InputFilter[] { filter });
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
        camButton.setVisibility(View.VISIBLE);
        camButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                camButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                if (Const.USING_FRONT_CAMERA) {
                    camButton.setImageResource(R.drawable.ic_baseline_camera_front_24px);

                    cameraCapture = new CameraCapture(
                            GabrielClientActivity.this, analyzer, Const.IMAGE_WIDTH,
                            Const.IMAGE_HEIGHT, preview, CameraSelector.DEFAULT_BACK_CAMERA);

                    Const.USING_FRONT_CAMERA = false;
                } else {
                    camButton.setImageResource(R.drawable.ic_baseline_camera_rear_24px);

                    cameraCapture = new CameraCapture(
                            GabrielClientActivity.this, analyzer, Const.IMAGE_WIDTH,
                            Const.IMAGE_HEIGHT, preview, CameraSelector.DEFAULT_FRONT_CAMERA);

                    Const.USING_FRONT_CAMERA = true;
                }


            }
        });


        if (Const.SHOW_DETECTIONS) {
            findViewById(R.id.fpsLabel).setVisibility(View.VISIBLE);
            fpsHandler = new Handler();
            fpsHandler.postDelayed(fpsCalculator, 1000);
        }

        if (Const.SHOW_TRAINING_ICON) {
            findViewById(R.id.imgTrain).setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.imgTrain).setVisibility(View.GONE);
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

    @Override
    protected void onResume() {
        Log.v(LOG_TAG, "++onResume");
        super.onResume();

        initOnce();
        Intent intent = getIntent();
        serverIP = intent.getStringExtra("SERVER_IP");
        initPerRun(serverIP);
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
        super.onActivityResult(requestCode, resultCode, data);
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
            preview = findViewById(R.id.camera_preview);

              }

        // Media controller
        if (mediaController == null) {
            mediaController = new MediaController(this);
        }

    }

    /**
     * Does initialization before each run (connecting to a specific server).
     * Called once before each experiment.
     */
    private void initPerRun(String serverIP) {
        Log.v(LOG_TAG, "++initPerRun");
        preview = findViewById(R.id.camera_preview);
        cameraCapture = new CameraCapture(
                this, analyzer, Const.IMAGE_WIDTH, Const.IMAGE_HEIGHT,
                preview, CameraSelector.DEFAULT_BACK_CAMERA);
        if (serverIP == null) return;
        this.setupComm();
    }

    int getPort() {
        int port = URI.create(this.serverIP).getPort();
        if (port == -1) {
            return Const.PORT;
        }
        return port;
    }

    // Based on
    // https://github.com/protocolbuffers/protobuf/blob/master/src/google/protobuf/compiler/java/java_message.cc#L1387
    private static Any pack(Extras engineFields) {
        return Any.newBuilder()
                .setTypeUrl("type.googleapis.com/openscout.Extras")
                .setValue(engineFields.toByteString())
                .build();
    }

    final private ImageAnalysis.Analyzer analyzer = new ImageAnalysis.Analyzer() {
        @Override
        public void analyze(@NonNull ImageProxy image) {
            openscoutcomm.sendSupplier(() -> {
                ByteString jpegByteString = yuvToJPEGConverter.convert(image);

                Extras extras;
                Extras.Builder extrasBuilder = Extras.newBuilder();
                if (Const.IS_TRAINING) {
                    extrasBuilder.setIsTraining(true);
                    extrasBuilder.setName(Const.TRAINING_NAME);
                }
                Protos.Location.Builder lb = Protos.Location.newBuilder();
                double [] coords = getGPS();
                lb.setLatitude(coords[0]);
                lb.setLongitude(coords[1]);
                extrasBuilder.setLocation(lb);
                extrasBuilder.setClientId(Const.UUID);
                extras = extrasBuilder.build();

                return InputFrame.newBuilder()
                        .setPayloadType(PayloadType.IMAGE)
                        .addPayloads(jpegByteString)
                        .setExtras(pack(extras))
                        .build();
            }, Const.SOURCE_NAME, false);

            image.close();
        }
    };

    void setupComm() {
        int port = getPort();

        Consumer<ResultWrapper> consumer = resultWrapper -> {
            if(resultWrapper.getResultsCount() > 0) {
                ResultWrapper.Result result = resultWrapper.getResults(0);
                ByteString dataString = result.getPayload();
                String results = dataString.toStringUtf8();

                // UI updates must always run in UI thread
                runOnUiThread(() -> {
                    resultsView = (TextView) findViewById(R.id.resultsText);
                    String prev = resultsView.getText().toString();
                    StringBuilder sb = new StringBuilder();
                    sb.append(results);
                    sb.append("\n");
                    sb.append(prev);
                    detections++;
                    resultsView.setText(sb.toString());
                });
            }
        };

        Consumer<ErrorType> onDisconnect = errorType -> {
            Log.e(LOG_TAG, "Disconnect Error:" + errorType.name());
            if (!recordingInitiated) {  //suppress this error when screen recording as we have to temporarily leave this activity causing a network disruption
                AlertDialog.Builder builder = new AlertDialog.Builder(GabrielClientActivity.this, AlertDialog.THEME_HOLO_DARK);
                builder.setMessage(errorType.name())
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
            finish();
        };
        openscoutcomm = ServerComm.createServerComm(
                consumer, this.serverIP, port, getApplication(), onDisconnect);
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
     * Terminates all services.
     */
    private void terminate() {
        Log.v(LOG_TAG, "++terminate");

        if (this.openscoutcomm != null) {
            this.openscoutcomm.stop();
            this.openscoutcomm = null;
        }
        if (preview != null) {
            cameraCapture.shutdown();
            preview = null;
            cameraCapture = null;
        }


    }



}
