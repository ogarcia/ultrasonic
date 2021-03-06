/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package org.moire.ultrasonic.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;

import org.moire.ultrasonic.R;
import org.moire.ultrasonic.domain.PlayerState;
import org.moire.ultrasonic.util.CacheCleaner;
import org.moire.ultrasonic.util.Constants;
import org.moire.ultrasonic.util.Util;

/**
 * This class is responsible for handling received events for the Media Player implementation
 *
 * @author Sindre Mehus
 */
public class MediaPlayerLifecycleSupport
{
	private static final String TAG = MediaPlayerLifecycleSupport.class.getSimpleName();

	private boolean created = false;
	private DownloadQueueSerializer downloadQueueSerializer; // From DI
	private final MediaPlayerControllerImpl mediaPlayerController; // From DI
	private final Downloader downloader; // From DI
	private Context context;

	private BroadcastReceiver headsetEventReceiver;

	public MediaPlayerLifecycleSupport(Context context, DownloadQueueSerializer downloadQueueSerializer,
									   final MediaPlayerControllerImpl mediaPlayerController, final Downloader downloader)
	{
		this.downloadQueueSerializer = downloadQueueSerializer;
		this.mediaPlayerController = mediaPlayerController;
		this.context = context;
		this.downloader = downloader;

		Log.i(TAG, "LifecycleSupport constructed");
	}

	public void onCreate()
	{
		onCreate(false, null);
	}

	private void onCreate(final boolean autoPlay, final Runnable afterCreated)
	{
		if (created)
		{
			if (afterCreated != null) afterCreated.run();
			return;
		}

		registerHeadsetReceiver();

		// React to media buttons.
		Util.registerMediaButtonEventReceiver(context, true);

		// Register the handler for outside intents.
		IntentFilter commandFilter = new IntentFilter();
		commandFilter.addAction(Constants.CMD_PLAY);
		commandFilter.addAction(Constants.CMD_TOGGLEPAUSE);
		commandFilter.addAction(Constants.CMD_PAUSE);
		commandFilter.addAction(Constants.CMD_STOP);
		commandFilter.addAction(Constants.CMD_PREVIOUS);
		commandFilter.addAction(Constants.CMD_NEXT);
		commandFilter.addAction(Constants.CMD_PROCESS_KEYCODE);
		context.registerReceiver(intentReceiver, commandFilter);

		mediaPlayerController.onCreate();
		if (autoPlay) mediaPlayerController.preload();

		this.downloadQueueSerializer.deserializeDownloadQueue(new Consumer<State>() {
			@Override
			public void accept(State state) {
				mediaPlayerController.restore(state.songs, state.currentPlayingIndex, state.currentPlayingPosition, autoPlay, false);

				// Work-around: Serialize again, as the restore() method creates a serialization without current playing info.
				MediaPlayerLifecycleSupport.this.downloadQueueSerializer.serializeDownloadQueue(downloader.downloadList,
						downloader.getCurrentPlayingIndex(), mediaPlayerController.getPlayerPosition());

				if (afterCreated != null) afterCreated.run();
			}
		});

		new CacheCleaner(context).clean();
		created = true;
		Log.i(TAG, "LifecycleSupport created");
	}

	public void onDestroy()
	{
		if (!created) return;
		downloadQueueSerializer.serializeDownloadQueueNow(downloader.downloadList,
				downloader.getCurrentPlayingIndex(), mediaPlayerController.getPlayerPosition());
		mediaPlayerController.clear(false);
		context.unregisterReceiver(headsetEventReceiver);
		context.unregisterReceiver(intentReceiver);
		mediaPlayerController.onDestroy();
		created = false;
		Log.i(TAG, "LifecycleSupport destroyed");
	}

	public void receiveIntent(Intent intent)
	{
		Log.i(TAG, "Received intent");
		if (intent != null && intent.getExtras() != null)
		{
			KeyEvent event = (KeyEvent) intent.getExtras().get(Intent.EXTRA_KEY_EVENT);
			if (event != null)
			{
				handleKeyEvent(event);
			}
		}
	}

	private void registerHeadsetReceiver() {
        // Pause when headset is unplugged.
        final SharedPreferences sp = Util.getPreferences(context);
        final String spKey = context
                .getString(R.string.settings_playback_resume_play_on_headphones_plug);

        headsetEventReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final Bundle extras = intent.getExtras();

                if (extras == null) {
                    return;
                }

                Log.i(TAG, String.format("Headset event for: %s", extras.get("name")));
                final int state = extras.getInt("state");
                if (state == 0) {
                    if (!mediaPlayerController.isJukeboxEnabled()) {
                        mediaPlayerController.pause();
                    }
                } else if (state == 1) {
                    if (!mediaPlayerController.isJukeboxEnabled() &&
                            sp.getBoolean(spKey, false) &&
                            mediaPlayerController.getPlayerState() == PlayerState.PAUSED) {
                        mediaPlayerController.start();
                    }
                }
            }
        };


		IntentFilter headsetIntentFilter;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
		{
			headsetIntentFilter = new IntentFilter(AudioManager.ACTION_HEADSET_PLUG);
		}
        else
		{
			headsetIntentFilter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
		}
        context.registerReceiver(headsetEventReceiver, headsetIntentFilter);
    }

	private void handleKeyEvent(KeyEvent event)
	{
		if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0)
		{
			return;
		}

		final int keyCode = event.getKeyCode();
		boolean autoStart = (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
				keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
				keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
				keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
				keyCode == KeyEvent.KEYCODE_MEDIA_NEXT);

		// We can receive intents (e.g. MediaButton) when everything is stopped, so we need to start
		onCreate(autoStart, new Runnable() {
			@Override
			public void run() {
				switch (keyCode)
				{
					case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
					case KeyEvent.KEYCODE_HEADSETHOOK:
						mediaPlayerController.togglePlayPause();
						break;
					case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
						mediaPlayerController.previous();
						break;
					case KeyEvent.KEYCODE_MEDIA_NEXT:
						if (downloader.getCurrentPlayingIndex() < downloader.downloadList.size() - 1)
						{
							mediaPlayerController.next();
						}
						break;
					case KeyEvent.KEYCODE_MEDIA_STOP:
						mediaPlayerController.stop();
						break;
					case KeyEvent.KEYCODE_MEDIA_PLAY:
						if (mediaPlayerController.getPlayerState() == PlayerState.IDLE)
						{
							mediaPlayerController.play();
						}
						else if (mediaPlayerController.getPlayerState() != PlayerState.STARTED)
						{
							mediaPlayerController.start();
						}
						break;
					case KeyEvent.KEYCODE_MEDIA_PAUSE:
						mediaPlayerController.pause();
						break;
					case KeyEvent.KEYCODE_1:
						mediaPlayerController.setSongRating(1);
						break;
					case KeyEvent.KEYCODE_2:
						mediaPlayerController.setSongRating(2);
						break;
					case KeyEvent.KEYCODE_3:
						mediaPlayerController.setSongRating(3);
						break;
					case KeyEvent.KEYCODE_4:
						mediaPlayerController.setSongRating(4);
						break;
					case KeyEvent.KEYCODE_5:
						mediaPlayerController.setSongRating(5);
						break;
					default:
						break;
				}
			}
		});
	}

	/**
	 * This receiver manages the intent that could come from other applications.
	 */
	private BroadcastReceiver intentReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(Context context, Intent intent)
		{
			String action = intent.getAction();
			if (action == null) return;
			Log.i(TAG, "intentReceiver.onReceive: " + action);

			switch(action)
			{
				case Constants.CMD_PLAY:
					mediaPlayerController.play();
					break;
				case Constants.CMD_NEXT:
					mediaPlayerController.next();
					break;
				case Constants.CMD_PREVIOUS:
					mediaPlayerController.previous();
					break;
				case Constants.CMD_TOGGLEPAUSE:
					mediaPlayerController.togglePlayPause();
					break;
				case Constants.CMD_STOP:
					// TODO: There is a stop() function, shouldn't we use that?
					mediaPlayerController.pause();
					mediaPlayerController.seekTo(0);
					break;
				case Constants.CMD_PAUSE:
					mediaPlayerController.pause();
					break;
				case Constants.CMD_PROCESS_KEYCODE:
					receiveIntent(intent);
					break;
			}
		}
	};
}