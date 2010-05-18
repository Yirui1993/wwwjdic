package org.nick.wwwjdic.ocr;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import org.nick.wwwjdic.Constants;
import org.nick.wwwjdic.R;
import org.nick.wwwjdic.WebServiceBackedActivity;
import org.nick.wwwjdic.Wwwjdic;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Bitmap.CompressFormat;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import android.widget.CompoundButton.OnCheckedChangeListener;

public class OcrActivity extends WebServiceBackedActivity implements
        SurfaceHolder.Callback, OnClickListener, OnTouchListener,
        OnCheckedChangeListener {

    private static final String TAG = OcrActivity.class.getSimpleName();

    private static final String WEOCR_DEFAULT_URL = "http://maggie.ocrgrid.org/cgi-bin/weocr/nhocr.cgi";

    private static final String PREF_DUMP_CROPPED_IMAGES_KEY = "pref_ocr_dump_cropped_images";
    private static final String PREF_WEOCR_URL_KEY = "pref_weocr_url";
    private static final String PREF_WEOCR_TIMEOUT_KEY = "pref_weocr_timeout";

    private Camera camera;
    private Size previewSize;
    private Size pictureSize;

    private boolean isPreviewRunning = false;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private Uri imageCaptureUri;

    private boolean autoFocusInProgress = false;
    private static final int AUTO_FOCUS = 1;
    protected static final int OCRRED_TEXT = 2;
    public static final int PICTURE_TAKEN = 3;

    private TextView ocrredTextView;
    private Button dictSearchButton;
    private Button kanjidictSearchButton;

    private ToggleButton flashToggle;
    private boolean supportsFlash = false;

    @Override
    protected void activityOnCreate(Bundle icicle) {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        window.setFormat(PixelFormat.TRANSLUCENT);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.ocr);
        surfaceView = (SurfaceView) findViewById(R.id.capture_surface);
        surfaceView.setOnTouchListener(this);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        ocrredTextView = (TextView) findViewById(R.id.ocrredText);
        ocrredTextView.setTextSize(30f);

        dictSearchButton = (Button) findViewById(R.id.send_to_dict);
        dictSearchButton.setOnClickListener(this);
        kanjidictSearchButton = (Button) findViewById(R.id.send_to_kanjidict);
        kanjidictSearchButton.setOnClickListener(this);
        toggleSearchButtons(false);

        flashToggle = (ToggleButton) findViewById(R.id.auto_flash_toggle);
        flashToggle.setOnCheckedChangeListener(this);

        surfaceView.requestFocus();
    }

    private void toggleSearchButtons(boolean enabled) {
        dictSearchButton.setEnabled(enabled);
        kanjidictSearchButton.setEnabled(enabled);
    }

    protected Handler createHandler() {
        return handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                case AUTO_FOCUS:
                    if (msg.arg1 == 1) {
                        try {
                            autoFocusInProgress = false;
                            imageCaptureUri = createTempFile();
                            if (imageCaptureUri == null) {
                                Toast t = Toast.makeText(OcrActivity.this,
                                        R.string.sd_file_create_failed,
                                        Toast.LENGTH_SHORT);
                                t.show();

                                return;
                            }

                            final ImageCaptureCallback captureCb = new ImageCaptureCallback(
                                    getContentResolver().openOutputStream(
                                            imageCaptureUri), this);
                            camera.takePicture(null, null, captureCb);
                        } catch (IOException e) {
                            Log.e(TAG, e.getMessage(), e);
                            throw new RuntimeException(e);
                        }
                    } else {
                        autoFocusInProgress = false;
                        Toast t = Toast.makeText(OcrActivity.this,
                                R.string.af_failed, Toast.LENGTH_SHORT);
                        t.show();
                    }
                    break;
                case OCRRED_TEXT:
                    progressDialog.dismiss();
                    int success = msg.arg1;
                    if (success == 1) {
                        String ocrredText = (String) msg.obj;
                        ocrredTextView.setTextSize(30f);
                        ocrredTextView.setText(ocrredText);
                        toggleSearchButtons(true);
                    } else {
                        Toast t = Toast.makeText(OcrActivity.this,
                                R.string.ocr_failed, Toast.LENGTH_SHORT);
                        t.show();
                    }
                    break;
                case PICTURE_TAKEN:
                    crop();
                    break;
                default:
                    super.handleMessage(msg);
                }
            }
        };
    }

    class OcrTask implements Runnable {

        private Bitmap bitmap;
        private Handler handler;

        public OcrTask(Bitmap b, Handler h) {
            bitmap = b;
            handler = h;
        }

        @Override
        public void run() {
            try {
                WeOcrClient client = new WeOcrClient(getWeocrUrl(),
                        getWeocrTimeout());
                String ocredText = client.sendOcrRequest(bitmap);
                Log.d(TAG, "OCR result: " + ocredText);

                if (ocredText != null && !"".equals(ocredText)) {
                    Message msg = handler.obtainMessage(OCRRED_TEXT, 1, 0);
                    msg.obj = ocredText;
                    handler.sendMessage(msg);
                } else {
                    Log.d("TAG", "OCR failed: empty string returned");
                    Message msg = handler.obtainMessage(OCRRED_TEXT, 0, 0);
                    handler.sendMessage(msg);
                }
            } catch (Exception e) {
                Log.e("TAG", "OCR failed", e);
                Message msg = handler.obtainMessage(OCRRED_TEXT, 0, 0);
                handler.sendMessage(msg);
            }
        }
    }

    Camera.PictureCallback pictureCallbackRaw = new Camera.PictureCallback() {
        public void onPictureTaken(byte[] data, Camera c) {
            OcrActivity.this.camera.startPreview();
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            requestAutoFocus();
        }

        return false;
    }

    private void crop() {
        try {
            Intent intent = new Intent("com.android.camera.action.CROP");
            Bundle extras = new Bundle();
            extras.putBoolean("noFaceDetection", false);
            extras.putBoolean("return-data", true);
            extras.putBoolean("scale", true);
            intent.setDataAndType(imageCaptureUri, "image/jpeg");

            intent.putExtras(extras);
            startActivityForResult(intent, Constants.CROP_RETURN_RESULT);

        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
            Toast t = Toast.makeText(OcrActivity.this,
                    R.string.cant_start_cropper, Toast.LENGTH_SHORT);
            t.show();
        }
    }

    private void requestAutoFocus() {
        if (autoFocusInProgress) {
            return;
        }

        autoFocusInProgress = true;
        camera.autoFocus(new Camera.AutoFocusCallback() {
            @Override
            public void onAutoFocus(boolean success, Camera camera) {
                Message msg = handler.obtainMessage(AUTO_FOCUS,
                        success ? 1 : 0, -1);
                handler.sendMessage(msg);
            }
        });

        toggleSearchButtons(false);
        ocrredTextView.setText("");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == Constants.CROP_RETURN_RESULT) {
            File f = new File(imageCaptureUri.getPath());
            if (f.exists()) {
                boolean deleted = f.delete();
                Log.d(TAG, "deleted: " + deleted);
            }

            if (resultCode == RESULT_OK) {
                Bitmap cropped = (Bitmap) data.getExtras()
                        .getParcelable("data");
                try {
                    if (isDumpCroppedImages()) {
                        dumpBitmap(cropped, "cropped-color.jpg");
                    }

                    Bitmap blackAndWhiteBitmap = convertToGrayscale(cropped);

                    if (isDumpCroppedImages()) {
                        dumpBitmap(blackAndWhiteBitmap, "cropped.jpg");
                    }

                    OcrTask task = new OcrTask(blackAndWhiteBitmap, handler);
                    submitWsTask(task, "Doing OCR...");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else if (resultCode == RESULT_CANCELED) {
                Toast t = Toast.makeText(this, R.string.cancelled,
                        Toast.LENGTH_SHORT);
                t.show();
            }
        }
    }

    private boolean isDumpCroppedImages() {
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(this);

        return preferences.getBoolean(PREF_DUMP_CROPPED_IMAGES_KEY, false);
    }

    private int getWeocrTimeout() {
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(this);

        String timeoutStr = preferences.getString(PREF_WEOCR_TIMEOUT_KEY, "10");

        return Integer.parseInt(timeoutStr) * 1000;
    }

    private String getWeocrUrl() {
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(this);

        return preferences.getString(PREF_WEOCR_URL_KEY, WEOCR_DEFAULT_URL);
    }

    private Bitmap convertToGrayscale(Bitmap bitmap) {
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0);
        Paint paint = new Paint();
        ColorMatrixColorFilter cmcf = new ColorMatrixColorFilter(colorMatrix);
        paint.setColorFilter(cmcf);

        Bitmap result = Bitmap.createBitmap(bitmap.getWidth(), bitmap
                .getHeight(), Bitmap.Config.RGB_565);

        Canvas drawingCanvas = new Canvas(result);
        Rect src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        Rect dst = new Rect(src);
        drawingCanvas.drawBitmap(bitmap, src, dst, paint);

        return result;
    }

    private void dumpBitmap(Bitmap bitmap, String filename) {
        try {
            File sdDir = Environment.getExternalStorageDirectory();
            File wwwjdicDir = new File(sdDir.getAbsolutePath() + "/wwwjdic");
            if (!wwwjdicDir.exists()) {
                wwwjdicDir.mkdir();
            }

            if (!wwwjdicDir.canWrite()) {
                return;
            }

            File imageFile = new File(wwwjdicDir, filename);

            FileOutputStream out = new FileOutputStream(imageFile
                    .getAbsolutePath());
            bitmap.compress(CompressFormat.JPEG, 90, out);
            out.flush();
            out.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (holder != surfaceHolder) {
            return;
        }

        if (isPreviewRunning) {
            camera.stopPreview();
        }

        try {
            Camera.Parameters p = camera.getParameters();
            if (previewSize != null) {
                p.setPreviewSize(previewSize.width, previewSize.height);
            } else {
                if (w == 480) {
                    p.setPreviewSize(w, h);
                }
            }

            if (w == 480) {
                p.setPictureSize(w, h);
            } else {
                if (pictureSize != null) {
                    p.setPictureSize(pictureSize.width, pictureSize.height);
                }
            }

            if (supportsFlash) {
                toggleFlash(flashToggle.isChecked(), p);
            }

            camera.setParameters(p);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            isPreviewRunning = true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void surfaceCreated(SurfaceHolder holder) {
        camera = Camera.open();

        Camera.Parameters params = camera.getParameters();
        List<Size> supportedPreviewSizes = ReflectionUtils
                .getSupportedPreviewSizes(params);
        List<Size> supportedPictueSizes = ReflectionUtils
                .getSupportedPictureSizes(params);
        supportsFlash = ReflectionUtils.getFlashMode(params) != null;

        try {
            if (supportedPreviewSizes != null) {
                previewSize = getOptimalPreviewSize(supportedPreviewSizes);
                Log.d(TAG, String.format("preview width: %d; height: %d",
                        previewSize.width, previewSize.height));
                params.setPreviewSize(previewSize.width, previewSize.height);
                camera.setParameters(params);
            }

            if (supportedPictueSizes != null) {
                pictureSize = supportedPictueSizes.get(supportedPictueSizes
                        .size() - 1);
                Log.d(TAG, String.format("picture width: %d; height: %d",
                        pictureSize.width, pictureSize.height));
            }

            flashToggle.setEnabled(supportsFlash);
            camera.setPreviewDisplay(holder);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Size getOptimalPreviewSize(List<Size> sizes) {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        int targetHeight = windowManager.getDefaultDisplay().getHeight();

        Size result = null;
        double minDiff = Double.MAX_VALUE;
        for (Size size : sizes) {
            if (Math.abs(size.height - targetHeight) < minDiff) {
                result = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        return result;
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        camera.stopPreview();
        isPreviewRunning = false;
        camera.release();
    }

    @Override
    public void onClick(View v) {
        TextView t = (TextView) findViewById(R.id.ocrredText);
        String key = t.getText().toString();

        Bundle extras = new Bundle();
        extras.putString(Constants.SEARCH_TEXT_KEY, key);

        switch (v.getId()) {
        case R.id.send_to_dict:
            extras.putBoolean(Constants.SEARCH_TEXT_KANJI_KEY, false);
            break;
        case R.id.send_to_kanjidict:
            extras.putBoolean(Constants.SEARCH_TEXT_KANJI_KEY, true);
            break;
        default:
        }

        Intent intent = new Intent(this, Wwwjdic.class);
        intent.putExtras(extras);

        startActivity(intent);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        requestAutoFocus();

        return false;
    }

    private Uri createTempFile() {
        File sdDir = Environment.getExternalStorageDirectory();
        File wwwjdicDir = new File(sdDir.getAbsolutePath() + "/wwwjdic");
        if (!wwwjdicDir.exists()) {
            wwwjdicDir.mkdir();
        }

        if (wwwjdicDir.exists() && wwwjdicDir.canWrite()) {
            return Uri.fromFile(new File(wwwjdicDir, "tmp_ocr_"
                    + String.valueOf(System.currentTimeMillis()) + ".jpg"));
        }

        return null;
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (!supportsFlash) {
            return;
        }

        Camera.Parameters params = camera.getParameters();
        toggleFlash(isChecked, params);
    }

    private void toggleFlash(boolean useFlash, Camera.Parameters params) {
        String flashMode = "off";
        if (useFlash) {
            flashMode = "on";
        } else {
            flashMode = "off";
        }

        ReflectionUtils.setFlashMode(params, flashMode);
        camera.setParameters(params);
    }
}
