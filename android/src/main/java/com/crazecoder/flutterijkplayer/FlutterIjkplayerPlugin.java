package com.crazecoder.flutterijkplayer;

import android.app.Activity;
import android.content.Intent;

import com.crazecoder.activity.VideoPlayActivity;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

/**
 * FlutterIjkplayerPlugin
 */
public class FlutterIjkplayerPlugin implements MethodCallHandler {
    private Activity activity;

    private FlutterIjkplayerPlugin(Activity activity) {
        this.activity = activity;
    }

    /**
     * Plugin registration.
     */
    public static void registerWith(Registrar registrar) {
        final MethodChannel channel = new MethodChannel(registrar.messenger(), "crazecoder/flutter_ijkplayer");
        channel.setMethodCallHandler(new FlutterIjkplayerPlugin(registrar.activity()));
        registrar.platformViewRegistry().registerViewFactory("ijkplayer", new IJKPlayerFactory());
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        if (call.method.equals("play")) {
            Intent intent = new Intent(activity, VideoPlayActivity.class);
            if (call.hasArgument("url"))
                intent.putExtra("url", call.argument("url").toString());
            if (call.hasArgument("title") && call.argument("title") != null)
                intent.putExtra("title", call.argument("title").toString());
            if (call.hasArgument("cache") && call.argument("cache") != null)
                intent.putExtra("cache", (Boolean) call.argument("cache"));
            if (call.hasArgument("radio") && call.argument("radio") != null) {
                double radio = call.argument("radio");
                intent.putExtra("landscape", radio > 1);
            }
            if (call.hasArgument("exoMode") && call.argument("exoMode") != null) {
                boolean exoMode = call.argument("exoMode");
                intent.putExtra("exoMode", exoMode);
            }
            activity.startActivity(intent);
            result.success("");
        } else {
            result.notImplemented();
        }
    }
}
