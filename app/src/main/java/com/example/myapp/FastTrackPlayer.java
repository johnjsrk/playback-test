package com.example.myapp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.ByteBuffer;

/**
 * Fast track(저지연) 재생용 플레이어.
 * MediaExtractor + MediaCodec 으로 디코딩한 PCM을
 * PERFORMANCE_MODE_LOW_LATENCY 로 만든 AudioTrack 에 직접 출력한다.
 * (모노/스테레오, 16bit PCM 출력만 지원)
 */
public class FastTrackPlayer implements LowLatencyPlayer {

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private volatile boolean stopRequested = false;
    private volatile boolean paused = true;
    private volatile boolean finished = false;
    private volatile long positionUs = 0;

    // 아래 필드는 재생 스레드에서만 사용
    private AudioTrack track;
    private boolean trackPlaying = false;
    private long framesWritten = 0;
    private int channels = 2;

    public FastTrackPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void load(Uri uri, long startMs, boolean autoPlay) {
        paused = !autoPlay;
        new Thread(() -> run(uri, startMs), "FastTrackPlayer").start();
    }

    public void play() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    public void pause() {
        paused = true;
    }

    public boolean isPlaying() {
        return !paused && !finished;
    }

    public long getPositionMs() {
        return positionUs / 1000;
    }

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

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
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

                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = codec.getOutputFormat();
                    if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            != AudioFormat.ENCODING_PCM_16BIT) {
                        throw new IllegalStateException("지원하지 않는 PCM 형식입니다");
                    }
                    createTrack(out.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            out.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    if (!readyNotified) {
                        readyNotified = true;
                        post(listener::onReady);
                    }
                } else if (outIndex >= 0) {
                    if (info.size > 0 && track != null) {
                        waitIfPaused();
                        if (stopRequested) {
                            codec.releaseOutputBuffer(outIndex, false);
                            break;
                        }
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        int remaining = info.size;
                        while (remaining > 0 && !stopRequested) {
                            int w = track.write(outBuf, remaining, AudioTrack.WRITE_BLOCKING);
                            if (w < 0) throw new IllegalStateException("AudioTrack 오류: " + w);
                            remaining -= w;
                        }
                        framesWritten += info.size / (2L * channels);
                        positionUs = info.presentationTimeUs;
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            // 버퍼에 남은 소리가 끝까지 재생되길 기다림
            if (outputDone && track != null) {
                long lastHead = -1;
                long lastChange = SystemClock.uptimeMillis();
                while (!stopRequested) {
                    if (waitIfPaused()) lastChange = SystemClock.uptimeMillis();
                    if (stopRequested) break;
                    long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    if (head >= framesWritten) break;
                    long now = SystemClock.uptimeMillis();
                    if (head != lastHead) {
                        lastHead = head;
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
            if (track != null) {
                try {
                    track.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // 일시정지 상태면 트랙을 멈추고 대기, 재개되면 트랙을 다시 시작. 대기했으면 true
    private boolean waitIfPaused() throws InterruptedException {
        boolean waited = false;
        synchronized (lock) {
            while (paused && !stopRequested) {
                if (trackPlaying && track != null) {
                    track.pause();
                    trackPlaying = false;
                }
                waited = true;
                lock.wait();
            }
        }
        if (!stopRequested && !trackPlaying && track != null) {
            track.play();
            trackPlaying = true;
        }
        return waited;
    }

    private void createTrack(int sampleRate, int channelCount) {
        if (channelCount > 2) {
            throw new IllegalStateException("모노/스테레오 파일만 지원합니다");
        }
        if (track != null) {
            track.release();
            track = null;
            trackPlaying = false;
        }
        channels = channelCount;
        framesWritten = 0;

        int mask = channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, mask,
                AudioFormat.ENCODING_PCM_16BIT);

        AudioTrack.Builder b = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(mask)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf);

        // Fast track(저지연 경로) 요청. Android 8.0(API 26) 이상
        if (Build.VERSION.SDK_INT >= 26) {
            b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        track = b.build();
        trackPlaying = false;
    }
}