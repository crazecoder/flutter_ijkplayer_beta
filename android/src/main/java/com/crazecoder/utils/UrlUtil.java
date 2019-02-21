package com.crazecoder.utils;

/**
 * Note of this class.
 *
 * @author crazecoder
 * @since 2019/2/20
 */
public class UrlUtil {
    public static boolean isUseExoPlayer(String url){
        return url.endsWith("m3u8");
    }
}
