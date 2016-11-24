package com.kreolite.androvision;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.imgproc.Imgproc;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.View.OnTouchListener;
import android.view.SurfaceView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Set;

public class ColorBlobDetectionActivity extends Activity implements OnTouchListener, CvCameraViewListener2 {
    private static final String                TAG = "ColorBlobDetectActivity";
    private static final int                   ZOOM = 4;
    private static Scalar                      COLOR_RADIUS = new Scalar(25,60,60,0);
    private Size                               SCREEN_SIZE;
    volatile Point                             targetCenter = new Point(-1, -1);
    private Point                              screenCenter = new Point(-1, -1);
    private int                                targetRadius = 0;
    private long                               minRadiusPercent = 0;
    private long                               maxRadiusPercent = 0;
    private long                               minRadius = 0;
    private long                               maxRadius = 0;
    private int                                mCircleNum = 0;

    private boolean                            mIsColorSelected = false;
    private Mat                                mRgba;
    private Scalar                             mBlobColorRgba;
    private Scalar                             mBlobColorHsv;
    private ColorBlobDetector                  mDetector;
    private Mat                                mSpectrum;
    private Size                               SPECTRUM_SIZE;
    private Scalar                             CONTOUR_COLOR;

    private CameraBridgeViewBase               mOpenCvCameraView;
    private ActuatorController                 carController;

    private UsbService                         usbService;
    private MyHandler                          mHandler;
    private SharedPreferences                  sharedPref;
    private boolean                            isReso1, isReso2, isReso3, isReso4;
    private double                             forwardBoundaryPercent = -0.15;
    private double                             reverseBoundaryPercent = 0.3;
    int                                        countOutOfFrame = 0;

    String                                     _lastPwmJsonValues = "";
    boolean                                    _isReversingHandled = false;

    private BaseLoaderCallback  mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.i(TAG, "OpenCV loaded successfully");
                    mOpenCvCameraView.enableView();
                    mOpenCvCameraView.setOnTouchListener(ColorBlobDetectionActivity.this);
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };

    public ColorBlobDetectionActivity() {
        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.color_blob_detection_surface_view);

        sharedPref = getApplicationContext().getSharedPreferences(getString(R.string.settings_file),
                Context.MODE_PRIVATE);
        isReso1 = sharedPref.getBoolean(getString(R.string.is_reso1), true);
        isReso2 = sharedPref.getBoolean(getString(R.string.is_reso2), false);
        isReso3 = sharedPref.getBoolean(getString(R.string.is_reso3), false);

        // 1920x1080, 1280x960, 800x480 else 352x288
        if (isReso1) {
            SCREEN_SIZE = new Size(1920, 1080);
        } else if (isReso2) {
            SCREEN_SIZE = new Size(1280, 960);
        } else if (isReso3) {
            SCREEN_SIZE = new Size(800, 480);
        }  else {
            SCREEN_SIZE = new Size(352, 288);
        }

        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.color_blob_detection_activity_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
        mOpenCvCameraView.setMaxFrameSize((int) SCREEN_SIZE.width, (int) SCREEN_SIZE.height);

        minRadiusPercent = sharedPref.getInt(getString(R.string.min_radius), R.integer.minRadiusPercent);
        minRadius = minRadiusPercent * (((long) SCREEN_SIZE.height) / 100L);
        maxRadiusPercent = sharedPref.getInt(getString(R.string.max_radius), R.integer.maxRadiusPercent);
        maxRadius = maxRadiusPercent * (((long) SCREEN_SIZE.height) / 100L);

        forwardBoundaryPercent = Double.parseDouble(sharedPref.getString(getString(R.string.forward_boundary_percent), "-15")) / 100;
        reverseBoundaryPercent = Double.parseDouble(sharedPref.getString(getString(R.string.reverse_boundary_percent), "30")) / 100;

        carController = new ActuatorController();
        countOutOfFrame = 0;

        mHandler = new MyHandler(this);
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();

        unregisterReceiver(mUsbReceiver);
        unbindService(usbConnection);
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback);
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
        hideNavigationBar();
        setFilters();  // Start listening notifications from UsbService
        startService(UsbService.class, usbConnection, null); // Start UsbService(if it was not started before) and Bind it
    }

    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height, width, CvType.CV_8UC4);
        mDetector = new ColorBlobDetector();
        mDetector.setColorRadius(COLOR_RADIUS);
        mDetector.setMinRadius( (int) minRadius);
        mDetector.setMaxRadius((int) maxRadius);
        mSpectrum = new Mat();
        mBlobColorRgba = new Scalar(255);
        mBlobColorHsv = new Scalar(255);
        SPECTRUM_SIZE = new Size(200, 64);
        CONTOUR_COLOR = new Scalar(255,255,10,255);
        screenCenter.x = mRgba.size().width / 2;
        screenCenter.y = mRgba.size().height / 2;
    }

    public void onCameraViewStopped() {
        mCircleNum = 0;
        targetCenter.x = -1;
        targetCenter.y = -1;
        carController.reset();
        updateActuator();
        mRgba.release();
    }

    public boolean onTouch(View v, MotionEvent event) {

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);

        int cols = mRgba.cols();
        int rows = mRgba.rows();

        int xOffset = (mOpenCvCameraView.getWidth() - cols) / 2;
        int yOffset = (mOpenCvCameraView.getHeight() - rows) / 2;

        int x = (int)event.getX() - xOffset;
        int y = (int)event.getY() - yOffset;

        Log.i(TAG, "Touch image coordinates: (" + x + ", " + y + ")");

        if ((x < 0) || (y < 0) || (x > cols) || (y > rows)) return false;

        Rect touchedRect = new Rect();

        touchedRect.x = (x>ZOOM) ? x-ZOOM : 0;
        touchedRect.y = (y>ZOOM) ? y-ZOOM : 0;

        touchedRect.width = (x+ZOOM < cols) ? x + ZOOM - touchedRect.x : cols - touchedRect.x;
        touchedRect.height = (y+ZOOM < rows) ? y + ZOOM - touchedRect.y : rows - touchedRect.y;

        Mat touchedRegionRgba = mRgba.submat(touchedRect);

        Mat touchedRegionHsv = new Mat();
        Imgproc.cvtColor(touchedRegionRgba, touchedRegionHsv, Imgproc.COLOR_RGB2HSV_FULL);

        // Calculate average color of touched region
        mBlobColorHsv = Core.sumElems(touchedRegionHsv);
        int pointCount = touchedRect.width*touchedRect.height;
        for (int i = 0; i < mBlobColorHsv.val.length; i++)
            mBlobColorHsv.val[i] /= pointCount;

        mBlobColorRgba = converScalarHsv2Rgba(mBlobColorHsv);

        Log.i(TAG, "Touched rgba color: (" + mBlobColorRgba.val[0] + ", " + mBlobColorRgba.val[1] +
                ", " + mBlobColorRgba.val[2] + ", " + mBlobColorRgba.val[3] + ")");

        mDetector.setHsvColor(mBlobColorHsv);

        Imgproc.resize(mDetector.getSpectrum(), mSpectrum, SPECTRUM_SIZE);

        mIsColorSelected = true;

        touchedRegionRgba.release();
        touchedRegionHsv.release();

        return false; // don't need subsequent touch events
    }

    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        mRgba = inputFrame.rgba();

        if (mIsColorSelected) {

            mDetector.findCircles(mRgba);
            Mat circles = mDetector.getCircles();
            mCircleNum = circles.rows();
            Log.i(TAG, "Target Count: " + mCircleNum);

            for (int i = 0, n = circles.rows(); i < n; i++) {
                double[] circleCoordinates = circles.get(0, i);
                int x = (int) circleCoordinates[0], y = (int) circleCoordinates[1];

                targetCenter = new Point(x, y);
                targetRadius = (int) circleCoordinates[2];

                Imgproc.circle(mRgba, targetCenter, targetRadius, CONTOUR_COLOR, 2, 0, 0);
                Imgproc.circle(mRgba, targetCenter, 3, CONTOUR_COLOR, Core.FILLED);

                Log.i(TAG, "Target Center = " + targetCenter);
                Log.i(TAG, "Target Radius = " + targetRadius);
                Log.i(TAG, "Radius Range = [" + minRadius + "," + maxRadius + "]");
            }

            /* mDetector.process(mRgba);
            List<MatOfPoint> contours = mDetector.getContours();
            Log.e(TAG, "Contours count: " + contours.size());
            Imgproc.drawContours(mRgba, contours, -1, CONTOUR_COLOR, 3);

            MatOfPoint2f points = new MatOfPoint2f();
            for (int i = 0, n = contours.size(); i < n; i++) {
                // contours.get(x) is a single MatOfPoint, but to use minEnclosingCircle we need to pass a MatOfPoint2f so we need to do a
                // conversion
                contours.get(i).convertTo(points, CvType.CV_32FC2);
                Imgproc.minEnclosingCircle(points, targetCenter, null);
                Imgproc.circle(mRgba, targetCenter, 3, CONTOUR_COLOR, Core.FILLED);
            }*/

            Mat colorLabel = mRgba.submat(4, 68, 4, 68);
            colorLabel.setTo(mBlobColorRgba);

            Mat spectrumLabel = mRgba.submat(4, 4 + mSpectrum.rows(), 70, 70 + mSpectrum.cols());
            mSpectrum.copyTo(spectrumLabel);
        }

        updateActuator();
        return mRgba;
    }

    private void hideNavigationBar() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private Scalar converScalarHsv2Rgba(Scalar hsvColor) {
        Mat pointMatRgba = new Mat();
        Mat pointMatHsv = new Mat(1, 1, CvType.CV_8UC3, hsvColor);
        Imgproc.cvtColor(pointMatHsv, pointMatRgba, Imgproc.COLOR_HSV2RGB_FULL, 4);

        return new Scalar(pointMatRgba.get(0, 0));
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case UsbService.ACTION_USB_PERMISSION_GRANTED: // USB PERMISSION GRANTED
                    Toast.makeText(context, "USB Ready", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_PERMISSION_NOT_GRANTED: // USB PERMISSION NOT GRANTED
                    Toast.makeText(context, "USB Permission not granted", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_NO_USB: // NO USB CONNECTED
                    Toast.makeText(context, "No USB connected", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_DISCONNECTED: // USB DISCONNECTED
                    Toast.makeText(context, "USB disconnected", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_NOT_SUPPORTED: // USB NOT SUPPORTED
                    Toast.makeText(context, "USB device not supported", Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };

    private final ServiceConnection usbConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName arg0, IBinder arg1) {
            usbService = ((UsbService.UsbBinder) arg1).getService();
            usbService.setHandler(mHandler);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            usbService = null;
        }
    };

    private void startService(Class<?> service, ServiceConnection serviceConnection, Bundle extras) {
        if (!UsbService.SERVICE_CONNECTED) {
            Intent startService = new Intent(this, service);
            if (extras != null && !extras.isEmpty()) {
                Set<String> keys = extras.keySet();
                for (String key : keys) {
                    String extra = extras.getString(key);
                    startService.putExtra(key, extra);
                }
            }
            startService(startService);
        }
        Intent bindingIntent = new Intent(this, service);
        bindService(bindingIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setFilters() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbService.ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(UsbService.ACTION_NO_USB);
        filter.addAction(UsbService.ACTION_USB_DISCONNECTED);
        filter.addAction(UsbService.ACTION_USB_NOT_SUPPORTED);
        filter.addAction(UsbService.ACTION_USB_PERMISSION_NOT_GRANTED);
        registerReceiver(mUsbReceiver, filter);
    }

    private static class MyHandler extends Handler {
        private final WeakReference<ColorBlobDetectionActivity> mActivity;

        MyHandler(ColorBlobDetectionActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case UsbService.MESSAGE_FROM_SERIAL_PORT:
                    String data = (String) msg.obj;
                    Log.d(TAG, "Received data from serial: " + data);
                    // Toast.makeText(mActivity.get(), "DATA_RCV: " + data, Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.CTS_CHANGE:
                    Toast.makeText(mActivity.get(), "CTS_CHANGE",Toast.LENGTH_LONG).show();
                    break;
                case UsbService.DSR_CHANGE:
                    Toast.makeText(mActivity.get(), "DSR_CHANGE",Toast.LENGTH_LONG).show();
                    break;
            }
        }
    }

    private void updateActuator(){
        String _pwmJsonValues, _pwmJsonNeutralValues;

        try {
            if (mCircleNum > 0) {
                carController.updateTargetPWM(screenCenter, targetCenter,
                        forwardBoundaryPercent, reverseBoundaryPercent);
                countOutOfFrame = 0;
            } else {
                countOutOfFrame++;
                if (countOutOfFrame > 2) {
                    targetCenter.x = -1;
                    targetCenter.y = -1;
                    countOutOfFrame = 0;
                    carController.reset();
                }
            }

            _pwmJsonValues = carController.getPWMValuesToJson();
            if ((_pwmJsonValues != null) && !_pwmJsonValues.contentEquals(_lastPwmJsonValues)) {
                Log.i(TAG, "Update Actuator ...");

                if (usbService != null) {
                    if (!carController.isReversing()) {
                        Log.i(TAG, "Sending PWM values: " + _pwmJsonValues);
                        usbService.write(_pwmJsonValues.getBytes());
                        _isReversingHandled = false;
                    }
                    else {
                        Log.i(TAG, "Sending PWM values: " + _pwmJsonValues);
                        usbService.write(_pwmJsonValues.getBytes());

                        // When reversing, need to send neutral first
                        if (!_isReversingHandled) {
                            _pwmJsonNeutralValues = carController.getPWMNeutralValuesToJson();
                            Log.i(TAG, "Sending PWM values: " + _pwmJsonNeutralValues);
                            usbService.write(_pwmJsonNeutralValues.getBytes());

                            Log.i(TAG, "Sending PWM values: " + _pwmJsonValues);
                            usbService.write(_pwmJsonValues.getBytes());
                            _isReversingHandled = true;
                        }
                    }
                }
                _lastPwmJsonValues = _pwmJsonValues;
            }
        } catch (InterruptedException e) {
            Log.e(TAG, e.getMessage());
        }
    }
}