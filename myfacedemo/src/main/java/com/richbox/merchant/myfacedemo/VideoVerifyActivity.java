package com.richbox.merchant.myfacedemo;

import android.*;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.iflytek.cloud.ErrorCode;
import com.iflytek.cloud.FaceDetector;
import com.iflytek.cloud.IdentityListener;
import com.iflytek.cloud.IdentityResult;
import com.iflytek.cloud.IdentityVerifier;
import com.iflytek.cloud.InitListener;
import com.iflytek.cloud.SpeechConstant;
import com.iflytek.cloud.SpeechError;
import com.iflytek.cloud.util.Accelerometer;
import com.richbox.merchant.myfacedemo.utils.FaceRect;
import com.richbox.merchant.myfacedemo.utils.FaceUtil;
import com.richbox.merchant.myfacedemo.utils.ParseResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

public class VideoVerifyActivity extends AppCompatActivity implements Camera.PreviewCallback{

    private static final String TAG = "VideoVerifyActivity";
    private SurfaceView mPreviewSurface;
    private SurfaceView mFaceSurface;
    private Camera mCamera;
    private int mCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
    // Camera nv21格式预览帧的尺寸，默认设置640*480
    private int PREVIEW_WIDTH = 640;
    private int PREVIEW_HEIGHT = 480;
    // 预览帧数据存储数组和缓存数组
    private byte[] nv21;
    private byte[] buffer;
    // 缩放矩阵
    private Matrix mScaleMatrix = new Matrix();
    // 加速度感应器，用于获取手机的朝向
    private Accelerometer mAcc;
    // FaceDetector对象，集成了离线人脸识别：人脸检测、视频流检测功能
    private FaceDetector mFaceDetector;
    private boolean mStopTrack;
    private int isAlign = 1;//是获取关键点0：不显示；1：显示
    private boolean outTime = false;//用于直接截图验证
    private boolean clipPic = true;//截图验证标记
    private Bitmap mImage;//相机截图
    private byte[] mImageData = null;//压缩后的图片数据
    private boolean isFirstLoad = true;//是否是第一次加载相机
    //UI相关
    private int recWidth = 0;
    private int recHeight = 0;
    private RelativeLayout content_wrapper;
    private TextView blink_tip;
    private static final int DEFAULT_MAX_ANGLE = 100;
    private static final int GET_FACE_ANGLE = 78;
    private double lminDegree = 0;
    private double lmaxDegree = 0;
    private double rminDegree = 0;
    private double rmaxDegree = 0;
    private Toast mToast;
    // 身份验证对象
    private IdentityVerifier mIdVerifier;
    // 用户id，唯一标识
    private String authid ;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_verify);
        initUI();

        nv21 = new byte[PREVIEW_WIDTH * PREVIEW_HEIGHT * 2];
        buffer = new byte[PREVIEW_WIDTH * PREVIEW_HEIGHT * 2];
        mAcc = new Accelerometer(this);

        // 身份验证对象初始化
        mIdVerifier = IdentityVerifier.createVerifier(this, new InitListener() {

            @Override
            public void onInit(int errorCode) {
                if (ErrorCode.SUCCESS == errorCode) {
                    showTip("引擎初始化成功");
                } else {
                    showTip("引擎初始化失败，错误码：" + errorCode);
                }
            }
        });
//        try {
//            mFaceDetector = FaceDetector.createDetector(this, null);
//        } catch (SpeechError speechError) {
//            Log.e(TAG, "speechError: " + speechError );
//            speechError.printStackTrace();
//        }
        EditText editText = (EditText) findViewById(R.id.userId);
        authid = getIntent().getStringExtra("UserId");
        try {
            mFaceDetector = FaceDetector.createDetector(this, null);
        } catch (SpeechError speechError) {
            speechError.printStackTrace();
        }
        Log.e(TAG, "onCreate: mFaceDetector------>  " + mFaceDetector );
        mFaceDetector = FaceDetector.getDetector();
        Log.e(TAG, "userid: =============  " + authid +  "   mFaceDetector-----> " + mFaceDetector );
    }

    @Override
    protected void onResume() {
        super.onResume();
        //第一次打开相机需要等到surface创建成功以后回调，后续可直接打开
        if (!isFirstLoad) {
            openCamera();
        }
        isFirstLoad = false;

        resetUI(false);//刷新UI

        if (null != mAcc) {
            mAcc.start();
        }

        mStopTrack = false;
        new Thread(new Runnable() {

            @Override
            public void run() {
                Log.e(TAG, "mStopTrack: " + mStopTrack );
                while (!mStopTrack) {
                    if (null == nv21) {
                        continue;
                    }

                    synchronized (nv21) {
                        System.arraycopy(nv21, 0, buffer, 0, nv21.length);
                    }

                    // 获取手机朝向，返回值0,1,2,3分别表示0,90,180和270度
                    int direction = Accelerometer.getDirection();
                    boolean frontCamera = (Camera.CameraInfo.CAMERA_FACING_FRONT == mCameraId);
                    // 前置摄像头预览显示的是镜像，需要将手机朝向换算成摄相头视角下的朝向。
                    // 转换公式：a' = (360 - a)%360，a为人眼视角下的朝向（单位：角度）
                    if (frontCamera) {
                        // SDK中使用0,1,2,3,4分别表示0,90,180,270和360度
                        direction = (4 - direction) % 4;
                    }


                    Canvas canvas = mFaceSurface.getHolder().lockCanvas();
                    if (null == canvas) {
                        continue;
                    }

                    canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                    canvas.setMatrix(mScaleMatrix);

//                    mFaceSurface.getHolder().unlockCanvasAndPost(canvas);

                    Log.e(TAG, "检测到眨眼");
                    if (safeToTakePicture && outTime) {
//                        commit();
                    }
                    mFaceSurface.getHolder().unlockCanvasAndPost(canvas);
                }
            }
        }).start();
    }

    private boolean safeToTakePicture = false;

    /**
     * 提交验证信息
     */
    private void commit() {
        clipPic = true;//已提交验证
        stopTip = true;
        blinkTipHandler.sendEmptyMessage(2);
        Log.e(TAG, "mCamera: " + mCamera );
        safeToTakePicture = false;
        mCamera.takePicture(null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                Log.e(TAG, "onPictureTaken: 已拍照 "  );
                compressImage(data);
//                loadingDialog.show();

                if (null != mImageData) {
//                    mProDialog.setMessage("验证中...");
//                    mProDialog.show();
                    // 设置人脸验证参数
                    // 清空参数
                    mIdVerifier.setParameter(SpeechConstant.PARAMS, null);
                    // 设置会话场景
                    mIdVerifier.setParameter(SpeechConstant.MFV_SCENES, "ifr");
                    // 设置会话类型
                    mIdVerifier.setParameter(SpeechConstant.MFV_SST, "verify");
                    // 设置验证模式，单一验证模式：sin
                    mIdVerifier.setParameter(SpeechConstant.MFV_VCM, "sin");
                    // 用户id
                    mIdVerifier.setParameter(SpeechConstant.AUTH_ID, authid);
                    // 设置监听器，开始会话
                    mIdVerifier.startWorking(mVerifyListener);

                    // 子业务执行参数，若无可以传空字符传
                    StringBuffer params = new StringBuffer();
                    // 向子业务写入数据，人脸数据可以一次写入
                    mIdVerifier.writeData("ifr", params.toString(), mImageData, 0, mImageData.length);
                    // 停止写入
                    mIdVerifier.stopWrite("ifr");
                }
            }
        });
    }

    /**
     * 人脸验证监听器
     */
    private IdentityListener mVerifyListener = new IdentityListener() {

        @Override
        public void onResult(IdentityResult result, boolean islast) {
            Log.d(TAG, result.getResultString());

//            if (null != mProDialog) {
//                mProDialog.dismiss();
//            }

            try {
                JSONObject object = new JSONObject(result.getResultString());
                Log.e(TAG, "onResult: " + object );
                String decision = object.getString("decision");

                if ("accepted".equalsIgnoreCase(decision)) {
                    showTip("通过验证");
                    finish();
                } else {
                    showTip("验证失败");
                    openCamera();
//                    resetUI(true);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {
        }

        @Override
        public void onError(SpeechError error) {
//            if (null != mProDialog) {
//                mProDialog.dismiss();
//            }

            showTip(error.getPlainDescription(true));
        }

    };

    private void compressImage(byte[] data) {
        // 获取图片保存路径
        String fileSrc = FaceUtil.getImagePath(this);
        try {
            FileOutputStream outStream = new FileOutputStream(fileSrc);
            outStream.write(data);
            outStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // 获取图片的宽和高
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        mImage = BitmapFactory.decodeFile(fileSrc, options);

        // 压缩图片
        options.inSampleSize = Math.max(1, (int) Math.ceil(Math.max(
                (double) options.outWidth / 1024f,
                (double) options.outHeight / 1024f)));
        options.inJustDecodeBounds = false;
        mImage = BitmapFactory.decodeFile(fileSrc, options);

        // 若mImageBitmap为空则图片信息不能正常获取
        if (null == mImage) {
            showTip("图片信息无法正常获取");
            return;
        }

        // 部分手机会对图片做旋转，这里检测旋转角度
        int degree = FaceUtil.readPictureDegree(fileSrc);
        if (degree != 0) {
            // 把图片旋转为正的方向
            mImage = FaceUtil.rotateImage(degree, mImage);
        } else {
            //camera初始设置了90度旋转
            mImage = FaceUtil.rotateImage(-90, mImage);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        //可根据流量及网络状况对图片进行压缩
        mImage.compress(Bitmap.CompressFormat.JPEG, 80, baos);
        mImageData = baos.toByteArray();
    }




    //重置UI,是否刷新相机
    private void resetUI(boolean restartCamera) {
        if (restartCamera && mCamera != null) {
            mCamera.startPreview();
            safeToTakePicture = true;
        }

//        if (crpv.getPercent() > 0) {
//            animHandler.sendEmptyMessage(4);
//        }
        //2秒后开始检测眨眼
        blinkTipHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                //1.5s后恢复状态
                outTime = false;
                clipPic = false;
                stopTip = false;
            }
        }, 2000);
        //5.5秒检测不到眨眼，在有脸时，直接截图
        blinkTipHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                outTime = true;
            }
        }, 3000);
    }


    private void initUI(){

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        recWidth = metrics.widthPixels / 2;
        recHeight = (int) (recWidth * PREVIEW_WIDTH / (float) PREVIEW_HEIGHT);


        content_wrapper = (RelativeLayout) findViewById(R.id.content_wrapper);//布局容器
        blink_tip = (TextView) findViewById(R.id.blink_tip);//眨眼提醒
        mPreviewSurface = (SurfaceView) findViewById(R.id.sfv_preview);
        mFaceSurface = (SurfaceView) findViewById(R.id.sfv_face);

        mPreviewSurface.getHolder().addCallback(mPreviewCallback);
        mPreviewSurface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mFaceSurface.setZOrderOnTop(true);
        mFaceSurface.getHolder().setFormat(PixelFormat.TRANSLUCENT);

        setSurfaceSize();
        blinkTimer.schedule(blinkTimerTask, 1000, 1000);
        blinkTimer.schedule(clearTimerTask, 1000, 300);
        mToast = Toast.makeText(this, "", Toast.LENGTH_SHORT);
    }

    private boolean blinkTipFlag = true;//提示闪烁true:显示提示；false:隐藏提示
    private Timer blinkTimer = new Timer();
    private boolean stopTip = false;//是否停止闪烁开关
    private TimerTask clearTimerTask = new TimerTask() {
        @Override
        public void run() {
//            Log.e("clearTimerTask", "running");
            //每隔0.3秒重置一次眼睛角度
            lmaxDegree = lminDegree = rmaxDegree = rminDegree = 0;
        }
    };

    private TimerTask blinkTimerTask = new TimerTask() {
        @Override
        public void run() {
//            Log.e("blinkTimerTask", "running");
            if (!stopTip) {
                blinkTipFlag = !blinkTipFlag;
                blinkTipHandler.sendEmptyMessage(blinkTipFlag ? 1 : 2);
            }
        }
    };

    private Handler blinkTipHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    blink_tip.setText("未检测到眨眼");
                    break;
                case 2:
                    blink_tip.setText("");
                    break;
            }
        }
    };


    private void setSurfaceSize() {
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(recWidth, recHeight);
        params.addRule(RelativeLayout.CENTER_HORIZONTAL);
        params.setMargins(0, 200, 0, 0);
        content_wrapper.setLayoutParams(params);
    }

    private SurfaceHolder.Callback mPreviewCallback = new SurfaceHolder.Callback() {

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            closeCamera();
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            openCamera();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width,
                                   int height) {
            mScaleMatrix.setScale(width / (float) PREVIEW_HEIGHT, height / (float) PREVIEW_WIDTH);
        }
    };

    private void closeCamera() {
        if (null != mCamera) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    private void openCamera() {
        if (null != mCamera) {
            return;
        }

        if (!checkCameraPermission()) {
            showTip("摄像头权限未打开，请打开后再试");
            mStopTrack = true;
            return;
        }

        // 只有一个摄相头，打开后置
        if (Camera.getNumberOfCameras() == 1) {
            mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
        }

        try {
            mCamera = Camera.open(mCameraId);
        } catch (Exception e) {
            e.printStackTrace();
            closeCamera();
            return;
        }

        Camera.Parameters params = mCamera.getParameters();
        params.setPreviewFormat(ImageFormat.NV21);
        params.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        mCamera.setParameters(params);

        // 设置显示的偏转角度，大部分机器是顺时针90度，某些机器需要按情况设置
        mCamera.setDisplayOrientation(90);
        mCamera.setPreviewCallback(new Camera.PreviewCallback() {

            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                System.arraycopy(data, 0, nv21, 0, data.length);
            }
        });

        try {
            mCamera.setPreviewDisplay(mPreviewSurface.getHolder());
            mCamera.startPreview();
            safeToTakePicture = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean checkCameraPermission() {
        int status = checkPermission(Manifest.permission.CAMERA, Process.myPid(), Process.myUid());
        if (PackageManager.PERMISSION_GRANTED == status) {
            return true;
        }

        return false;
    }

    private void showTip(final String str) {
        mToast.setText(str);
        mToast.show();
    }

    //计算眼睛角度
    private double toDegree(Point start, Point center, Point end) {
        double a = Math.sqrt(Math.pow((center.x - start.x), 2) + Math.pow((center.y - start.y), 2));
        double c = Math.sqrt(Math.pow((center.x - end.x), 2) + Math.pow((center.y - end.y), 2));
        double b = Math.sqrt(Math.pow((end.x - start.x), 2) + Math.pow((end.y - start.y), 2));
        // 计算弧度表示的角
        double B = Math.acos((a * a + c * c - b * b) / (2.0 * a * c));
        // 用角度表示的角
        B = Math.toDegrees(B);
        return B;
    }

    private boolean isclip(double l, double r) {
        if (l < 162 || r < 162) {
            return false;
        }

        if (lminDegree > l || lminDegree == 0)
            lminDegree = l;
        if (lmaxDegree < l || lmaxDegree == 0)
            lmaxDegree = l;
        if (rminDegree > r || rminDegree == 0)
            rminDegree = l;
        if (rmaxDegree < r || rmaxDegree == 0)
            rmaxDegree = r;

        if (lmaxDegree - lminDegree > 8 && rmaxDegree - rminDegree > 8) {
            lmaxDegree = lminDegree = rmaxDegree = rminDegree = 0;
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onPreviewFrame(byte[] bytes, Camera camera) {
        compressImage(bytes);
//                loadingDialog.show();

        if (null != mImageData) {
//                    mProDialog.setMessage("验证中...");
//                    mProDialog.show();
            // 设置人脸验证参数
            // 清空参数
            mIdVerifier.setParameter(SpeechConstant.PARAMS, null);
            // 设置会话场景
            mIdVerifier.setParameter(SpeechConstant.MFV_SCENES, "ifr");
            // 设置会话类型
            mIdVerifier.setParameter(SpeechConstant.MFV_SST, "verify");
            // 设置验证模式，单一验证模式：sin
            mIdVerifier.setParameter(SpeechConstant.MFV_VCM, "sin");
            // 用户id
            mIdVerifier.setParameter(SpeechConstant.AUTH_ID, authid);
            // 设置监听器，开始会话
            mIdVerifier.startWorking(mVerifyListener);

            // 子业务执行参数，若无可以传空字符传
            StringBuffer params = new StringBuffer();
            // 向子业务写入数据，人脸数据可以一次写入
            mIdVerifier.writeData("ifr", params.toString(), mImageData, 0, mImageData.length);
            // 停止写入
            mIdVerifier.stopWrite("ifr");
        }
    }
}
