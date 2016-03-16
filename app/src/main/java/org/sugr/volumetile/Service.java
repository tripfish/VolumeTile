package org.sugr.volumetile;

import android.app.AlertDialog;
import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.SeekBar;

import com.jakewharton.rxbinding.view.RxView;
import com.jakewharton.rxbinding.widget.RxSeekBar;

import butterknife.Bind;
import butterknife.ButterKnife;
import rx.Subscription;
import rx.subjects.PublishSubject;

public class Service extends android.service.quicksettings.TileService {
    private AudioManager audioManager;
    private PublishSubject<State> volumeState = PublishSubject.create();
    private PublishSubject<State> managerState = PublishSubject.create();
    private ViewHolder holder;

    @Override
    public void onCreate() {
        super.onCreate();

        audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void onClick() {
        if (isLocked()) {
            unlockAndRun(this::createAndShow);
        } else {
            createAndShow();
        }
    }

    private void createAndShow() {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(root);

        AlertDialog dialog = builder.create();
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        holder = new ViewHolder();

        ButterKnife.bind(holder, root);

        Subscription volumeSubscription = volumeState.subscribe(state -> {
            SeekBar bar;
            ImageView mute;
            ImageView unmute;
            switch (state.stream) {
                case AudioManager.STREAM_MUSIC:
                    bar = holder.mediaSeek;
                    mute = holder.mediaMute;
                    unmute = holder.mediaUnmute;
                    break;
                case AudioManager.STREAM_ALARM:
                    bar = holder.alarmSeek;
                    mute = holder.alarmMute;
                    unmute = holder.alarmUnmute;
                    break;
                case AudioManager.STREAM_RING:
                    bar = holder.ringSeek;
                    mute = holder.ringMute;
                    unmute = holder.ringUnmute;
                    break;
                case AudioManager.STREAM_NOTIFICATION:
                    bar = holder.notificationSeek;
                    mute = holder.notificationMute;
                    unmute = holder.notificationUnmute;
                    break;
                default:
                    return;
            }

            if (state.volume == -1) {
                if (state.muted) {
                    bar.setProgress(0);
                    mute.setVisibility(View.GONE);
                    unmute.setVisibility(View.VISIBLE);
                } else {
                    int current = audioManager.getStreamVolume(state.stream);
                    bar.setProgress(current);
                    mute.setVisibility(View.VISIBLE);
                    unmute.setVisibility(View.GONE);
                }
            } else {
                bar.setProgress(state.volume);
            }
        });

        Subscription managerSubscription = managerState.subscribe(state -> {
            if (state.volume == -1) {
                if (state.muted) {
                    audioManager.adjustStreamVolume(state.stream, AudioManager.ADJUST_MUTE, 0);
                } else {
                    audioManager.adjustStreamVolume(state.stream, AudioManager.ADJUST_UNMUTE, 0);
                }
            } else {
                if (audioManager.isStreamMute(state.stream)) {
                    audioManager.adjustStreamVolume(state.stream, AudioManager.ADJUST_UNMUTE, 0);
                }
                audioManager.setStreamVolume(state.stream, state.volume, AudioManager.FLAG_PLAY_SOUND);
            }
        });

        dialog.setOnDismissListener(d -> {
            volumeSubscription.unsubscribe();
            managerSubscription.unsubscribe();
            holder = null;
        });

        boolean voiceCapable = isVoiceCapable();

        setupVolume(holder.mediaSeek, holder.mediaMute, holder.mediaUnmute, AudioManager.STREAM_MUSIC);
        setupVolume(holder.alarmSeek, holder.alarmMute, holder.alarmUnmute, AudioManager.STREAM_ALARM);

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

        volumeState.onNext(new State(stream, audioManager.getStreamVolume(stream), audioManager.isStreamMute(stream)));

        RxSeekBar.changeEvents(bar).subscribe(event -> {
        });
        RxSeekBar.changes(bar).subscribe(v -> {
            if (audioManager.isStreamMute(stream)) {
                volumeState.onNext(new State(stream, -1, false));
            }
            managerState.onNext(new State(stream, v, false));
        });

        RxView.clicks(mute).subscribe(v -> {
            volumeState.onNext(new State(stream, -1, true));
            managerState.onNext(new State(stream, -1, true));
        });

        RxView.clicks(unmute).subscribe(v -> {
            volumeState.onNext(new State(stream, -1, false));
            managerState.onNext(new State(stream, -1, false));
        });
    }

    private boolean isVoiceCapable() {
        TelephonyManager telephony = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        return telephony != null && telephony.isVoiceCapable();
    }

    class ViewHolder {
        @Bind(R.id.media_mute) ImageView mediaMute;
        @Bind(R.id.media_unmute) ImageView mediaUnmute;
        @Bind(R.id.media_seek) SeekBar mediaSeek;

        @Bind(R.id.alarm_mute) ImageView alarmMute;
        @Bind(R.id.alarm_unmute) ImageView alarmUnmute;
        @Bind(R.id.alarm_seek) SeekBar alarmSeek;

        @Bind(R.id.ring_mute) ImageView ringMute;
        @Bind(R.id.ring_unmute) ImageView ringUnmute;
        @Bind(R.id.ring_seek) SeekBar ringSeek;

        @Bind(R.id.notification_mute) ImageView notificationMute;
        @Bind(R.id.notification_unmute) ImageView notificationUnmute;
        @Bind(R.id.notification_seek) SeekBar notificationSeek;

        @Bind(R.id.notification_row) ViewGroup notificationRow;
    }

    class State {
        int stream;
        int volume;
        boolean muted;

        public State(int stream, int volume, boolean muted) {
            this.stream = stream;
            this.volume = volume;
            this.muted = muted;
        }
    }
}
