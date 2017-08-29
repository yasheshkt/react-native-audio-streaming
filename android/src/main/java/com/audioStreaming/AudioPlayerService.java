package com.audioStreaming;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

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

/**
 * Created by markus on 2017-08-28.
 */

public class AudioPlayerService extends Service implements ExoPlayer.EventListener {

  private SimpleExoPlayer player;
  private BandwidthMeter bandwidthMeter;
  private ExtractorsFactory extractorsFactory;
  private TrackSelection.Factory trackSelectionFactory;
  private TrackSelector trackSelector;
  private DefaultBandwidthMeter defaultBandwidthMeter;
  private DataSource.Factory dataSourceFactory;
  private MediaSource mediaSource;

  private final IBinder binder = new RadioBinder();

  private Context context;
  private EventsReceiver eventsReceiver;
  private ReactNativeAudioStreamingModule module;

  private TelephonyManager phoneManager;
  private PhoneListener phoneStateListener;

  private String trackUrl;

  @Override
  public IBinder onBind(Intent intent) {
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
  }

  public void setData(Context context, ReactNativeAudioStreamingModule module) {
    this.context = context;
    //this.clsActivity = module.getClassActivity();
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
    bandwidthMeter = new DefaultBandwidthMeter();
    extractorsFactory = new DefaultExtractorsFactory();

    trackSelectionFactory = new AdaptiveTrackSelection.Factory(bandwidthMeter);

    trackSelector = new DefaultTrackSelector(trackSelectionFactory);

/*        dataSourceFactory = new DefaultDataSourceFactory(this,
                Util.getUserAgent(this, "mediaPlayerSample"),
                (TransferListener<? super DataSource>) bandwidthMeter);*/

    defaultBandwidthMeter = new DefaultBandwidthMeter();
    dataSourceFactory = new DefaultDataSourceFactory(this,
            Util.getUserAgent(this, "mediaPlayerSample"), defaultBandwidthMeter);


    player = ExoPlayerFactory.newSimpleInstance(this, trackSelector);

    player.addListener(AudioPlayerService.this);
  }

  private void updateProgress() {
    if (player.getPlaybackState() ==  ExoPlayer.STATE_READY) {
      double progress = ((double)player.getCurrentPosition() / 1000.0);
      double duration = ((double)player.getDuration() / 1000.0);
      if (player.getPlayWhenReady()) {
        Intent intent = new Intent(Mode.PLAYING);
        intent.putExtra("duration", duration);
        intent.putExtra("progress", progress);
        sendBroadcast(intent);
      }
    }
  }

  public void play(String trackUrl) {
    if (trackUrl != null && !trackUrl.equals(this.trackUrl)) {
      this.trackUrl = trackUrl;
      mediaSource = new ExtractorMediaSource(Uri.parse(trackUrl), dataSourceFactory, extractorsFactory, null, null);
      player.prepare(mediaSource);
    }

    player.setPlayWhenReady(true);
    progressUpdater.run();
  }

  public void seekToTime(Double time) {
    long seekToMillis = (long)(time*1000);
    player.seekTo(seekToMillis);
  }

  public void stop() {
    player.setPlayWhenReady(false);
//    Intent intent = new Intent(Mode.STOPPED);
//    sendBroadcast(intent);
    handler.removeCallbacks(progressUpdater);
  }


  //ExoPlayer EventListener implementation
  @Override
  public void onTimelineChanged(Timeline timeline, Object manifest) {
    Log.i("APS", timeline.toString());
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
          if (duration > 0) {
            intent.putExtra("duration", duration);
            intent.putExtra("progress", progress);
          }
          sendBroadcast(intent);
          break;
        } else {
          Intent intent = new Intent(Mode.STOPPED);
          if (duration > 0) {
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
  public void onPositionDiscontinuity() {
    Log.i("APS", "On position discontinuity");
  }

  @Override
  public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
    Log.i("APS", "Playback parameters changed");
  }
}
