package fi.wilma.dashboard;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

public class MainActivity extends Activity {

    private WebView      webView;
    private WilmaBridge  bridge;
    private Handler      handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        handler = new Handler();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT);
        webView.setLayoutParams(lp);
        root.addView(webView);
        setContentView(root);

        bridge = new WilmaBridge(webView, this);
        configureWebView();
        hideSystemUI();

        // Load the local dashboard
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void configureWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setSupportZoom(false);
        ws.setAllowFileAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setDefaultTextEncodingName("UTF-8");

        // Disable autocorrect and suggestions globally for the WebView.
        // This is the only reliable way to prevent Android's keyboard from
        // auto-capitalizing text fields on API 17 regardless of HTML attributes.
        ws.setSaveFormData(false);

        if (Build.VERSION.SDK_INT >= 21) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.addJavascriptInterface(bridge, "WilmaBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        hideSystemUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    public void onBackPressed() {
        // Disabled in kiosk mode
    }

    private void hideSystemUI() {
        final View decorView = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 19) {
            decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                0x00001000  // SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        } else {
            decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }

        decorView.setOnSystemUiVisibilityChangeListener(
            new View.OnSystemUiVisibilityChangeListener() {
                @Override
                public void onSystemUiVisibilityChange(int visibility) {
                    if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                        handler.postDelayed(new Runnable() {
                            @Override public void run() { hideSystemUI(); }
                        }, 2000);
                    }
                }
            }
        );
    }
}
