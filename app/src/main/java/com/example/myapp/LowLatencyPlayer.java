package com.example.myapp;

import android.net.Uri;

/** Fast track / AAudio 처럼 PCM을 직접 출력하는 저지연 플레이어의 공통 인터페이스 */
public interface LowLatencyPlayer {

    interface Listener {
        void onReady();
        void onCompleted();
        void onError(String message);
    }

    void load(Uri uri, long startMs, boolean autoPlay);

    void play();

    void pause();

    boolean isPlaying();

    long getPositionMs();

    void release();

    /** 실제 적용된 출력 경로 설명 (화면 표시용). 없으면 빈 문자열 */
    default String getInfo() {
        return "";
    }
}