package com.crazecoder.activity;


import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
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
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_play);
        simpleExoPlayerView = findViewById(R.id.exo_view);
        ijkVideoView = findViewById(R.id.ijk_view);
        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        boolean cache = getIntent().getBooleanExtra("cache", true);
        if (UrlUtil.isUseExoPlayer(url)) {
            simpleExoPlayerView.setVisibility(View.VISIBLE);
            ijkVideoView.setVisibility(View.GONE);
            ViewUtil.initExoPlayer(simpleExoPlayerView);
            simpleExoPlayerView.setPlayer(ViewUtil.getSimpleExoPlayer(this, url, true));
        } else {
            ijkVideoView.setVisibility(View.VISIBLE);
            simpleExoPlayerView.setVisibility(View.GONE);
            ViewUtil.initIjkVideoView(ijkVideoView);
            ijkVideoView.start();
        }
    }
}
