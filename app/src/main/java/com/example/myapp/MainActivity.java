package com.example.myapp;

import android.content.Context;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.exoplayer.ExoPlayer;

public class MainActivity extends AppCompatActivity {

    private static final int MODE_DEEP = 0;
    private static final int MODE_OFFLOAD = 1;
    private static final int MODE_FAST = 2;
    private static final int MODE_AAUDIO = 3;

    private ExoPlayer player;          // Deep / Offload 재생용
    private LowLatencyPlayer ll;       // Fast track / AAudio 재생용
    private int activeMode = MODE_DEEP;
    private boolean llEnded = false;

    private TextView tvFileName;
    private TextView tvInfo;
    private TextView tvTime;
    private TextView tvVolume;
    private Button btnPlayPause;
    private RadioGroup rgMode;
    private Uri currentUri;
    private long durationMs = 0;

    private AudioManager audioManager;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // 0.25초마다 재생 시간과 볼륨 표시를 갱신 (하드웨어 볼륨 키로 바꾼 값도 반영됨)
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateTime();
            updateVolume();
            ui.postDelayed(this, 250);
        }
    };

    // 파일 선택기 (저장소 권한 불필요)
    private final ActivityResultLauncher<String[]> openAudio =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    loadAudio(uri);
                }
            });

    private final LowLatencyPlayer.Listener llListener = new LowLatencyPlayer.Listener() {
        @Override
        public void onReady() {
            btnPlayPause.setEnabled(true);
            btnPlayPause.setText(ll != null && ll.isPlaying() ? "일시정지" : "재생");
            tvInfo.setText(ll != null ? ll.getInfo() : "");
        }

        @Override
        public void onCompleted() {
            llEnded = true;
            btnPlayPause.setText("재생");
        }

        @Override
        public void onError(String message) {
            Toast.makeText(MainActivity.this,
                    "재생할 수 없습니다: " + message, Toast.LENGTH_LONG).show();
            resetUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvFileName = findViewById(R.id.tvFileName);
        tvInfo = findViewById(R.id.tvInfo);
        tvTime = findViewById(R.id.tvTime);
        tvVolume = findViewById(R.id.tvVolume);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        rgMode = findViewById(R.id.rgMode);
        Button btnOpen = findViewById(R.id.btnOpen);
        Button btnVolUp = findViewById(R.id.btnVolUp);
        Button btnVolDown = findViewById(R.id.btnVolDown);

        // 볼륨 키가 미디어 볼륨을 조절하도록 지정
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true);
        applyOffloadPreference();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (isLowLatency(activeMode)) return;
                if (state == Player.STATE_READY) {
                    btnPlayPause.setEnabled(true);
                } else if (state == Player.STATE_ENDED) {
                    player.pause();
                    player.seekTo(0);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isLowLatency(activeMode)) return;
                btnPlayPause.setText(isPlaying ? "일시정지" : "재생");
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (isLowLatency(activeMode)) return;
                Toast.makeText(MainActivity.this,
                        "재생할 수 없는 파일입니다", Toast.LENGTH_SHORT).show();
                resetUi();
            }
        });

        btnOpen.setOnClickListener(v -> openAudio.launch(new String[]{"audio/*"}));
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnVolUp.setOnClickListener(v -> changeVolume(AudioManager.ADJUST_RAISE));
        btnVolDown.setOnClickListener(v -> changeVolume(AudioManager.ADJUST_LOWER));

        // 재생 모드 변경 시 현재 위치에서 이어서 재생
        rgMode.setOnCheckedChangeListener((group, checkedId) -> switchMode());
    }

    private void changeVolume(int direction) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        updateVolume();
    }

    private void updateVolume() {
        int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        tvVolume.setText("볼륨 " + cur + " / " + max);
    }

    private void updateTime() {
        long pos = 0;
        long dur = durationMs;
        if (currentUri != null) {
            if (isLowLatency(activeMode)) {
                // 저지연 모드의 위치는 출력 버퍼에 넣은 지점이라 실제 소리보다 아주 조금 앞섬
                pos = (ll != null && !llEnded) ? ll.getPositionMs() : 0;
            } else {
                pos = player.getCurrentPosition();
                if (dur <= 0) {
                    long d = player.getDuration();
                    if (d != C.TIME_UNSET) dur = d;
                }
            }
        }
        if (dur > 0 && pos > dur) pos = dur;
        if (pos < 0) pos = 0;
        tvTime.setText(formatTime(pos) + " / " + formatTime(dur));
    }

    private static String formatTime(long ms) {
        long totalSec = Math.max(0, ms) / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }

    // 파일의 전체 재생 시간(ms). 알 수 없으면 0
    private long readDurationMs(Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    private int selectedMode() {
        int id = rgMode.getCheckedRadioButtonId();
        if (id == R.id.rbOffload) return MODE_OFFLOAD;
        if (id == R.id.rbFast) return MODE_FAST;
        if (id == R.id.rbAAudio) return MODE_AAUDIO;
        return MODE_DEEP;
    }

    private static boolean isLowLatency(int mode) {
        return mode == MODE_FAST || mode == MODE_AAUDIO;
    }

    // Offload 사용 여부 설정. Offload 선택 시 기기가 해당 형식을 지원하면 사용하고,
    // 지원하지 않으면 자동으로 일반(PCM) 경로로 재생됨
    private void applyOffloadPreference() {
        int mode = activeMode == MODE_OFFLOAD
                ? TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                : TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED;

        TrackSelectionParameters.AudioOffloadPreferences prefs =
                new TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(mode)
                        .build();

        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters()
                        .buildUpon()
                        .setAudioOffloadPreferences(prefs)
                        .build());
    }

    private void switchMode() {
        int newMode = selectedMode();
        if (newMode == activeMode) return;

        boolean wasPlaying = false;
        long position = 0;
        if (currentUri != null) {
            if (isLowLatency(activeMode)) {
                if (ll != null && !llEnded) {
                    wasPlaying = ll.isPlaying();
                    position = ll.getPositionMs();
                }
            } else {
                wasPlaying = player.isPlaying();
                position = player.getCurrentPosition();
            }
        }

        stopActive();
        activeMode = newMode;
        if (currentUri != null) {
            startCurrent(position, wasPlaying);
        }
    }

    private void stopActive() {
        if (ll != null) {
            ll.release();
            ll = null;
        }
        tvInfo.setText("");
        player.stop();
        player.clearMediaItems();
    }

    // 현재 파일을 선택된 모드로 시작
    private void startCurrent(long positionMs, boolean autoPlay) {
        btnPlayPause.setEnabled(false);
        if (isLowLatency(activeMode)) {
            if (ll != null) ll.release();
            llEnded = false;
            ll = activeMode == MODE_AAUDIO
                    ? new AAudioPlayer(this, llListener)
                    : new FastTrackPlayer(this, llListener);
            ll.load(currentUri, positionMs, autoPlay);
        } else {
            applyOffloadPreference();
            player.setMediaItem(MediaItem.fromUri(currentUri), positionMs);
            player.prepare();
            player.setPlayWhenReady(autoPlay);
        }
    }

    private void loadAudio(Uri uri) {
        stopActive();
        currentUri = uri;
        durationMs = readDurationMs(uri);
        tvFileName.setText(getFileName(uri));
        btnPlayPause.setText("재생");
        startCurrent(0, false);
    }

    private void togglePlayPause() {
        if (currentUri == null) return;

        if (isLowLatency(activeMode)) {
            if (llEnded) {              // 끝까지 재생된 뒤에는 처음부터 다시
                startCurrent(0, true);
                return;
            }
            if (ll == null) return;
            if (ll.isPlaying()) {
                ll.pause();
                btnPlayPause.setText("재생");
            } else {
                ll.play();
                btnPlayPause.setText("일시정지");
            }
        } else {
            if (player.isPlaying()) {
                player.pause();
            } else {
                if (player.getPlaybackState() == Player.STATE_ENDED) {
                    player.seekTo(0);
                }
                player.play();
            }
        }
    }

    private void resetUi() {
        stopActive();
        currentUri = null;
        durationMs = 0;
        tvFileName.setText("선택된 파일 없음");
        btnPlayPause.setEnabled(false);
        btnPlayPause.setText("재생");
    }

    private String getFileName(Uri uri) {
        String name = "알 수 없는 파일";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cursor.getString(idx);
                }
            }
        }
        return name;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ll != null) {
            ll.release();
            ll = null;
        }
        player.release();
    }
}