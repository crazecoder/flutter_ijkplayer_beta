package com.crazecoder.flutterijkplayer;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.crazecoder.media.IjkVideoView;
import com.crazecoder.utils.ViewUtil;
import com.google.android.exoplayer2.ui.PlayerView;


import java.util.Map;

import io.flutter.plugin.common.StandardMessageCodec;
import io.flutter.plugin.platform.PlatformView;
import io.flutter.plugin.platform.PlatformViewFactory;

/**
 * Note of this class.
 *
 * @author chendong
 * @since 2018/12/12
 */
public class IJKPlayerFactory extends PlatformViewFactory {

    private IjkVideoView ijkVideoView;
    private PlayerView simpleExoPlayerView;

    public IJKPlayerFactory() {
        super(new StandardMessageCodec());
    }

    @Override
    public PlatformView create(final Context context, int i, final Object param) {
        return new PlatformView() {
            @Override
            public View getView() {
                boolean autoPlay = false;
                Map<String, Object> map = (Map<String, Object>) param;
                ijkVideoView = new IjkVideoView(context);
                ViewUtil.initIjkVideoView(ijkVideoView);
                String url = null;
                if (map != null && map.containsKey("url")) {
                    url = map.get("url").toString();
                    if (map.containsKey("autoPlay")) {
                        autoPlay = (boolean) map.get("autoPlay");
                    }
                }
                Log.d("IJKPlayerFactory", url);
                if (url != null) {
                    if (url.endsWith("m3u8")) {
                        simpleExoPlayerView = new PlayerView(context);
                        simpleExoPlayerView.setPlayer(ViewUtil.getSimpleExoPlayer(context,url,autoPlay));
                        ViewUtil.initExoPlayer(simpleExoPlayerView,false);
                        return simpleExoPlayerView;
                    } else {
                        ijkVideoView.setVideoURI(Uri.parse(url));
                        if (autoPlay) ijkVideoView.start();
                        return ijkVideoView;
                    }
                }
                TextView textView = new TextView(context);
                textView.setText("url can not be null");
                return textView;
            }

            @Override
            public void dispose() {
                ijkVideoView.release(true);
            }
        };
    }



}
