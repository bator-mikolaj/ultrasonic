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
package com.thejoshwa.ultrasonic.androidapp.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.RemoteControlClient;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.SeekBar;
import com.thejoshwa.ultrasonic.androidapp.R;
import com.thejoshwa.ultrasonic.androidapp.activity.DownloadActivity;
import com.thejoshwa.ultrasonic.androidapp.activity.MainActivity;
import com.thejoshwa.ultrasonic.androidapp.activity.SubsonicTabActivity;
import com.thejoshwa.ultrasonic.androidapp.audiofx.EqualizerController;
import com.thejoshwa.ultrasonic.androidapp.audiofx.VisualizerController;
import com.thejoshwa.ultrasonic.androidapp.domain.MusicDirectory;
import com.thejoshwa.ultrasonic.androidapp.domain.PlayerState;
import com.thejoshwa.ultrasonic.androidapp.domain.RepeatMode;
import com.thejoshwa.ultrasonic.androidapp.provider.UltraSonicAppWidgetProvider4x1;
import com.thejoshwa.ultrasonic.androidapp.receiver.MediaButtonIntentReceiver;
import com.thejoshwa.ultrasonic.androidapp.util.CancellableTask;
import com.thejoshwa.ultrasonic.androidapp.util.FileUtil;
import com.thejoshwa.ultrasonic.androidapp.util.LRUCache;
import com.thejoshwa.ultrasonic.androidapp.util.ShufflePlayBuffer;
import com.thejoshwa.ultrasonic.androidapp.util.SimpleServiceBinder;
import com.thejoshwa.ultrasonic.androidapp.util.StreamProxy;
import com.thejoshwa.ultrasonic.androidapp.util.Util;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import static com.thejoshwa.ultrasonic.androidapp.domain.PlayerState.*;

/**
 * @author Sindre Mehus, Joshua Bahnsen
 * @version $Id$
 */
public class DownloadServiceImpl extends Service implements DownloadService {

    private static final String TAG = DownloadServiceImpl.class.getSimpleName();

    public static final String CMD_PLAY = "com.thejoshwa.ultrasonic.androidapp.CMD_PLAY";
    public static final String CMD_TOGGLEPAUSE = "com.thejoshwa.ultrasonic.androidapp.CMD_TOGGLEPAUSE";
    public static final String CMD_PAUSE = "com.thejoshwa.ultrasonic.androidapp.CMD_PAUSE";
    public static final String CMD_STOP = "com.thejoshwa.ultrasonic.androidapp.CMD_STOP";
    public static final String CMD_PREVIOUS = "com.thejoshwa.ultrasonic.androidapp.CMD_PREVIOUS";
    public static final String CMD_NEXT = "com.thejoshwa.ultrasonic.androidapp.CMD_NEXT";
    
    public static final String PLAYSTATUS_REQUEST = "com.android.music.playstatusrequest";
    public static final String PLAYSTATUS_RESPONSE = "com.android.music.playstatusresponse";

    private final IBinder binder = new SimpleServiceBinder<DownloadService>(this);
    private MediaPlayer mediaPlayer;
    private final List<DownloadFile> downloadList = new ArrayList<DownloadFile>();
    private final Handler handler = new Handler();
    private final DownloadServiceLifecycleSupport lifecycleSupport = new DownloadServiceLifecycleSupport(this);
    private final ShufflePlayBuffer shufflePlayBuffer = new ShufflePlayBuffer(this);

    private final LRUCache<MusicDirectory.Entry, DownloadFile> downloadFileCache = new LRUCache<MusicDirectory.Entry, DownloadFile>(100);
    private final List<DownloadFile> cleanupCandidates = new ArrayList<DownloadFile>();
    private final Scrobbler scrobbler = new Scrobbler();
    private final JukeboxService jukeboxService = new JukeboxService(this);
    private Notification notification = new Notification(R.drawable.ic_stat_ultrasonic, null, System.currentTimeMillis());
    		           
    private DownloadFile currentPlaying;
    private DownloadFile currentDownloading;
    private CancellableTask bufferTask;
    private PlayerState playerState = IDLE;
    private boolean shufflePlay;
    private long revision;
    private static DownloadService instance;
    private String suggestedPlaylistName;
    private PowerManager.WakeLock wakeLock;
    private boolean keepScreenOn = false;

    private static boolean equalizerAvailable;
    private static boolean visualizerAvailable;
    private EqualizerController equalizerController;
    private VisualizerController visualizerController;
    private boolean showVisualization;
    private boolean jukeboxEnabled;
    private StreamProxy proxy;
    private static MusicDirectory.Entry currentSong;
    private RemoteControlClient remoteControlClient;
    private AudioManager audioManager;
    private int secondaryProgress = -1;
        
    static {
        try {
            EqualizerController.checkAvailable();
            equalizerAvailable = true;
        } catch (Throwable t) {
            equalizerAvailable = false;
        }
    }
    static {
        try {
            VisualizerController.checkAvailable();
            visualizerAvailable = true;
        } catch (Throwable t) {
            visualizerAvailable = false;
        }
    }
    
	private OnAudioFocusChangeListener _afChangeListener = new OnAudioFocusChangeListener() {
		public void onAudioFocusChange(int focusChange) {
			if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
				pause();
			} else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
				if (playerState == PlayerState.STARTED) {
					start();
				}
			} else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
				stop();
			}
		}
	};

    @Override
    public void onCreate() {
        super.onCreate();

        audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mediaPlayer, int what, int more) {
                handleError(new Exception("MediaPlayer error: " + what + " (" + more + ")"));
                return false;
            }
        });
        
    	notification.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        notification.contentView = new RemoteViews(this.getPackageName(), R.layout.notification);
        Intent notificationIntent = new Intent(this, DownloadActivity.class);
        notification.contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Util.linkButtons(this, notification.contentView, false);
        
        if (equalizerAvailable) {
            equalizerController = new EqualizerController(this, mediaPlayer);
            if (!equalizerController.isAvailable()) {
                equalizerController = null;
            } else {
                equalizerController.loadSettings();
            }
        }
        if (visualizerAvailable) {
            visualizerController = new VisualizerController(this, mediaPlayer);
            if (!visualizerController.isAvailable()) {
                visualizerController = null;
            }
        }
        
        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.getClass().getName());
        wakeLock.setReferenceCounted(false);

        instance = this;
        lifecycleSupport.onCreate();
    }
    
    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        lifecycleSupport.onStart(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        lifecycleSupport.onDestroy();
        mediaPlayer.release();
        shufflePlayBuffer.shutdown();
        if (equalizerController != null) {
            equalizerController.release();
        }
        if (visualizerController != null) {
            visualizerController.release();
        }
        
    	audioManager.unregisterRemoteControlClient(remoteControlClient);
        notification = null;
        instance = null;
    }

    public static DownloadService getInstance() {
        return instance;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public synchronized void download(List<MusicDirectory.Entry> songs, boolean save, boolean autoplay, boolean playNext) {
        shufflePlay = false;
        int offset = 1;

        if (songs.isEmpty()) {
            return;
        }
        if (playNext) {
            if (autoplay && getCurrentPlayingIndex() >= 0) {
                offset = 0;
            }
            for (MusicDirectory.Entry song : songs) {
                DownloadFile downloadFile = new DownloadFile(this, song, save);
                downloadList.add(getCurrentPlayingIndex() + offset, downloadFile);
                offset++;
            }
            revision++;
        } else {
            for (MusicDirectory.Entry song : songs) {
                DownloadFile downloadFile = new DownloadFile(this, song, save);
                downloadList.add(downloadFile);
            }
            revision++;
        }
        updateJukeboxPlaylist();

        if (autoplay) {
            play(0);
        } else {
            if (currentPlaying == null) {
                currentPlaying = downloadList.get(0);
            }
            checkDownloads();
        }
        lifecycleSupport.serializeDownloadQueue();
    }

    private void updateJukeboxPlaylist() {
        if (jukeboxEnabled) {
            jukeboxService.updatePlaylist();
        }
    }

    public void restore(List<MusicDirectory.Entry> songs, int currentPlayingIndex, int currentPlayingPosition) {
        download(songs, false, false, false);
        if (currentPlayingIndex != -1) {
            play(currentPlayingIndex, false);
            if (currentPlaying.isCompleteFileAvailable()) {
                doPlay(currentPlaying, currentPlayingPosition, false);
            }
        }
    }

    @Override
    public synchronized void setShufflePlayEnabled(boolean enabled) {
        if (shufflePlay == enabled) {
            return;
        }

        shufflePlay = enabled;
        if (shufflePlay) {
            clear();
            checkDownloads();
        }
    }

    @Override
    public synchronized boolean isShufflePlayEnabled() {
        return shufflePlay;
    }

    @Override
    public synchronized void shuffle() {
        Collections.shuffle(downloadList);
        if (currentPlaying != null) {
            downloadList.remove(getCurrentPlayingIndex());
            downloadList.add(0, currentPlaying);
        }
        revision++;
        lifecycleSupport.serializeDownloadQueue();
        updateJukeboxPlaylist();
    }

    @Override
    public RepeatMode getRepeatMode() {
        return Util.getRepeatMode(this);
    }

    @Override
    public void setRepeatMode(RepeatMode repeatMode) {
        Util.setRepeatMode(this, repeatMode);
    }

    @Override
    public boolean getKeepScreenOn() {
    	return keepScreenOn;
    }

    @Override
    public void setKeepScreenOn(boolean keepScreenOn) {
    	this.keepScreenOn = keepScreenOn;
    }

    @Override
    public boolean getShowVisualization() {
        return showVisualization;
    }

    @Override
    public void setShowVisualization(boolean showVisualization) {
        this.showVisualization = showVisualization;
    }

    @Override
    public synchronized DownloadFile forSong(MusicDirectory.Entry song) {
        for (DownloadFile downloadFile : downloadList) {
            if (downloadFile.getSong().equals(song)) {
                return downloadFile;
            }
        }

        DownloadFile downloadFile = downloadFileCache.get(song);
        if (downloadFile == null) {
            downloadFile = new DownloadFile(this, song, false);
            downloadFileCache.put(song, downloadFile);
        }
        return downloadFile;
    }

    @Override
    public synchronized void clear() {
        clear(true);
    }

    @Override
    public synchronized void clearIncomplete() {
        reset();
        Iterator<DownloadFile> iterator = downloadList.iterator();
        while (iterator.hasNext()) {
            DownloadFile downloadFile = iterator.next();
            if (!downloadFile.isCompleteFileAvailable()) {
                iterator.remove();
            }
        }
        lifecycleSupport.serializeDownloadQueue();
        updateJukeboxPlaylist();
    }

    @Override
    public synchronized int size() {
        return downloadList.size();
    }

    public synchronized void clear(boolean serialize) {
        reset();
        downloadList.clear();
        revision++;
        if (currentDownloading != null) {
            currentDownloading.cancelDownload();
            currentDownloading = null;
        }
        setCurrentPlaying(null, false);

        if (serialize) {
            lifecycleSupport.serializeDownloadQueue();
        }
        updateJukeboxPlaylist();
    }

    @Override
    public synchronized void remove(DownloadFile downloadFile) {
        if (downloadFile == currentDownloading) {
            currentDownloading.cancelDownload();
            currentDownloading = null;
        }
        if (downloadFile == currentPlaying) {
            reset();
            setCurrentPlaying(null, false);
        }
        downloadList.remove(downloadFile);
        revision++;
        lifecycleSupport.serializeDownloadQueue();
        updateJukeboxPlaylist();
    }

    @Override
    public synchronized void delete(List<MusicDirectory.Entry> songs) {
        for (MusicDirectory.Entry song : songs) {
            forSong(song).delete();
        }
    }

    @Override
    public synchronized void unpin(List<MusicDirectory.Entry> songs) {
        for (MusicDirectory.Entry song : songs) {
            forSong(song).unpin();
        }
    }

    synchronized void setCurrentPlaying(int currentPlayingIndex, boolean showNotification) {
        try {
            setCurrentPlaying(downloadList.get(currentPlayingIndex), showNotification);
        } catch (IndexOutOfBoundsException x) {
            // Ignored
        }
    }

    synchronized void setCurrentPlaying(DownloadFile currentPlaying, boolean showNotification) {
        this.currentPlaying = currentPlaying;
        
        if (currentPlaying != null) {
        	Util.broadcastNewTrackInfo(this, currentPlaying.getSong());
        	Util.broadcastA2dpMetaDataChange(this, getInstance());
        } else {
            Util.broadcastNewTrackInfo(this, null);
            Util.broadcastA2dpMetaDataChange(this, null);
        }
        
        updateRemoteControl();

        // Update widget
        UltraSonicAppWidgetProvider4x1.getInstance().notifyChange(this, this, playerState == PlayerState.STARTED);
        
        if (currentPlaying != null) {
        	if (showNotification) {
        		Util.showPlayingNotification(this, this, handler, currentPlaying.getSong(), this.notification, this.playerState);
        	}
        
            SubsonicTabActivity tabInstance = SubsonicTabActivity.getInstance();
            
            if (tabInstance != null) {
            	tabInstance.showNowPlaying(this, this, currentPlaying.getSong(), this.playerState);
            }
        } else {
            Util.hidePlayingNotification(this, this, handler);
            
            SubsonicTabActivity tabInstance = SubsonicTabActivity.getInstance();
            
            if (tabInstance != null) {
            	tabInstance.hideNowPlaying();
            }
        }
    }

    @Override
    public synchronized int getCurrentPlayingIndex() {
        return downloadList.indexOf(currentPlaying);
    }

    @Override
    public DownloadFile getCurrentPlaying() {
        return currentPlaying;
    }

    @Override
    public DownloadFile getCurrentDownloading() {
        return currentDownloading;
    }

    @Override
    public synchronized List<DownloadFile> getDownloads() {
        return new ArrayList<DownloadFile>(downloadList);
    }

    /** Plays either the current song (resume) or the first/next one in queue. */
    public synchronized void play()
    {
        int current = getCurrentPlayingIndex();
        if (current == -1) {
            play(0);
        } else {
            play(current);
        }
    }

    @Override
    public synchronized void play(int index) {
        play(index, true);
    }

    private synchronized void play(int index, boolean start) {
        if (index < 0 || index >= size()) {
            reset();
            setCurrentPlaying(null, false);
        } else {
            setCurrentPlaying(index, start);
            checkDownloads();
            if (start) {
                if (jukeboxEnabled) {
                    jukeboxService.skip(getCurrentPlayingIndex(), 0);
                    setPlayerState(STARTED);
                } else {
                    bufferAndPlay();
                }
            }
        }
    }

    /** Plays or resumes the playback, depending on the current player state. */
    public synchronized void togglePlayPause()
    {
        if (playerState == PAUSED || playerState == COMPLETED) {
            start();
        } else if (playerState == STOPPED || playerState == IDLE) {
        	play();
        } else if (playerState == STARTED) {
            pause();
        }
    }

    @Override
    public synchronized void seekTo(int position) {
        try {
            if (jukeboxEnabled) {
                jukeboxService.skip(getCurrentPlayingIndex(), position / 1000);
            } else {
            	if (secondaryProgress == -1 || secondaryProgress >= position)
            		mediaPlayer.seekTo(position);
            }
        } catch (Exception x) {
            handleError(x);
        }
    }

    @Override
    public synchronized void previous() {
        int index = getCurrentPlayingIndex();
        if (index == -1) {
            return;
        }

        // Restart song if played more than five seconds.
        if (getPlayerPosition() > 5000 || index == 0) {
            play(index);
        } else {
            play(index - 1);
        }
    }

    @Override
    public synchronized void next() {
        int index = getCurrentPlayingIndex();
        if (index != -1) {
            play(index + 1);
        }
    }

    private void onSongCompleted() {
        int index = getCurrentPlayingIndex();
        
        if (index != -1) {
            switch (getRepeatMode()) {
                case OFF:
                    play(index + 1);
                    break;
                case ALL:
                    play((index + 1) % size());
                    break;
                case SINGLE:
                    play(index);
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public synchronized void pause() {
        try {
            if (playerState == STARTED) {
                if (jukeboxEnabled) {
                    jukeboxService.stop();
                } else {
                    mediaPlayer.pause();
                }
                setPlayerState(PAUSED);
            }
        } catch (Exception x) {
            handleError(x);
        }
    }
    
    @Override
    public synchronized void stop() {
		audioManager.abandonAudioFocus(_afChangeListener);
    	
        try {
            if (playerState == STARTED) {
                if (jukeboxEnabled) {
                    jukeboxService.stop();
                } else {
                    mediaPlayer.pause();
                }
                setPlayerState(PAUSED);
            }
        } catch (Exception x) {
            handleError(x);
        }
        
        //seekTo(0);
    }

    @Override
    public synchronized void start() {
        try {
            if (jukeboxEnabled) {
                jukeboxService.start();
            } else {
                mediaPlayer.start();
            }
            setPlayerState(STARTED);
        } catch (Exception x) {
            handleError(x);
        }
    }

    @Override
    public synchronized void reset() {
        if (bufferTask != null) {
            bufferTask.cancel();
        }
        try {
            mediaPlayer.reset();
            setPlayerState(IDLE);
        } catch (Exception x) {
            handleError(x);
        }
    }

    @Override
    public synchronized int getPlayerPosition() {
        try {
            if (playerState == IDLE || playerState == DOWNLOADING || playerState == PREPARING) {
                return 0;
            }
            if (jukeboxEnabled) {
                return jukeboxService.getPositionSeconds() * 1000;
            } else {
           		return mediaPlayer.getCurrentPosition();
            }
        } catch (Exception x) {
            handleError(x);
            return 0;
        }
    }

    @Override
    public synchronized int getPlayerDuration() {
        if (currentPlaying != null) {
            Integer duration = currentPlaying.getSong().getDuration();
            if (duration != null) {
                return duration * 1000;
            }
        }
        if (playerState != IDLE && playerState != DOWNLOADING && playerState != PREPARING) {
            try {
                return mediaPlayer.getDuration();
            } catch (Exception x) {
                handleError(x);
            }
        }
        return 0;
    }

    @Override
    public PlayerState getPlayerState() {
        return playerState;
    }

    synchronized void setPlayerState(PlayerState playerState) {
        Log.i(TAG, this.playerState.name() + " -> " + playerState.name() + " (" + currentPlaying + ")");

        this.playerState = playerState;
        
        if (this.playerState == PAUSED) {
            lifecycleSupport.serializeDownloadQueue();
        }
        
        if (this.playerState == PlayerState.STARTED) {
    		audioManager.requestAudioFocus(_afChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        
        boolean showWhenPaused = (this.playerState == PlayerState.PAUSED && Util.isNotificationAlwaysEnabled(this));

        boolean show = this.playerState == PlayerState.STARTED || showWhenPaused;
        boolean hide = this.playerState == PlayerState.IDLE || this.playerState == PlayerState.STOPPED || !showWhenPaused;
        Util.broadcastPlaybackStatusChange(this, this.playerState);
        Util.broadcastA2dpPlayStatusChange(this, this.playerState, getInstance());

        // Set remote control
        updateRemoteControl();
        
        // Update widget
        UltraSonicAppWidgetProvider4x1.getInstance().notifyChange(this, this, this.playerState == PlayerState.STARTED);
        
       	if (show) {
       		if (currentPlaying != null) {
       			Util.showPlayingNotification(this, this, handler, currentPlaying.getSong(), this.notification, this.playerState);
       			
                SubsonicTabActivity tabInstance = SubsonicTabActivity.getInstance();
                if (tabInstance != null) {
                	tabInstance.showNowPlaying(this, this, currentPlaying.getSong(), this.playerState);
                }
       		}
       	} else if (hide) {
       		Util.hidePlayingNotification(this, this, handler);
       		
            SubsonicTabActivity tabInstance = SubsonicTabActivity.getInstance();
            if (tabInstance != null) {
            	tabInstance.hideNowPlaying();
            }
       	}
        
        if (this.playerState == STARTED) {
            scrobbler.scrobble(this, currentPlaying, false);
        } else if (this.playerState == COMPLETED) {
            scrobbler.scrobble(this, currentPlaying, true);
        }
    }

    @Override
    public void setSuggestedPlaylistName(String name) {
        this.suggestedPlaylistName = name;
    }

    @Override
    public String getSuggestedPlaylistName() {
        return suggestedPlaylistName;
    }

    @Override
    public EqualizerController getEqualizerController() {
        return equalizerController;
    }

    @Override
    public VisualizerController getVisualizerController() {
        return visualizerController;
    }

    @Override
    public boolean isJukeboxEnabled() {
        return jukeboxEnabled;
    }

    @Override
    public void setJukeboxEnabled(boolean jukeboxEnabled) {
        this.jukeboxEnabled = jukeboxEnabled;
        jukeboxService.setEnabled(jukeboxEnabled);
        if (jukeboxEnabled) {
            reset();
            
            // Cancel current download, if necessary.
            if (currentDownloading != null) {
                currentDownloading.cancelDownload();
            }
        }
    }

    @Override
    public void adjustJukeboxVolume(boolean up) {
        jukeboxService.adjustVolume(up);
    }

    private void updateRemoteControl() {
    	if (Util.isLockScreenEnabled(this)) {
        	if (remoteControlClient == null) {
        		Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        		intent.setComponent(new ComponentName(this.getPackageName(), MediaButtonIntentReceiver.class.getName()));
        		remoteControlClient = new RemoteControlClient(PendingIntent.getBroadcast(this, 0, intent, 0));
        	}
        	
        	audioManager.registerRemoteControlClient(remoteControlClient);
        		
            remoteControlClient.setTransportControlFlags(
            		RemoteControlClient.FLAG_KEY_MEDIA_PLAY |
            		RemoteControlClient.FLAG_KEY_MEDIA_PAUSE |
            		RemoteControlClient.FLAG_KEY_MEDIA_NEXT |
            		RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS |
            		RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE |
            		RemoteControlClient.FLAG_KEY_MEDIA_STOP);
        	
        	switch (playerState)
        	{
        	case STARTED:
        		remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
        		break;
        	case PAUSED:
        		remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
        		break;
        	case IDLE:
        	case STOPPED:
        		remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
        		break;
        	}	

        	try {
				if (currentPlaying != null) {
					if (currentSong != currentPlaying.getSong()) {
						currentSong = currentPlaying.getSong();
					}

					String artist = currentSong.getArtist();
					String album = currentSong.getAlbum();
					String title = artist + " - " + currentSong.getTitle();
					Integer duration = currentSong.getDuration();

					DisplayMetrics metrics = this.getResources().getDisplayMetrics();
					int size = Math.min(metrics.widthPixels, metrics.heightPixels);
					// Always get the album art from disk
					Bitmap bitmap = FileUtil.getAlbumArtBitmap(this, currentSong, size);

					// Update the remote controls
					remoteControlClient
							.editMetadata(true)
							.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, title)
							.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, artist)
							.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, album)
							.apply();
					
					if (duration != null) {
						remoteControlClient
						.editMetadata(false)
						.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, duration)
						.apply();
					}
					
					if (bitmap != null) {
							remoteControlClient
								.editMetadata(false)
								.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, bitmap)
								.apply();
					}
				}
			}
        	catch (Exception e) {
        		Log.e(TAG, "Exception in setRemoteControl", e);
        	}
        }
    }
    
    private synchronized void bufferAndPlay() {
        reset();

        bufferTask = new BufferTask(currentPlaying, 0);
        bufferTask.start();
    }

    private synchronized void doPlay(final DownloadFile downloadFile, final int position, final boolean start) {
        try {
            final File file = downloadFile.isCompleteFileAvailable() ? downloadFile.getCompleteFile() : downloadFile.getPartialFile();
            downloadFile.updateModificationDate();
            
            mediaPlayer.setOnCompletionListener(null);
            mediaPlayer.setOnBufferingUpdateListener(null);
            mediaPlayer.reset();
            secondaryProgress = -1; // Ensure seeking in non StreamProxy playback works 
            setPlayerState(IDLE);
                        
            mediaPlayer.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
				@Override
				public void onBufferingUpdate(MediaPlayer mp, int percent) {
					SeekBar progressBar = DownloadActivity.getProgressBar();
					MusicDirectory.Entry song = downloadFile.getSong();
					
					if (progressBar != null && song.getTranscodedContentType() == null && Util.getMaxBitrate(getApplicationContext()) == 0) {
						secondaryProgress = (int) (((double)percent / (double)100) * progressBar.getMax());
						progressBar.setSecondaryProgress(secondaryProgress);
					}
				}
			});
            
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mediaPlayer) {

                    // Acquire a temporary wakelock, since when we return from
                    // this callback the MediaPlayer will release its wakelock
                    // and allow the device to go to sleep.
                    wakeLock.acquire(60000);

                    setPlayerState(COMPLETED);

                    // If COMPLETED and not playing partial file, we are *really" finished
                    // with the song and can move on to the next.
                    if (!file.equals(downloadFile.getPartialFile())) {
                        onSongCompleted();
                        return;
                    }

                    // If file is not completely downloaded, restart the playback from the current position.
                    int pos = mediaPlayer.getCurrentPosition();
                    synchronized (DownloadServiceImpl.this) {

                        // Work-around for apparent bug on certain phones: If close (less than ten seconds) to the end
                        // of the song, skip to the next rather than restarting it.
                        Integer duration = downloadFile.getSong().getDuration() == null ? null : downloadFile.getSong().getDuration() * 1000;
                        
                        if (duration != null) {
                            if (Math.abs(duration - pos) < 10000) {
                                Log.i(TAG, "Skipping restart from " + pos  + " of " + duration);
                                onSongCompleted();
                                return;
                            }
                        }

                        Log.i(TAG, "Requesting restart from " + pos  + " of " + duration);
                        reset();
                        bufferTask = new BufferTask(downloadFile, pos);
                        bufferTask.start();
                    }
                }
            });
            
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
				@Override
				public void onPrepared(MediaPlayer mp) {
		            Log.i(TAG, "Media player prepared");
					
					setPlayerState(PREPARED);
					
					SeekBar progressBar = DownloadActivity.getProgressBar();
					
					if (progressBar != null && downloadFile.isCompleteFileAvailable()) {
						// Populate seek bar secondary progress if we have a complete file for consistency
						DownloadActivity.getProgressBar().setSecondaryProgress(100 * progressBar.getMax());
					}
					
					if (position != 0) {
		                Log.i(TAG, "Restarting player from position " + position);
		                mp.seekTo(position);
		            }

		            if (start) {
		            	mp.start();
		                setPlayerState(STARTED);
		            } else {
		                setPlayerState(STOPPED);
		            }
		            
		            lifecycleSupport.serializeDownloadQueue();
				}
			});
            
            String url = file.getPath();
            String playUrl = url;
            
            // Only use stream proxy if it is enabled and no complete file is available
            if (!downloadFile.isOffline() && downloadFile.isStreamProxyEnabled() && !downloadFile.isCompleteFileAvailable()) {
                if (proxy == null) {
                    Log.i(TAG, "Creating StreamProxy instance");
                	
                    proxy = new StreamProxy();
                    proxy.start();
                }
                
                proxy.setDownloadFile(downloadFile);
                playUrl = String.format("http://127.0.0.1:%d/%s", proxy.getPort(), url);
            }
            
            Log.i(TAG, "Setting media player data source: " + playUrl);
            
            mediaPlayer.setDataSource(playUrl);
            setPlayerState(PREPARING);
            
            Log.i(TAG, "Preparing media player");
            		
            mediaPlayer.prepareAsync();
        } catch (Exception x) {
            handleError(x);
        }
    }
    
    private void handleError(Exception x) {
        Log.w(TAG, "Media player error: " + x, x);
        mediaPlayer.reset();
        setPlayerState(IDLE);
    }

    protected synchronized void checkDownloads() {

        if (!Util.isExternalStoragePresent() || !lifecycleSupport.isExternalStorageAvailable()) {
            return;
        }

        if (shufflePlay) {
            checkShufflePlay();
        }

        if (jukeboxEnabled || !Util.isNetworkConnected(this)) {
            return;
        }

        if (downloadList.isEmpty()) {
            return;
        }

        // Need to download current playing?
        if (currentPlaying != null &&
                currentPlaying != currentDownloading &&
                !currentPlaying.isCompleteFileAvailable()) {

            // Cancel current download, if necessary.
            if (currentDownloading != null) {
                currentDownloading.cancelDownload();
            }

            currentDownloading = currentPlaying;
            currentDownloading.download();
            cleanupCandidates.add(currentDownloading);
        }

        // Find a suitable target for download.
        else if (currentDownloading == null || currentDownloading.isWorkDone() || currentDownloading.isFailed()) {

            int n = size();
            if (n == 0) {
                return;
            }

            int preloaded = 0;

            int start = currentPlaying == null ? 0 : getCurrentPlayingIndex();
            int i = start;
            do {
                DownloadFile downloadFile = downloadList.get(i);
                if (!downloadFile.isWorkDone()) {
                    if (downloadFile.shouldSave() || preloaded < Util.getPreloadCount(this)) {
                        currentDownloading = downloadFile;
                        currentDownloading.download();
                        cleanupCandidates.add(currentDownloading);
                        break;
                    }
                } else if (currentPlaying != downloadFile) {
                    preloaded++;
                }

                i = (i + 1) % n;
            } while (i != start);
        }

        // Delete obsolete .partial and .complete files.
        cleanup();
    }

    private synchronized void checkShufflePlay() {

        final int listSize = 20;
        boolean wasEmpty = downloadList.isEmpty();

        long revisionBefore = revision;

        // First, ensure that list is at least 20 songs long.
        int size = size();
        if (size < listSize) {
            for (MusicDirectory.Entry song : shufflePlayBuffer.get(listSize - size)) {
                DownloadFile downloadFile = new DownloadFile(this, song, false);
                downloadList.add(downloadFile);
                revision++;
            }
        }

        int currIndex = currentPlaying == null ? 0 : getCurrentPlayingIndex();

        // Only shift playlist if playing song #5 or later.
        if (currIndex > 4) {
            int songsToShift = currIndex - 2;
            for (MusicDirectory.Entry song : shufflePlayBuffer.get(songsToShift)) {
                downloadList.add(new DownloadFile(this, song, false));
                downloadList.get(0).cancelDownload();
                downloadList.remove(0);
                revision++;
            }
        }

        if (revisionBefore != revision) {
            updateJukeboxPlaylist();
        }

        if (wasEmpty && !downloadList.isEmpty()) {
            play(0);
        }
    }

    public long getDownloadListUpdateRevision() {
        return revision;
    }

    private synchronized void cleanup() {
        Iterator<DownloadFile> iterator = cleanupCandidates.iterator();
        while (iterator.hasNext()) {
            DownloadFile downloadFile = iterator.next();
            if (downloadFile != currentPlaying && downloadFile != currentDownloading) {
                if (downloadFile.cleanup()) {
                    iterator.remove();
                }
            }
        }
    }

    private class BufferTask extends CancellableTask {

        private final DownloadFile downloadFile;
        private final int position;
        private final long expectedFileSize;
        private final File partialFile;

        public BufferTask(DownloadFile downloadFile, int position) {
            this.downloadFile = downloadFile;
            this.position = position;
            partialFile = downloadFile.getPartialFile();
            int bufferLength = downloadFile.getBufferLength();

			// Calculate roughly how many bytes buffer length corresponds to.
			int bitRate = downloadFile.getBitRate();
			long byteCount = Math.max(100000, bitRate * 1024 / 8 * bufferLength);

			// Find out how large the file should grow before resuming playback.
			if (position == 0) {
				expectedFileSize = byteCount;
			} else {
				expectedFileSize = partialFile.length() + byteCount;
			}
        }

        @Override
        public void execute() {
        	if (!getIsCompleteFileAvailable(downloadFile)) {
                setPlayerState(DOWNLOADING);

                while (!bufferComplete()) {
                    Util.sleepQuietly(50L);
                    if (isCancelled()) {
                        return;
                    }
                }

        	}
        	
            doPlay(downloadFile, position, true);
        }
        
        private boolean getIsCompleteFileAvailable(DownloadFile downloadFile) {
            boolean completeFileAvailable = downloadFile.isCompleteFileAvailable();
           
            if (completeFileAvailable) {
            	Log.i(TAG, "Buffering complete: Complete file exists (" + completeFileAvailable + ")");
            	return true;
            }
            
            return false;
        }

		private boolean bufferComplete() {
			if (!getIsCompleteFileAvailable(downloadFile)) {
				long size = partialFile.length();
				if (size >= expectedFileSize) {
					Log.i(TAG, "Buffering complete: " + partialFile + " ("
							+ size + "/" + expectedFileSize + ")");
					return true;
				}

				Log.i(TAG, "Buffering incomplete: " + partialFile + " (" + size
						+ "/" + expectedFileSize + ")");
				return false;
			} else {
				return true;
			}
		}

        @Override
        public String toString() {
            return "BufferTask (" + downloadFile + ")";
        }
    }
}