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
package com.thejoshwa.ultrasonic.androidapp.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.SearchRecentSuggestions;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

import com.thejoshwa.ultrasonic.androidapp.R;
import com.thejoshwa.ultrasonic.androidapp.provider.SearchSuggestionProvider;
import com.thejoshwa.ultrasonic.androidapp.service.DownloadService;
import com.thejoshwa.ultrasonic.androidapp.service.DownloadServiceImpl;
import com.thejoshwa.ultrasonic.androidapp.service.MusicService;
import com.thejoshwa.ultrasonic.androidapp.service.MusicServiceFactory;
import com.thejoshwa.ultrasonic.androidapp.util.Constants;
import com.thejoshwa.ultrasonic.androidapp.util.ErrorDialog;
import com.thejoshwa.ultrasonic.androidapp.util.FileUtil;
import com.thejoshwa.ultrasonic.androidapp.util.ModalBackgroundTask;
import com.thejoshwa.ultrasonic.androidapp.util.Util;

import java.io.File;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import net.simonvt.menudrawer.MenuDrawer;
import net.simonvt.menudrawer.Position;

public class SettingsActivity extends PreferenceActivity implements SharedPreferences.OnSharedPreferenceChangeListener, OnClickListener {

    private static final String TAG = SettingsActivity.class.getSimpleName();
    private final Map<String, ServerSettings> serverSettings = new LinkedHashMap<String, ServerSettings>();
    private boolean testingConnection;
    private ListPreference theme;
    private ListPreference maxBitrateWifi;
    private ListPreference maxBitrateMobile;
    private ListPreference cacheSize;
    private EditTextPreference cacheLocation;
    private ListPreference preloadCount;
    private ListPreference bufferLength;
    private ListPreference networkTimeout;
    private ListPreference maxAlbums;
    private ListPreference maxSongs;
    private ListPreference maxArtists;
    private ListPreference defaultAlbums;
    private ListPreference defaultSongs;
    private ListPreference defaultArtists;
    private CheckBoxPreference mediaButtonsEnabled;
    private CheckBoxPreference lockScreenEnabled;
    
    private static final String STATE_MENUDRAWER = "com.thejoshwa.ultrasonic.androidapp.menuDrawer";
    private static final String STATE_ACTIVE_VIEW_ID = "com.thejoshwa.ultrasonic.androidapp.activeViewId";
    private static final String STATE_ACTIVE_POSITION = "com.thejoshwa.ultrasonic.androidapp.activePosition";
    
    public MenuDrawer menuDrawer;    
    private int activePosition = 1;
    private int menuActiveViewId;
    View searchMenuItem = null;
    View playlistsMenuItem = null;
    View menuMain = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.settings);
        
        if (savedInstanceState != null) {
            activePosition = savedInstanceState.getInt(STATE_ACTIVE_POSITION);
            menuActiveViewId = savedInstanceState.getInt(STATE_ACTIVE_VIEW_ID);
        }
        
        menuDrawer = MenuDrawer.attach(this, MenuDrawer.MENU_DRAG_WINDOW, Position.LEFT);
        menuDrawer.setMenuView(R.layout.menu_main);
        
        searchMenuItem = findViewById(R.id.menu_search);
        playlistsMenuItem = findViewById(R.id.menu_playlists);
        
        findViewById(R.id.menu_home).setOnClickListener(this);
        findViewById(R.id.menu_browse).setOnClickListener(this);
        searchMenuItem.setOnClickListener(this);
        playlistsMenuItem.setOnClickListener(this);
        findViewById(R.id.menu_now_playing).setOnClickListener(this);
        findViewById(R.id.menu_settings).setOnClickListener(this);
        findViewById(R.id.menu_about).setOnClickListener(this);
        findViewById(R.id.menu_exit).setOnClickListener(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        
        View browseMenuItem = findViewById(R.id.menu_settings);
        menuDrawer.setActiveView(browseMenuItem);
        
        TextView activeView = (TextView)findViewById(menuActiveViewId);
        
        if (activeView != null) {
            menuDrawer.setActiveView(activeView);
        }

        theme = (ListPreference) findPreference(Constants.PREFERENCES_KEY_THEME);
        maxBitrateWifi = (ListPreference) findPreference(Constants.PREFERENCES_KEY_MAX_BITRATE_WIFI);
        maxBitrateMobile = (ListPreference) findPreference(Constants.PREFERENCES_KEY_MAX_BITRATE_MOBILE);
        cacheSize = (ListPreference) findPreference(Constants.PREFERENCES_KEY_CACHE_SIZE);
        cacheLocation = (EditTextPreference) findPreference(Constants.PREFERENCES_KEY_CACHE_LOCATION);
        preloadCount = (ListPreference) findPreference(Constants.PREFERENCES_KEY_PRELOAD_COUNT);
        bufferLength = (ListPreference) findPreference(Constants.PREFERENCES_KEY_BUFFER_LENGTH);
        networkTimeout = (ListPreference) findPreference(Constants.PREFERENCES_KEY_NETWORK_TIMEOUT);
        maxAlbums = (ListPreference) findPreference(Constants.PREFERENCES_KEY_MAX_ALBUMS);
        maxSongs = (ListPreference) findPreference(Constants.PREFERENCES_KEY_MAX_SONGS);
        maxArtists = (ListPreference) findPreference(Constants.PREFERENCES_KEY_MAX_ARTISTS);
        defaultArtists = (ListPreference) findPreference(Constants.PREFERENCES_KEY_DEFAULT_ARTISTS);
        defaultSongs = (ListPreference) findPreference(Constants.PREFERENCES_KEY_DEFAULT_SONGS);
        defaultAlbums = (ListPreference) findPreference(Constants.PREFERENCES_KEY_DEFAULT_ALBUMS);
        mediaButtonsEnabled = (CheckBoxPreference) findPreference(Constants.PREFERENCES_KEY_MEDIA_BUTTONS);
        lockScreenEnabled = (CheckBoxPreference) findPreference(Constants.PREFERENCES_KEY_SHOW_LOCK_SCREEN_CONTROLS);

        findPreference("testConnection1").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                testConnection(1);
                return false;
            }
        });

        findPreference("testConnection2").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                testConnection(2);
                return false;
            }
        });

        findPreference("testConnection3").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                testConnection(3);
                return false;
            }
        });

        findPreference("clearSearchHistory").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                SearchRecentSuggestions suggestions = new SearchRecentSuggestions(SettingsActivity.this, SearchSuggestionProvider.AUTHORITY, SearchSuggestionProvider.MODE);
                suggestions.clearHistory();
                Util.toast(SettingsActivity.this, R.string.settings_search_history_cleared);
                return false;
            }
        });

        for (int i = 1; i <= 3; i++) {
            String instance = String.valueOf(i);
            serverSettings.put(instance, new ServerSettings(instance));
        }

        SharedPreferences prefs = Util.getPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        
        getActionBar().setSubtitle(R.string.menu_settings);

        update();
    }
    
    private void applyTheme() {
        String theme = Util.getTheme(this);
        
        if ("dark".equalsIgnoreCase(theme) || "fullscreen".equalsIgnoreCase(theme)) {
            setTheme(R.style.UltraSonicTheme);
        } else if ("light".equalsIgnoreCase(theme) || "fullscreenlight".equalsIgnoreCase(theme)) {
            setTheme(R.style.UltraSonicTheme_Light);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        SharedPreferences prefs = Util.getPreferences(this);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Log.d(TAG, "Preference changed: " + key);
        update();

        if (Constants.PREFERENCES_KEY_HIDE_MEDIA.equals(key)) {
            setHideMedia(sharedPreferences.getBoolean(key, false));
        }
        else if (Constants.PREFERENCES_KEY_MEDIA_BUTTONS.equals(key)) {
            setMediaButtonsEnabled(sharedPreferences.getBoolean(key, true));
        }
        else if (Constants.PREFERENCES_KEY_CACHE_LOCATION.equals(key)) {
            setCacheLocation(sharedPreferences.getString(key, ""));
        }
    }

    private void update() {
        if (testingConnection) {
            return;
        }
        
        theme.setSummary(theme.getEntry());
        maxBitrateWifi.setSummary(maxBitrateWifi.getEntry());
        maxBitrateMobile.setSummary(maxBitrateMobile.getEntry());
        cacheSize.setSummary(cacheSize.getEntry());
        cacheLocation.setSummary(cacheLocation.getText());
        preloadCount.setSummary(preloadCount.getEntry());
        bufferLength.setSummary(bufferLength.getEntry());
        networkTimeout.setSummary(networkTimeout.getEntry());
        maxAlbums.setSummary(maxAlbums.getEntry());
        maxArtists.setSummary(maxArtists.getEntry());
        maxSongs.setSummary(maxSongs.getEntry());
        defaultAlbums.setSummary(defaultAlbums.getEntry());
        defaultArtists.setSummary(defaultArtists.getEntry());
        defaultSongs.setSummary(defaultSongs.getEntry());
        
        if (!mediaButtonsEnabled.isChecked()) {
        	lockScreenEnabled.setChecked(false);
        	lockScreenEnabled.setEnabled(false);
        }
        
        for (ServerSettings ss : serverSettings.values()) {
            ss.update();
        }
    }

    private void setHideMedia(boolean hide) {
        File nomediaDir = new File(FileUtil.getUltraSonicDirectory(), ".nomedia");
        if (hide && !nomediaDir.exists()) {
            if (!nomediaDir.mkdir()) {
                Log.w(TAG, "Failed to create " + nomediaDir);
            }
        } else if (nomediaDir.exists()) {
            if (!nomediaDir.delete()) {
                Log.w(TAG, "Failed to delete " + nomediaDir);
            }
        }
        Util.toast(this, R.string.settings_hide_media_toast, false);
    }

    private void setMediaButtonsEnabled(boolean enabled) {
        if (enabled) {
        	lockScreenEnabled.setEnabled(true);
            Util.registerMediaButtonEventReceiver(this);
        } else {
        	lockScreenEnabled.setEnabled(false);
            Util.unregisterMediaButtonEventReceiver(this);
        }
    }
    
    private void setCacheLocation(String path) {
        File dir = new File(path);
        if (!FileUtil.ensureDirectoryExistsAndIsReadWritable(dir)) {
            Util.toast(this, R.string.settings_cache_location_error, false);

            // Reset it to the default.
            String defaultPath = FileUtil.getDefaultMusicDirectory().getPath();
            if (!defaultPath.equals(path)) {
                SharedPreferences prefs = Util.getPreferences(this);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(Constants.PREFERENCES_KEY_CACHE_LOCATION, defaultPath);
                editor.commit();
                cacheLocation.setSummary(defaultPath);
                cacheLocation.setText(defaultPath);
            }

            // Clear download queue.
            DownloadService downloadService = DownloadServiceImpl.getInstance();
            downloadService.clear();
        }
    }

    private void testConnection(final int instance) {
        ModalBackgroundTask<Boolean> task = new ModalBackgroundTask<Boolean>(this, false) {
            private int previousInstance;

            @Override
            protected Boolean doInBackground() throws Throwable {
                updateProgress(R.string.settings_testing_connection);

                previousInstance = Util.getActiveServer(SettingsActivity.this);
                testingConnection = true;
                Util.setActiveServer(SettingsActivity.this, instance);
                try {
                    MusicService musicService = MusicServiceFactory.getMusicService(SettingsActivity.this);
                    musicService.ping(SettingsActivity.this, this);
                    return musicService.isLicenseValid(SettingsActivity.this, null);
                } finally {
                    Util.setActiveServer(SettingsActivity.this, previousInstance);
                    testingConnection = false;
                }
            }

            @Override
            protected void done(Boolean licenseValid) {
                if (licenseValid) {
                    Util.toast(SettingsActivity.this, R.string.settings_testing_ok);
                } else {
                    Util.toast(SettingsActivity.this, R.string.settings_testing_unlicensed);
                }
            }

            @Override
            protected void cancel() {
                super.cancel();
                Util.setActiveServer(SettingsActivity.this, previousInstance);
            }

            @Override
            protected void error(Throwable error) {
                Log.w(TAG, error.toString(), error);
                new ErrorDialog(SettingsActivity.this, getResources().getString(R.string.settings_connection_failure) +
                        " " + getErrorMessage(error), false);
            }
        };
        task.execute();
    }

    private class ServerSettings {
        private EditTextPreference serverName;
        private EditTextPreference serverUrl;
        private EditTextPreference username;
        private PreferenceScreen screen;

        private ServerSettings(String instance) {

            screen = (PreferenceScreen) findPreference("server" + instance);
            serverName = (EditTextPreference) findPreference(Constants.PREFERENCES_KEY_SERVER_NAME + instance);
            serverUrl = (EditTextPreference) findPreference(Constants.PREFERENCES_KEY_SERVER_URL + instance);
            username = (EditTextPreference) findPreference(Constants.PREFERENCES_KEY_USERNAME + instance);

            serverUrl.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object value) {
                    try {
                        String url = (String) value;
                        new URL(url);
                        if (!url.equals(url.trim()) || url.contains("@")) {
                            throw new Exception();
                        }
                    } catch (Exception x) {
                        new ErrorDialog(SettingsActivity.this, R.string.settings_invalid_url, false);
                        return false;
                    }
                    return true;
                }
            });

            username.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object value) {
                    String username = (String) value;
                    if (username == null || !username.equals(username.trim())) {
                        new ErrorDialog(SettingsActivity.this, R.string.settings_invalid_username, false);
                        return false;
                    }
                    return true;
                }
            });
        }

        public void update() {
            serverName.setSummary(serverName.getText());
            serverUrl.setSummary(serverUrl.getText());
            username.setSummary(username.getText());
            screen.setSummary(serverUrl.getText());
            screen.setTitle(serverName.getText());
        }
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        	case android.R.id.home:
        		menuDrawer.toggleMenu();
        		return true;
        }

        return super.onOptionsItemSelected(item);
    }
    
    @Override
    protected void onRestoreInstanceState(Bundle inState) {
        super.onRestoreInstanceState(inState);
        menuDrawer.restoreState(inState.getParcelable(STATE_MENUDRAWER));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(STATE_MENUDRAWER, menuDrawer.saveState());
        outState.putInt(STATE_ACTIVE_VIEW_ID, menuActiveViewId);
        outState.putInt(STATE_ACTIVE_POSITION, activePosition);
    }
    
    @Override
    public void onBackPressed() {
        final int drawerState = menuDrawer.getDrawerState();
        
        if (drawerState == MenuDrawer.STATE_OPEN || drawerState == MenuDrawer.STATE_OPENING) {
            menuDrawer.closeMenu();
            return;
        }

        super.onBackPressed();
    }

    @Override
    public void onClick(View v) {
        menuActiveViewId = v.getId();
        
        Intent intent;
        
        switch (menuActiveViewId) {
    		case R.id.menu_home:
    			intent = new Intent(this, MainActivity.class);
    			intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    			Util.startActivityWithoutTransition(this, intent);
    			break;
    		case R.id.menu_browse:
    			intent = new Intent(this, SelectArtistActivity.class);
    			intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    			Util.startActivityWithoutTransition(this, intent);
    			break;
    		case R.id.menu_search:
    			intent = new Intent(this, SearchActivity.class);
    			intent.putExtra(Constants.INTENT_EXTRA_REQUEST_SEARCH, true);
    			Util.startActivityWithoutTransition(this, intent);
    			break;
    		case R.id.menu_playlists:
    			intent = new Intent(this, SelectPlaylistActivity.class);
    			intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    			Util.startActivityWithoutTransition(this, intent);
    			break;
    		case R.id.menu_now_playing:
    			Util.startActivityWithoutTransition(this, DownloadActivity.class);
    			break;
    		case R.id.menu_settings:
    			Util.startActivityWithoutTransition(this, SettingsActivity.class);
    			break;
    		case R.id.menu_about:
    			Util.startActivityWithoutTransition(this, HelpActivity.class);
    			break;
    		case R.id.menu_exit:
    			intent = new Intent(this, MainActivity.class);
    			intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    			intent.putExtra(Constants.INTENT_EXTRA_NAME_EXIT, true);
    			Util.startActivityWithoutTransition(this, intent);
    			break;
        }
        
        menuDrawer.closeMenu();
    }
}