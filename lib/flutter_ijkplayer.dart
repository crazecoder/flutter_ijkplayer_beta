import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/material.dart';

const MethodChannel _channel =
    const MethodChannel('crazecoder/flutter_ijkplayer');

Future<Null> play({@required String url, String title, bool cache}) async {
  Map<String, Object> map = {
    "url": url,
    "cache": cache,
    "title": title,
  };
  await _channel.invokeMethod(
    'play',
    map,
  );
}

class FlutterIjkplayer extends StatefulWidget {
  final String url;
  final bool autoPlay;
  final bool autoSound;
  final int previewMills;

  FlutterIjkplayer({
    this.url,
    this.autoPlay: false,
    this.autoSound: false,
    this.previewMills: 0,
  });

  @override
  State<StatefulWidget> createState() => _FlutterIjkplayerState();
}

class _FlutterIjkplayerState extends State<FlutterIjkplayer> {
  @override
  Widget build(BuildContext context) {
    return AndroidView(
      viewType: "ijkplayer",
      onPlatformViewCreated: (_) {},
      creationParamsCodec: StandardMessageCodec(),
      creationParams: {
        "url": widget.url,
        "autoPlay": widget.autoPlay,
        "autoSound": widget.autoSound,
        "previewMills": widget.previewMills,
      },
    );
  }
}
