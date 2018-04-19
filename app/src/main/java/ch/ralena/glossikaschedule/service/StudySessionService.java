package ch.ralena.glossikaschedule.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSessionManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import java.io.IOException;

import ch.ralena.glossikaschedule.R;
import ch.ralena.glossikaschedule.object.Day;
import ch.ralena.glossikaschedule.object.SentencePair;
import ch.ralena.glossikaschedule.utils.Utils;
import io.realm.Realm;

public class StudySessionService extends Service implements MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener {
	public static final String BROADCAST_START_SESSION = "broadcast_start_session";
	public static final String ACTION_PLAY = "action_play";
	public static final int ACTION_ID_PLAY = 0;
	public static final String ACTION_PAUSE = "action_pause";
	public static final int ACTION_ID_PAUSE = 1;
	public static final String ACTION_PREVIOUS = "action_previous";
	public static final int ACTION_ID_PREVIOUS = 2;
	public static final String ACTION_NEXT = "action_next";
	public static final int ACTION_ID_NEXT = 3;

	private static final int NOTIFICATION_ID = 1337;
	private static final String CHANNEL_ID = "GLOSSIKA_1337";

	// Media Session
	private MediaSessionManager mediaSessionManager;
	private MediaSessionCompat mediaSession;
	private MediaControllerCompat.TransportControls transportControls;

	public enum PlaybackStatus {
		PLAYING, PAUSED
	}

	private MediaPlayer mediaPlayer;
	private Day day;
	private int stopPosition;
	private AudioManager audioManager;
	private boolean inCall = false;
	private PhoneStateListener phoneStateListener;
	private TelephonyManager telephonyManager;
	private Realm realm;
	private SentencePair sentencePair;

	// given to clients that connect to the service
	StudyBinder binder = new StudyBinder();

	// Broadcast Receivers
	private BroadcastReceiver becomingNoisyReceiver = new BecomingNoisyReceiver();
	private BroadcastReceiver startSessionReceiver = new StartSessionReceiver();

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// check if we have attached our bundle or not
		if (intent.getExtras() == null)
			stopSelf();

		realm = Realm.getDefaultInstance();

		String id = new Utils.Storage(getApplicationContext()).getDayId();
		day = realm.where(Day.class).equalTo("id", id).findFirst();
		if (day == null)
			stopSelf();
		day.resetReviews(realm);

		if (!requestAudioFocus())
			stopSelf();

		playSentence();

		if (mediaSessionManager == null) {
			initMediaSession();
			buildNotification(PlaybackStatus.PLAYING);
		}

		handleIncomingActions(intent);

		return super.onStartCommand(intent, flags, startId);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		callStateListener();
		registerBecomingNoisyReceiver();
		registerStartSessionReceiver();
	}


	@Override
	public void onDestroy() {
		super.onDestroy();

		// stop media from playing
		if (mediaPlayer != null) {
			stop();
			mediaPlayer.release();
		}
		removeAudioFocus();

		// cancel the phone state listener
		if (phoneStateListener != null) {
			telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
		}
//		removeNotification();

		// unregister broadcast receivers
		unregisterReceiver(becomingNoisyReceiver);
		unregisterReceiver(startSessionReceiver);
	}

	// --- setup ---

	private void playSentence() {
		sentencePair = day.getNextSentencePair(realm);
		mediaPlayer = new MediaPlayer();
		mediaPlayer.setOnCompletionListener(this);
		mediaPlayer.reset();
		mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
		try {
			// load sentence path into mediaplayer to be played
			mediaPlayer.setDataSource(sentencePair.getTargetSentence().getUri());
			mediaPlayer.prepare();
		} catch (IOException e) {
			e.printStackTrace();
			stopSelf();
		}
		play();
	}

	// --- managing media ---

	private void initMediaSession() {
		if (mediaSessionManager != null)
			return;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
		}
		mediaSession = new MediaSessionCompat(getApplicationContext(), "GlossikaNative");
		transportControls = mediaSession.getController().getTransportControls();
		mediaSession.setActive(true);
		mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
		updateMetaData();
		mediaSession.setCallback(new MediaSessionCompat.Callback() {
			@Override
			public void onPlay() {
				super.onPlay();
				resume();
				buildNotification(PlaybackStatus.PLAYING);
			}

			@Override
			public void onPause() {
				super.onPause();
				pause();
				buildNotification(PlaybackStatus.PAUSED);
			}

			@Override
			public void onSkipToNext() {
				super.onSkipToNext();
				nextSentence();
				buildNotification(PlaybackStatus.PLAYING);
			}

			@Override
			public void onSkipToPrevious() {
				super.onSkipToPrevious();
				previousSentence();
				buildNotification(PlaybackStatus.PLAYING);
			}
		});
	}

	private void updateMetaData() {
		mediaSession.setMetadata(
				new MediaMetadataCompat.Builder()
						.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sentencePair.getBaseSentence().getText())
						.putString(MediaMetadataCompat.METADATA_KEY_TITLE, sentencePair.getTargetSentence().getText())
						.build()
		);
	}

	private void play() {
		if (!mediaPlayer.isPlaying()) {
			mediaPlayer.start();
		}
	}

	private void stop() {
		if (mediaPlayer != null && mediaPlayer.isPlaying()) {
			mediaPlayer.stop();
		}
	}

	private void pause() {
		if (mediaPlayer != null && mediaPlayer.isPlaying()) {
			mediaPlayer.pause();
			stopPosition = mediaPlayer.getCurrentPosition();
		}
	}

	private void resume() {
		if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
			mediaPlayer.seekTo(stopPosition);
			mediaPlayer.start();
		}
	}

	private void nextSentence() {

	}

	private void previousSentence() {

	}

	private void setVolume(float volume) {
		if (mediaPlayer.isPlaying()) {
			mediaPlayer.setVolume(volume, volume);
		}
	}

	// --- notification ---

	private void buildNotification(PlaybackStatus playbackStatus) {
		int playPauseDrawable = android.R.drawable.ic_media_pause;
		PendingIntent playPauseAction = null;

		if (playbackStatus == PlaybackStatus.PLAYING) {
			playPauseDrawable = android.R.drawable.ic_media_pause;
			playPauseAction = iconAction(ACTION_ID_PAUSE);
		} else if (playbackStatus == PlaybackStatus.PAUSED) {
			playPauseDrawable = android.R.drawable.ic_media_play;
			playPauseAction = iconAction(ACTION_ID_PLAY);
		}

		// create the notification channel
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			// Create the NotificationChannel, but only on API 26+ because
			// the NotificationChannel class is new and not in the support library
			CharSequence name = "GlossikaNativeChannel";
			String description = "Glossika stuff";
			int importance = NotificationManager.IMPORTANCE_LOW;
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
			channel.setDescription(description);
			// Register the channel with the system
			notificationManager.createNotificationChannel(channel);
		}


		// create the notification
		Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setShowWhen(false)
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setSmallIcon(R.drawable.clock)
				.setStyle(
						new android.support.v4.media.app.NotificationCompat.MediaStyle()
								.setMediaSession(mediaSession.getSessionToken())
								.setShowActionsInCompactView(0, 1, 2)
				).setColor(getResources().getColor(R.color.colorPrimary))
				.setContentText(sentencePair.getBaseSentence().getText())
				.setContentTitle(sentencePair.getTargetSentence().getText())
				.addAction(android.R.drawable.ic_media_previous, "prev sentence", iconAction(ACTION_ID_PREVIOUS))
				.addAction(playPauseDrawable, "pause", playPauseAction)
				.addAction(android.R.drawable.ic_media_next, "next sentence", iconAction(ACTION_ID_NEXT))
				.build();
		notificationManager.notify(NOTIFICATION_ID, notification);

	}

	private PendingIntent iconAction(int actionId) {
		Intent iconIntent = new Intent(this, StudySessionService.class);
		switch (actionId) {
			case ACTION_ID_PLAY:
				iconIntent.setAction(ACTION_PLAY);
				break;
			case ACTION_ID_PAUSE:
				iconIntent.setAction(ACTION_PAUSE);
				break;
			case ACTION_ID_NEXT:
				iconIntent.setAction(ACTION_NEXT);
				break;
			case ACTION_ID_PREVIOUS:
				iconIntent.setAction(ACTION_PREVIOUS);
				break;
			default:
				return null;
		}
		return PendingIntent.getService(this, actionId, iconIntent, 0);
	}

	private void handleIncomingActions(Intent playbackAction) {
		if (playbackAction == null || playbackAction.getAction() == null) return;

		String actionString = playbackAction.getAction();
		if (actionString.equalsIgnoreCase(ACTION_PLAY)) {
			transportControls.play();
		} else if (actionString.equalsIgnoreCase(ACTION_PAUSE)) {
			transportControls.pause();
		} else if (actionString.equalsIgnoreCase(ACTION_NEXT)) {
			transportControls.skipToNext();
		} else if (actionString.equalsIgnoreCase(ACTION_PREVIOUS)) {
			transportControls.skipToPrevious();
		}
	}


	// --- call state listener

	private void callStateListener() {
		telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		phoneStateListener = new PhoneStateListener() {
			@Override
			public void onCallStateChanged(int state, String incomingNumber) {
				switch (state) {
					case TelephonyManager.CALL_STATE_OFFHOOK:
					case TelephonyManager.CALL_STATE_RINGING:
						// phone ringing or in phone call
						inCall = true;
						pause();
						break;
					case TelephonyManager.CALL_STATE_IDLE:
						// back from phone call
						inCall = false;
						resume();
						break;
				}
			}
		};
		telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	@Override
	public void onCompletion(MediaPlayer mp) {
		// when file has completed playing
		stop();
		stopSelf();
	}

	@Override
	public void onAudioFocusChange(int focusChange) {
		// when another app makes focus request
		switch (focusChange) {
			case AudioManager.AUDIOFOCUS_GAIN:      // we've (re)gained audio focus
				restartPlaying();
				break;
			case AudioManager.AUDIOFOCUS_LOSS:        // we've lost focus indefinitely
				pauseAndRelease();
				break;
			case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:    // we've lost focus for a short amount of time, e.g. Google Maps announcing directions
				stop();
				break;
			case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:    // we've lost focus for a short amount of time but we can still play audio in bg
				setVolume(0.1f);
				break;

		}
	}

	private boolean requestAudioFocus() {
		audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
		return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
	}

	private boolean removeAudioFocus() {
		return audioManager.abandonAudioFocus(this) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
	}

	private void pauseAndRelease() {
		stop();
		mediaPlayer.release();
		mediaPlayer = null;
	}

	private void restartPlaying() {
		if (mediaPlayer == null) {
			playSentence();
		} else {
			play();
		}
		// restore full volume levels
		setVolume(1.0f);
	}

	// get a copy of the service so we can run its methods from fragment
	public class StudyBinder extends Binder {
		public StudySessionService getService() {
			return StudySessionService.this;
		}
	}

	// --- Broadcast Receivers ---

	private void registerBecomingNoisyReceiver() {
		IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
		registerReceiver(becomingNoisyReceiver, intentFilter);
	}

	private void registerStartSessionReceiver() {
		IntentFilter intentFilter = new IntentFilter(BROADCAST_START_SESSION);
		registerReceiver(startSessionReceiver, intentFilter);
	}

	private class BecomingNoisyReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
				pause();
				buildNotification(PlaybackStatus.PAUSED);
			}
		}
	}

	private class StartSessionReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			String id = new Utils.Storage(getApplicationContext()).getDayId();
			day = realm.where(Day.class).equalTo("id", id).findFirst();
			if (day == null)
				stopSelf();
			day.resetReviews(realm);
			stop();
			mediaPlayer.reset();
			if (!requestAudioFocus())
				stopSelf();

			playSentence();
			buildNotification(PlaybackStatus.PLAYING);
		}
	}
}