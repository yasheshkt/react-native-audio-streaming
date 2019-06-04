package com.audioStreaming;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.RemoteViews;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import java.io.InputStream;

/**
 * Created by markus on 2017-08-28.
 */

public class AudioPlayerService extends Service implements ExoPlayer.EventListener {
  private static final String TAG = "AudioPlayerService";

  //Notifications
  public static final String BROADCAST_PLAYBACK_STOP = "stop",
          BROADCAST_PLAYBACK_PLAY = "pause",
          BROADCAST_EXIT = "exit";
  private static final int NOTIFY_ME_ID = 696969;
  private Class<?> clsActivity;
  private NotificationCompat.Builder notificationBuilder;
  private NotificationManager notificationManager = null;
  public static RemoteViews remoteViews;
  private final MediaNotificationReceiver mediaNotificationReceiver = new MediaNotificationReceiver(this);
  private final NotificationIntentReceiver receiver = new NotificationIntentReceiver(this);

  //ExoPlayer
  private SimpleExoPlayer player;
  private ExtractorsFactory extractorsFactory;
  private DataSource.Factory dataSourceFactory;

  private MediaSessionCompat mMediaSession = null;

  //Internal
  private final IBinder binder = new RadioBinder();

  private Context context;
  private EventsReceiver eventsReceiver;
  private ReactNativeAudioStreamingModule module;

  private TelephonyManager phoneManager;
  private PhoneListener phoneStateListener;

  private String trackUrl;
  private String trackTitle;
  private String coverImageUrl;
  private String artist;
  private Bitmap coverImageBitmap;

  @Override
  public IBinder onBind(Intent intent) {
    Log.i(TAG, "On Bind " + intent.toString());
    return binder;
  }

  public class RadioBinder extends Binder {
    public AudioPlayerService getService() {
      return AudioPlayerService.this;
    }
  }

  Handler handler = new Handler();
  Runnable progressUpdater = new Runnable() {
    public void run() {
      updateProgress();
      handler.postDelayed(progressUpdater, 500);
    }
  };

  @Override
  public void onCreate() {
    super.onCreate();
    createPlayer();

    IntentFilter intentFilter = new IntentFilter();
    intentFilter.addAction(BROADCAST_PLAYBACK_STOP);
    intentFilter.addAction(BROADCAST_PLAYBACK_PLAY);
    intentFilter.addAction(BROADCAST_EXIT);
    registerReceiver(this.receiver, intentFilter);

  }

  private void createMediaSession() {
    final Context context = this.context.getApplicationContext();

    MediaSessionCompat.Callback callback = new MediaSessionCompat.Callback() {

      @Override
      public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
        Log.i(TAG, mediaButtonEvent.toString());
        final String intentAction = mediaButtonEvent.getAction();
        if (Intent.ACTION_MEDIA_BUTTON.equals(intentAction)) {
          final KeyEvent event = mediaButtonEvent.getParcelableExtra(
                  Intent.EXTRA_KEY_EVENT);
          if (event == null) {
            return super.onMediaButtonEvent(mediaButtonEvent);
          }
          final int keycode = event.getKeyCode();
          final int action = event.getAction();
          if (event.getRepeatCount() == 0 && action == KeyEvent.ACTION_DOWN) {
            switch (keycode) {
              // Do what you want in here
              case KeyEvent.KEYCODE_HEADSETHOOK:
                AudioPlayerService.this.togglePlayPause();
                break;
              case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                AudioPlayerService.this.togglePlayPause();
                break;
              case KeyEvent.KEYCODE_MEDIA_PAUSE:
                AudioPlayerService.this.stop();
                break;
              case KeyEvent.KEYCODE_MEDIA_PLAY:
                AudioPlayerService.this.play();
                break;
            }
            return true;
          }
        }
        return super.onMediaButtonEvent(mediaButtonEvent);
      }

      @Override
      public void onPlay() {
        // Handle the play button
        Log.i(TAG, "On play button");
      }

      @Override
      public void onPause() {
        super.onPause();
        Log.i(TAG, "On pause button");
      }

    };
    mMediaSession = new MediaSessionCompat(this.context,
            TAG); // Debugging tag, any string
    mMediaSession.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
    mMediaSession.setCallback(callback);

  }

  @Override
  public void onDestroy() {
    Log.i(TAG, "On destroy");
    exitNotification();
    super.onDestroy();
  }

  @Override
  public boolean onUnbind(Intent intent) {
    Log.i(TAG, "On Unbind " + intent.toString());
    clearNotification();
    stopSelf();
    return super.onUnbind(intent);
  }

  @Override
  public void onTaskRemoved(Intent rootIntent) {
    Log.i(TAG, "On task removed " + rootIntent.toString());
    clearNotification();
    stopSelf();
    super.onTaskRemoved(rootIntent);
  }

  public void setData(Context context, ReactNativeAudioStreamingModule module) {
    this.context = context;
    this.createMediaSession();
    this.clsActivity = module.getClassActivity();
    this.module = module;

    this.eventsReceiver = new EventsReceiver(this.module);


    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.CREATED));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.DESTROYED));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.STARTED));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.CONNECTING));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.START_PREPARING));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.PREPARED));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.PLAYING));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.STOPPED));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.COMPLETED));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.ERROR));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.BUFFERING_START));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.BUFFERING_END));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.METADATA_UPDATED));
    registerReceiver(this.eventsReceiver, new IntentFilter(Mode.ALBUM_UPDATED));


    this.phoneStateListener = new PhoneListener(this.module);
    this.phoneManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
    if (this.phoneManager != null) {
      this.phoneManager.listen(this.phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }


  }
  public void createPlayer() {
    BandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
    DefaultBandwidthMeter defaultBandwidthMeter = new DefaultBandwidthMeter();
    extractorsFactory = new DefaultExtractorsFactory();

    TrackSelection.Factory trackSelectionFactory = new AdaptiveTrackSelection.Factory(bandwidthMeter);

    TrackSelector trackSelector = new DefaultTrackSelector(trackSelectionFactory);

/*        dataSourceFactory = new DefaultDataSourceFactory(this,
                Util.getUserAgent(this, "mediaPlayerSample"),
                (TransferListener<? super DataSource>) bandwidthMeter);*/

    dataSourceFactory = new DefaultDataSourceFactory(this,
            Util.getUserAgent(this, "mediaPlayerSample"), defaultBandwidthMeter);


    player = ExoPlayerFactory.newSimpleInstance(this, trackSelector);

    player.addListener(AudioPlayerService.this);
  }

  private void updateProgress() {
    if (player.getPlaybackState() ==  ExoPlayer.STATE_READY) {
      double progress = ((double)player.getCurrentPosition() / 1000.0);
      double duration = ((double)player.getDuration() / 1000.0);
      if (player.getPlayWhenReady() && progress >= 0 && duration >= 0) {
        Intent intent = new Intent(Mode.PLAYING);
        intent.putExtra("duration", duration);
        intent.putExtra("progress", progress);
        sendBroadcast(intent);
      }
    }
  }

  public void setTrackURL(String trackUrl) {
    if (trackUrl != null && !trackUrl.equals(this.trackUrl)) {
      this.trackUrl = trackUrl;
      MediaSource mediaSource = new ExtractorMediaSource(Uri.parse(trackUrl), dataSourceFactory, extractorsFactory, null, null);
      player.prepare(mediaSource);
    }
  }

  public void setTrackTitle(String trackTitle) {
    this.trackTitle = trackTitle;
    updateNotificationViews();
  }

  public void setArtist(String artist) {
    this.artist = artist;
    updateNotificationViews();
  }

  public void setCoverImageUrl(String coverImageUrl) {
    if (coverImageUrl != null && !coverImageUrl.equals(this.coverImageUrl)) {
      this.coverImageBitmap = null;
      this.coverImageUrl = coverImageUrl;

      new DownloadImageTask(new DownloadImageResultListener() {
        @Override
        public void onCompletion(Bitmap bitmap) {
          AudioPlayerService.this.coverImageBitmap = bitmap;
          updateNotificationViews();
        }
      }).execute(this.coverImageUrl);

    } else if (coverImageUrl == null) {
      this.coverImageUrl = null;
      this.coverImageBitmap = null;
      updateNotificationViews();
    }
  }

  public void play() {
      player.setPlayWhenReady(true);
      progressUpdater.run();
      showNotification();
      updateNotificationViews();
      mMediaSession.setActive(true);
  }

  public void togglePlayPause() {
    if (this.isPlaying()) {
      this.stop();
    } else {
      this.play();
    }
  }

  public void seekToTime(Double time) {
    long seekToMillis = (long)(time*1000);
    player.seekTo(seekToMillis);
  }

  public void stop() {
    player.setPlayWhenReady(false);
    updateNotificationViews();
    stopForeground(false);
    handler.removeCallbacks(progressUpdater);
  }

  public boolean isPlaying() {
    return this.player.getPlayWhenReady();
  }

  // Notifications
  public void showNotification() {
    notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    remoteViews = new RemoteViews(context.getPackageName(), R.layout.streaming_notification_player);

    notificationBuilder = new NotificationCompat.Builder(this)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setVibrate(null)
            .setSound(null)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off) // TODO Use app icon instead
            .setContentText("")
            .setContent(remoteViews);

    Intent resultIntent = new Intent(this.context, this.clsActivity);
    resultIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    resultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

    TaskStackBuilder stackBuilder = TaskStackBuilder.create(this.context);
    stackBuilder.addParentStack(this.clsActivity);
    stackBuilder.addNextIntent(resultIntent);

    PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0,
            PendingIntent.FLAG_UPDATE_CURRENT);


    notificationBuilder.setContentIntent(resultPendingIntent);
    remoteViews.setOnClickPendingIntent(R.id.btn_streaming_notification_play, makePendingIntent(BROADCAST_PLAYBACK_PLAY));
    remoteViews.setImageViewResource(R.id.btn_streaming_notification_play, android.R.drawable.ic_media_pause);
    remoteViews.setOnClickPendingIntent(R.id.btn_streaming_notification_stop, makePendingIntent(BROADCAST_EXIT));

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      String[] deletableChannelIds = getResources().getStringArray(R.array.rnAudioStreamingDeletableChannelIds);
      for (String id: deletableChannelIds) {
        notificationManager.deleteNotificationChannel(id);
      }

      String channelId = getString(R.string.rnAudioStreamingChannelId);
      CharSequence name = getString(R.string.rnAudioStreamingChannelName);
      String description = getString(R.string.rnAudioStreamingChannelDescription);
      int importance = NotificationManager.IMPORTANCE_LOW;
      NotificationChannel channel = new NotificationChannel(channelId, name, importance);
      channel.setDescription(description);
      channel.setSound(null, null);
      channel.setVibrationPattern(null);
      channel.enableVibration(false);
      notificationManager.createNotificationChannel(channel);
      notificationBuilder.setChannelId(channelId);
    }

    

    final Notification notification = notificationBuilder.build();
    startForeground(NOTIFY_ME_ID, notification);
  }

  private void updateNotificationViews() {
    if (remoteViews != null && notificationBuilder != null && notificationManager != null) {

      Resources resources = context.getResources();
      int playResource = resources.getIdentifier("notification_play_icon", "drawable", context.getPackageName());
      if (playResource == 0) {
        playResource = android.R.drawable.ic_media_play;
      }
      int pauseResource = resources.getIdentifier("notification_pause_icon", "drawable", context.getPackageName());
      if (pauseResource == 0) {
        pauseResource = android.R.drawable.ic_media_pause;
      }
      remoteViews.setImageViewResource(R.id.btn_streaming_notification_play, this.isPlaying() ? pauseResource : playResource);

      remoteViews.setImageViewBitmap(R.id.coverImageView, this.coverImageBitmap);

      remoteViews.setTextViewText(R.id.trackTitleText, this.trackTitle);
      remoteViews.setTextViewText(R.id.artistText, this.artist);
      notificationBuilder.setContent(remoteViews);
      notificationManager.notify(NOTIFY_ME_ID, notificationBuilder.build());
    }
  }

  private PendingIntent makePendingIntent(String broadcast) {
    Intent intent = new Intent(broadcast);
    return PendingIntent.getBroadcast(this.context, 0, intent, 0);
  }

  public void clearNotification() {
    if (notificationManager != null)
      notificationManager.cancel(NOTIFY_ME_ID);
  }

  public void exitNotification() {
    stop();
    stopForeground(true);
    if (notificationManager != null) {
      notificationManager.cancel(NOTIFY_ME_ID);
      notificationManager.cancelAll();
    }

    notificationBuilder = null;
    notificationManager = null;

    stopSelf();
  }

  public NotificationManager getNotificationManager() {
    return notificationManager;
  }


  //ExoPlayer EventListener implementation

  @Override
  public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
    Log.i("APS", "onShuffleModeEnabledChanged " + shuffleModeEnabled);
  }

  @Override
  public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {
    Log.i("APS", timeline.toString() + " " + reason);
  }

  @Override
  public void onRepeatModeChanged(int repeatMode) {
    Log.i("APS", "onRepeatModeChanged " + repeatMode);
  }

  @Override
  public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
    Log.i("APS", "On tracks changed");
  }

  @Override
  public void onLoadingChanged(boolean isLoading) {
    Log.i("APS", "On loading");
  }

  @Override
  public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
    double progress = ((double)player.getCurrentPosition() / 1000.0);
    double duration = ((double)player.getDuration() / 1000.0);
    Log.i("APS", "Duration: " + duration +  " playback state: " + playbackState);


    switch (playbackState) {
      case ExoPlayer.STATE_READY: {
        if (player.getPlayWhenReady()) {
          Intent intent = new Intent(Mode.PLAYING);
          if (duration >= 0 && progress >= 0) {
            intent.putExtra("duration", duration);
            intent.putExtra("progress", progress);
          }
          sendBroadcast(intent);
          break;
        } else {
          Intent intent = new Intent(Mode.STOPPED);
          if (duration >= 0 && progress >= 0) {
            intent.putExtra("duration", duration);
            intent.putExtra("progress", progress);
          }
          sendBroadcast(intent);
          break;
        }
      }
      case ExoPlayer.STATE_BUFFERING: {
        Intent intent = new Intent(Mode.BUFFERING);
        sendBroadcast(intent);
        break;
      }
      case ExoPlayer.STATE_ENDED: {
        Intent intent = new Intent(Mode.STOPPED);
        if (duration >= 0 && progress >= 0) {
          intent.putExtra("duration", duration);
          intent.putExtra("progress", progress);
        }
        sendBroadcast(intent);
        break;
      }
    }
  }

  @Override
  public void onPlayerError(ExoPlaybackException error) {
    Log.i("APS", "On player error");
  }

  @Override
  public void onSeekProcessed() {
    Log.i("APS", "On seek processed");
  }

  @Override
  public void onPositionDiscontinuity(int reason) {
    Log.i("APS", "On position discontinuity " + reason);
  }

  @Override
  public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
    Log.i("APS", "Playback parameters changed");
  }

  class NotificationIntentReceiver extends BroadcastReceiver {
    private AudioPlayerService audioPlayerService;

    public NotificationIntentReceiver(AudioPlayerService service) {
      super();
      this.audioPlayerService = service;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      if (action.equals(AudioPlayerService.BROADCAST_PLAYBACK_PLAY)) {
        if (!this.audioPlayerService.isPlaying()) {
          this.audioPlayerService.play();
        } else {
          this.audioPlayerService.stop();
        }
      } else if (action.equals(AudioPlayerService.BROADCAST_EXIT)) {
        this.audioPlayerService.exitNotification();
      }
    }
  }

  class MediaNotificationReceiver extends MediaButtonReceiver {
    private AudioPlayerService audioPlayerService;

    public MediaNotificationReceiver(AudioPlayerService service) {
      super();
      this.audioPlayerService = service;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
      super.onReceive(context, intent);

      String action = intent.getAction();
      Log.i(TAG, action);
    }
  }

  public interface DownloadImageResultListener {
    void onCompletion(Bitmap bitmap);
  }

  // DownloadImage AsyncTask
  private class DownloadImageTask extends AsyncTask<String, Void, Bitmap> {
    public DownloadImageResultListener listener = null;

    public DownloadImageTask(DownloadImageResultListener listener) {
      this.listener = listener;
    }

    @Override
    protected Bitmap doInBackground(String... URL) {

      String imageURL = URL[0];

      Bitmap bitmap = null;
      try {
        // Download Image from URL
        InputStream input = new java.net.URL(imageURL).openStream();
        // Decode Bitmap
        bitmap = BitmapFactory.decodeStream(input);
      } catch (Exception e) {
        e.printStackTrace();
      }
      return bitmap;
    }

    @Override
    protected void onPostExecute(Bitmap result) {
      // Set the bitmap into ImageView
      if (listener != null) {
        listener.onCompletion(result);
      }
    }
  }
}
