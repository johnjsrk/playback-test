package com.example.myapp;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.ByteBuffer;

/**
 * AAudio(MMAP) 재생용 플레이어.
 * MediaExtractor + MediaCodec 으로 디코딩한 PCM을 네이티브(C++) AAudio 스트림으로 출력한다.
 * 스트림은 PERFORMANCE_MODE_LOW_LATENCY + EXCLUSIVE 로 요청하며,
 * 기기/드라이버가 지원하면 MMAP 경로가 사용된다. (모노/스테레오, 16bit PCM만 지원)
 */
public class AAudioPlayer implements LowLatencyPlayer {

    private static boolean libLoaded;

    static {
        try {
            System.loadLibrary("aaudioplayer");
            libLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            libLoaded = false;
        }
    }

    private static native long nativeOpen(int sampleRate, int channels);

    private static native String nativeGetInfo(long handle);

    private static native int nativeStart(long handle);

    private static native int nativePause(long handle);

    // 직접 버퍼의 offset 위치에서 bytes 만큼 쓰기 시도. 실제로 쓴 바이트 수 또는 음수 오류 코드
    private static native int nativeWrite(long handle, ByteBuffer buf, int offset, int bytes);

    private static native long nativeFramesRead(long handle);

    private static native void nativeClose(long handle);

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private volatile boolean stopRequested = false;
    private volatile boolean paused = true;
    private volatile boolean finished = false;
    private volatile long positionUs = 0;
    private volatile String info = "";

    // 아래 필드는 재생 스레드에서만 사용
    private long handle = 0;
    private boolean streamStarted = false;
    private long framesWritten = 0;
    private int channels = 2;

    public AAudioPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    @Override
    public void load(Uri uri, long startMs, boolean autoPlay) {
        paused = !autoPlay;
        new Thread(() -> run(uri, startMs), "AAudioPlayer").start();
    }

    @Override
    public void play() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public boolean isPlaying() {
        return !paused && !finished;
    }

    @Override
    public long getPositionMs() {
        return positionUs / 1000;
    }

    @Override
    public String getInfo() {
        return info;
    }

    @Override
    public void release() {
        synchronized (lock) {
            stopRequested = true;
            lock.notifyAll();
        }
    }

    private void post(Runnable r) {
        main.post(() -> {
            if (!stopRequested) r.run();
        });
    }

    private void run(Uri uri, long startMs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            if (!libLoaded) {
                throw new IllegalStateException("네이티브 라이브러리를 불러오지 못했습니다");
            }
            extractor.setDataSource(context, uri, null);

            int audioIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioIndex = i;
                    inputFormat = f;
                    break;
                }
            }
            if (audioIndex < 0) throw new IllegalStateException("오디오 트랙이 없습니다");

            extractor.selectTrack(audioIndex);
            if (startMs > 0) {
                extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }

            codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            boolean readyNotified = false;

            while (!outputDone && !stopRequested) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int size = extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(bufInfo, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = codec.getOutputFormat();
                    if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            != AudioFormat.ENCODING_PCM_16BIT) {
                        throw new IllegalStateException("지원하지 않는 PCM 형식입니다");
                    }
                    openStream(out.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            out.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    if (!readyNotified) {
                        readyNotified = true;
                        post(listener::onReady);
                    }
                } else if (outIndex >= 0) {
                    if (bufInfo.size > 0 && handle != 0) {
                        waitIfPaused();
                        if (stopRequested) {
                            codec.releaseOutputBuffer(outIndex, false);
                            break;
                        }
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        int offset = bufInfo.offset;
                        int remaining = bufInfo.size;
                        while (remaining > 0 && !stopRequested) {
                            // 일시정지 요청이 들어오면 여기서도 멈춤
                            waitIfPaused();
                            if (stopRequested) break;
                            int w = nativeWrite(handle, outBuf, offset, remaining);
                            if (w < 0) throw new IllegalStateException("AAudio 오류: " + w);
                            offset += w;
                            remaining -= w;
                        }
                        framesWritten += (bufInfo.size - remaining) / (2L * channels);
                        positionUs = bufInfo.presentationTimeUs;
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((bufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            // 버퍼에 남은 소리가 끝까지 재생되길 기다림
            if (outputDone && handle != 0) {
                long lastRead = -1;
                long lastChange = SystemClock.uptimeMillis();
                while (!stopRequested) {
                    if (waitIfPaused()) lastChange = SystemClock.uptimeMillis();
                    if (stopRequested) break;
                    long read = nativeFramesRead(handle);
                    if (read >= framesWritten) break;
                    long now = SystemClock.uptimeMillis();
                    if (read != lastRead) {
                        lastRead = read;
                        lastChange = now;
                    } else if (now - lastChange > 1000) {
                        break;
                    }
                    Thread.sleep(20);
                }
            }

            finished = true;
            if (outputDone) {
                post(listener::onCompleted);
            }
        } catch (Exception e) {
            finished = true;
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            post(() -> listener.onError(msg));
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }
            extractor.release();
            if (handle != 0) {
                nativeClose(handle);
                handle = 0;
            }
        }
    }

    // 일시정지 상태면 스트림을 멈추고 대기, 재개되면 다시 시작. 대기했으면 true
    private boolean waitIfPaused() throws InterruptedException {
        boolean waited = false;
        synchronized (lock) {
            while (paused && !stopRequested) {
                if (streamStarted && handle != 0) {
                    nativePause(handle);
                    streamStarted = false;
                }
                waited = true;
                lock.wait();
            }
        }
        if (!stopRequested && !streamStarted && handle != 0) {
            int r = nativeStart(handle);
            if (r < 0) throw new IllegalStateException("AAudio 시작 실패: " + r);
            streamStarted = true;
        }
        return waited;
    }

    private void openStream(int sampleRate, int channelCount) {
        if (channelCount > 2) {
            throw new IllegalStateException("모노/스테레오 파일만 지원합니다");
        }
        if (handle != 0) {
            nativeClose(handle);
            handle = 0;
            streamStarted = false;
        }
        channels = channelCount;
        framesWritten = 0;

        handle = nativeOpen(sampleRate, channelCount);
        if (handle == 0) {
            throw new IllegalStateException("AAudio 스트림을 열 수 없습니다");
        }
        info = nativeGetInfo(handle);
    }
}