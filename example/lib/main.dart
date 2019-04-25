import 'package:flutter/material.dart';
import 'package:flutter_ijkplayer/flutter_ijkplayer.dart';

void main() => runApp(MyApp());

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
//  static const url = "http://img.ksbbs.com/asset/Mon_1703/05cacb4e02f9d9e.mp4";
  static const url =
      "https://cdn-4.haku99.com/hls/2019/02/13/z3KVXvYN/playlist.m3u8";

  @override
  void initState() {
    super.initState();
  }


  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
        body: Center(
          child: Column(
            children: <Widget>[
              Container(
                child: FlutterIjkplayer(
                  url: url,
                  autoPlay: true,
                  previewMills: 10000,
                ),
                height: 300,
              ),
              RaisedButton(
                child: Text("click"),
                onPressed: () => play(
                      url: url,
                      radio: 0.9,
                    ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
