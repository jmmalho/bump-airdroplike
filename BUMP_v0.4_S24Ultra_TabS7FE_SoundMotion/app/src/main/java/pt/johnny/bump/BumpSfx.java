package pt.johnny.bump;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Original, quiet one-shot sounds and haptic patterns. No microphone, looping, or audio focus. */
final class BumpSfx {
    enum Cue { READY, SEND, INCOMING, SENT, RECEIVED, ERROR }

    private BumpSfx() { }

    static void play(Context context, Cue cue) {
        if (Prefs.sounds(context)) {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            // Respect silent and vibrate modes even when the media volume is non-zero.
            if (audio == null || audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL)
                playSound(context, cue);
        }
        if (Prefs.haptics(context)) vibrate(context, cue);
    }

    private static void playSound(Context context, Cue cue) {
        int resource;
        switch (cue) {
            case READY: resource = R.raw.bump_ready; break;
            case SEND: resource = R.raw.bump_send; break;
            case INCOMING: resource = R.raw.bump_incoming; break;
            case SENT: resource = R.raw.bump_sent; break;
            case RECEIVED: resource = R.raw.bump_received; break;
            default: resource = R.raw.bump_error;
        }
        MediaPlayer player = new MediaPlayer();
        try (android.content.res.AssetFileDescriptor fd = context.getResources().openRawResourceFd(resource)) {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            player.setOnPreparedListener(mp -> {
                try { mp.start(); } catch (IllegalStateException e) { mp.release(); }
            });
            player.setOnCompletionListener(MediaPlayer::release);
            player.setOnErrorListener((mp, what, extra) -> { mp.release(); return true; });
            player.setVolume(cue == Cue.RECEIVED ? .65f : .55f, cue == Cue.RECEIVED ? .65f : .55f);
            player.prepareAsync();
        } catch (Exception e) {
            try { player.release(); } catch (Exception ignored) { }
        }
    }

    private static void vibrate(Context context, Cue cue) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern;
            switch (cue) {
                case READY: pattern = new long[]{0, 12}; break;
                case SEND: pattern = new long[]{0, 18}; break;
                case INCOMING: pattern = new long[]{0, 13}; break;
                case RECEIVED: pattern = new long[]{0, 15, 76, 22}; break;
                case SENT: pattern = new long[]{0, 15, 70, 12}; break;
                default: pattern = new long[]{0, 20};
            }
            if (Build.VERSION.SDK_INT >= 26)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else vibrator.vibrate(pattern, -1);
        } catch (Exception ignored) { }
    }
}
