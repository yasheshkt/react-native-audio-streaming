package com.audioStreaming;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;


public class EventsReceiver extends BroadcastReceiver {
    private ReactNativeAudioStreamingModule module;

    public EventsReceiver(ReactNativeAudioStreamingModule module) {
        this.module = module;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        WritableMap params = Arguments.createMap();
        params.putString("status", intent.getAction());

        if (intent != null && intent.getExtras() != null) {
            Double duration = intent.getExtras().getDouble("duration", -1);
            if (duration != -1) {
                params.putDouble("duration", duration);
            }

            Double progress = intent.getExtras().getDouble("progress", -1);
            if (progress != -1) {
                params.putDouble("progress", progress);
            }
        }

        if (intent.getAction().equals(Mode.METADATA_UPDATED)) {
            params.putString("key", intent.getStringExtra("key"));
            params.putString("value", intent.getStringExtra("value"));
        }

        this.module.sendEvent(this.module.getReactApplicationContextModule(), "AudioBridgeEvent", params);
    }
}
