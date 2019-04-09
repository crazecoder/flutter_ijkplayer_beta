package com.crazecoder.activity;


import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.crazecoder.flutterijkplayer.R;
import com.crazecoder.media.IjkVideoView;
import com.crazecoder.utils.UrlUtil;
import com.crazecoder.utils.ViewUtil;
import com.google.android.exoplayer2.ui.SimpleExoPlayerView;


public class VideoPlayActivity extends Activity {

    IjkVideoView ijkVideoView;
    SimpleExoPlayerView simpleExoPlayerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean landscape = getIntent().getBooleanExtra("landscape", false);

        if (landscape)
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);//强制横屏
        else
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);//强制竖屏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_play);
        simpleExoPlayerView = findViewById(R.id.exo_view);
        ijkVideoView = findViewById(R.id.ijk_view);
        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        boolean cache = getIntent().getBooleanExtra("cache", true);
        boolean exoMode = getIntent().getBooleanExtra("exoMode", false);
        if (exoMode||UrlUtil.isUseExoPlayer(url)) {
            simpleExoPlayerView.setVisibility(View.VISIBLE);
            ijkVideoView.setVisibility(View.GONE);
            ViewUtil.initExoPlayer(simpleExoPlayerView, true);
            simpleExoPlayerView.setPlayer(ViewUtil.getSimpleExoPlayer(this, url, true, true, 0));
        } else {
            ijkVideoView.setVisibility(View.VISIBLE);
            simpleExoPlayerView.setVisibility(View.GONE);
            ViewUtil.initIjkVideoView(ijkVideoView, 0, true);
            ijkVideoView.setVideoURI(Uri.parse(url));
            ijkVideoView.start();
        }
    }

    @Override
    protected void onDestroy() {
        if (ijkVideoView != null)
            ijkVideoView.release(true);
        if (simpleExoPlayerView != null && simpleExoPlayerView.getPlayer() != null)
            simpleExoPlayerView.getPlayer().release();
        super.onDestroy();
    }
}
