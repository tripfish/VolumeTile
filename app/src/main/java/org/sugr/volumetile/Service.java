package org.sugr.volumetile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.telephony.TelephonyManager;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Toast;

import com.jakewharton.rxbinding.view.RxView;
import com.jakewharton.rxbinding.widget.RxSeekBar;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import butterknife.BindView;
import butterknife.ButterKnife;
import rx.Observable;
import rx.Subscription;
import rx.android.MainThreadSubscription;
import rx.subjects.PublishSubject;

public class Service extends android.service.quicksettings.TileService {
    private AudioManager audioManager;
    private PublishSubject<State> uiState = PublishSubject.create();
    private PublishSubject<State> volumeState = PublishSubject.create();
    private ViewHolder holder;
    private int focusedStream = AudioManager.STREAM_MUSIC;
    private Map<Integer, Integer> streamVolumeCache = new HashMap<>();
    private Subscription closeSubscription;
    private Subscription volumePollingSubscription;

    @Override
    public void onCreate() {
        super.onCreate();

        audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void onClick() {

        NotificationManager notificationManager =
                (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !notificationManager.isNotificationPolicyAccessGranted()) {

            Intent closeIntent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            getApplicationContext().sendBroadcast(closeIntent);

            Intent policyIntent = new Intent(
                    android.provider.Settings
                            .ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            startActivity(policyIntent);

            Toast.makeText(getApplicationContext(), "Please grant DND access.",
                    Toast.LENGTH_LONG).show();

        } else {

            if (isLocked()) {
                unlockAndRun(this::createAndShow);
            } else {
                createAndShow();
            }
        }
    }

    @Override
    public void onStartListening() {
        super.onStartListening();

        volumePollingSubscription = Observable.just(0l)
                .mergeWith(Observable.interval(500, TimeUnit.MILLISECONDS))
                .subscribe(t -> {
                    int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

                    Tile tile = getQsTile();
                    tile.setLabel(
                        getString(R.string.tile_level_label, (int) (volume * 100 / max))
                    );
                    tile.updateTile();
                });
    }

    @Override
    public void onStopListening() {
        super.onStopListening();

        if (volumePollingSubscription != null) {
            volumePollingSubscription.unsubscribe();
        }

        Tile tile = getQsTile();
        tile.setLabel(getString(R.string.tile_label));
        tile.updateTile();
    }

    private void createAndShow() {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.AppTheme));
        builder.setView(root);

        AlertDialog dialog = builder.create();
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        holder = new ViewHolder(root);

        Observable closeObservable = Observable.interval(2000, 20, TimeUnit.MILLISECONDS).doOnEach(t -> {
            holder.closeProgress.incrementProgressBy(-1);
            if (holder.closeProgress.getProgress() == 0) {
                dialog.dismiss();
            }
        });
        closeSubscription = closeObservable.subscribe();

        Subscription uiStateSubscription = uiState.map(
                state -> new ViewTupleState(holder.tuples.get(state.stream), state)
        ).doOnEach(tupleState -> {
            closeSubscription.unsubscribe();
            closeSubscription = closeObservable.subscribe();
            holder.closeProgress.setProgress(100);
        }).subscribe(tupleState -> {
            if (tupleState.state.muted || tupleState.state.volume == 0) {
                tupleState.tuple.bar.animate().alpha(0.3f).start();
                tupleState.tuple.bar.setProgress(0);
                tupleState.tuple.mute.setVisibility(View.GONE);
                tupleState.tuple.unmute.setVisibility(View.VISIBLE);
            } else {
                if (tupleState.state.volume == -1 || tupleState.tuple.bar.getAlpha() != 1f) {
                    if (tupleState.state.volume == -1) {
                        tupleState.state.volume = intOrDefault(streamVolumeCache.get(tupleState.state.stream));
                        if (tupleState.state.volume == 0) {
                            tupleState.state.volume = audioManager.getStreamVolume(tupleState.state.stream);
                        }
                        if (tupleState.state.volume == 0) {
                            tupleState.state.volume = tupleState.tuple.bar.getMax() / 10;
                            if (tupleState.state.volume == 0) {
                                tupleState.state.volume = 1;
                            }
                        }
                    }
                    tupleState.tuple.bar.animate().alpha(1f).start();
                    tupleState.tuple.mute.setVisibility(View.VISIBLE);
                    tupleState.tuple.unmute.setVisibility(View.GONE);
                }

                if (tupleState.state.volume > -1) {
                    tupleState.tuple.bar.setProgress(tupleState.state.volume);
                }
            }
        });

        Subscription volumeStateSubscription = volumeState.subscribe(state -> {
            if (state.muted || state.volume == 0) {
                streamVolumeCache.put(state.stream, audioManager.getStreamVolume(state.stream));
                audioManager.adjustStreamVolume(state.stream, AudioManager.ADJUST_MUTE, 0);
                audioManager.setStreamVolume(state.stream, 0, AudioManager.FLAG_PLAY_SOUND);
            } else {
                if (state.volume == -1 || audioManager.isStreamMute(state.stream)) {
                    if (state.volume == -1) {
                        if (audioManager.getStreamVolume(state.stream) == 0) {
                            state.volume = intOrDefault(streamVolumeCache.get(state.stream));
                            if (state.stream == 0) {
                                state.volume = audioManager.getStreamMaxVolume(state.stream) / 10;
                            }
                            if (state.volume == 0) {
                                state.volume = 1;
                            }
                        }
                    }
                    audioManager.adjustStreamVolume(state.stream, AudioManager.ADJUST_UNMUTE, 0);
                }

                if (state.volume > -1) {
                    audioManager.setStreamVolume(state.stream, state.volume, AudioManager.FLAG_PLAY_SOUND);
                }
            }
        });

        Subscription dialogKeySubscription = Observable.<Integer>create(subscriber -> {
            dialog.setOnKeyListener((dialogInterface, i, keyEvent) -> {
                if (!subscriber.isUnsubscribed() && keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (i) {
                        case KeyEvent.KEYCODE_VOLUME_DOWN:
                        case KeyEvent.KEYCODE_VOLUME_UP:
                        case KeyEvent.KEYCODE_VOLUME_MUTE:
                            subscriber.onNext(i);
                            return true;
                    }
                }
                return false;
            });

            subscriber.add(new MainThreadSubscription() {
                @Override protected void onUnsubscribe() {
                    dialog.setOnKeyListener(null);
                }
            });
        }).subscribe(i -> {
            int current = audioManager.getStreamVolume(focusedStream);
            int max  = audioManager.getStreamMaxVolume(focusedStream);
            int step = audioManager.getStreamMaxVolume(focusedStream) / 10;
            if (step == 0) {
                step = 1;
            }

            State state;
            switch (i) {
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    if (current - step < 0) {
                        return;
                    }
                    state = new State(focusedStream, current - step, false);

                    break;
                case KeyEvent.KEYCODE_VOLUME_UP:
                    if (current + step > max) {
                        return;
                    }
                    state = new State(focusedStream, current + step, false);

                    break;
                case KeyEvent.KEYCODE_VOLUME_MUTE:
                    if (audioManager.isStreamMute(focusedStream))  {
                        state = new State(focusedStream, -1, true);
                    } else {
                        if (current == 0) {
                            current += step;
                        }
                        state = new State(focusedStream, current, false);
                    }

                    break;
                default:
                    return;
            }

            volumeState.onNext(state);
            uiState.onNext(state);
        });

        dialog.setOnDismissListener(d -> {
            uiStateSubscription.unsubscribe();
            volumeStateSubscription.unsubscribe();
            dialogKeySubscription.unsubscribe();
            closeSubscription.unsubscribe();
            holder = null;
        });

        boolean voiceCapable = isVoiceCapable();

        setupVolume(holder.mediaSeek, holder.mediaMute, holder.mediaUnmute, AudioManager.STREAM_MUSIC);
        setupVolume(holder.alarmSeek, holder.alarmMute, holder.alarmUnmute, AudioManager.STREAM_ALARM);
        setupVolume(holder.voiceSeek, holder.voiceMute, holder.voiceUnmute, AudioManager.STREAM_VOICE_CALL);

        if (voiceCapable) {
            setupVolume(holder.ringSeek, holder.ringMute, holder.ringUnmute, AudioManager.STREAM_RING);
        } else {
            holder.ringSeek.setVisibility(View.GONE);
        }

        boolean linked = Settings.System.getInt(getContentResolver(), "notifications_use_ring_volume", 1) == 1;
        if (linked && voiceCapable) {
            holder.notificationRow.setVisibility(View.GONE);
        } else {
            setupVolume(holder.notificationSeek, holder.notificationMute, holder.notificationUnmute, AudioManager.STREAM_NOTIFICATION);
        }

        showDialog(dialog);
    }

    private void setupVolume(SeekBar bar, ImageView mute, ImageView unmute, int stream) {
        int max = audioManager.getStreamMaxVolume(stream);

        bar.setMax(max);

        uiState.onNext(new State(stream, audioManager.getStreamVolume(stream), audioManager.isStreamMute(stream)));

        // Skip the initial value to avoid setting the focused stream
        RxSeekBar.userChanges(bar).skip(1).subscribe(v -> {
            focusedStream = stream;
            uiState.onNext(new State(stream, v, false));
            volumeState.onNext(new State(stream, v, false));
        });

        RxView.clicks(mute).subscribe(v -> {
            focusedStream = stream;
            uiState.onNext(new State(stream, -1, true));
            volumeState.onNext(new State(stream, -1, true));
        });

        RxView.clicks(unmute).subscribe(v -> {
            focusedStream = stream;
            uiState.onNext(new State(stream, -1, false));
            volumeState.onNext(new State(stream, -1, false));
        });
    }

    private boolean isVoiceCapable() {
        TelephonyManager telephony = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        return telephony != null && telephony.isVoiceCapable();
    }

    private static int intOrDefault(Integer in) {
        if (in == null) {
            return 0;
        }

        return in;
    }

    static class ViewHolder {
        @BindView(R.id.media_mute) ImageView mediaMute;
        @BindView(R.id.media_unmute) ImageView mediaUnmute;
        @BindView(R.id.media_seek) SeekBar mediaSeek;

        @BindView(R.id.alarm_mute) ImageView alarmMute;
        @BindView(R.id.alarm_unmute) ImageView alarmUnmute;
        @BindView(R.id.alarm_seek) SeekBar alarmSeek;

        @BindView(R.id.voice_mute) ImageView voiceMute;
        @BindView(R.id.voice_unmute) ImageView voiceUnmute;
        @BindView(R.id.voice_seek) SeekBar voiceSeek;

        @BindView(R.id.ring_mute) ImageView ringMute;
        @BindView(R.id.ring_unmute) ImageView ringUnmute;
        @BindView(R.id.ring_seek) SeekBar ringSeek;

        @BindView(R.id.notification_mute) ImageView notificationMute;
        @BindView(R.id.notification_unmute) ImageView notificationUnmute;
        @BindView(R.id.notification_seek) SeekBar notificationSeek;

        @BindView(R.id.notification_row) ViewGroup notificationRow;

        @BindView(R.id.close_progress) ProgressBar closeProgress;

        private Map<Integer, ViewTuple> tuples = new HashMap<>();

        ViewHolder(View root) {
            ButterKnife.bind(this, root);

            tuples.put(AudioManager.STREAM_MUSIC, new ViewTuple(mediaMute, mediaUnmute, mediaSeek));
            tuples.put(AudioManager.STREAM_ALARM, new ViewTuple(alarmMute, alarmUnmute, alarmSeek));
            tuples.put(AudioManager.STREAM_VOICE_CALL, new ViewTuple(voiceMute, voiceUnmute, voiceSeek));
            tuples.put(AudioManager.STREAM_RING, new ViewTuple(ringMute, ringUnmute, ringSeek));
            tuples.put(AudioManager.STREAM_NOTIFICATION, new ViewTuple(notificationMute, notificationUnmute, notificationSeek));
        }

    }

    private static class State {
        int stream;
        int volume;
        boolean muted;

        public State(int stream, int volume, boolean muted) {
            this.stream = stream;
            this.volume = volume;
            this.muted = muted;
        }
    }

    private static class ViewTuple {
        ImageView mute;
        ImageView unmute;
        SeekBar bar;

        public ViewTuple(ImageView mute, ImageView unmute, SeekBar bar) {
            this.mute = mute;
            this.unmute = unmute;
            this.bar = bar;
        }
    }

    private static class ViewTupleState {
        ViewTuple tuple;
        State state;

        ViewTupleState(ViewTuple tuple, State state) {
            this.tuple = tuple;
            this.state = state;
        }
    }
}
