/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.search.providers;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.gecko.AppConstants;
import org.mozilla.gecko.GeckoProfile;
import org.mozilla.gecko.GeckoSharedPrefs;
import org.mozilla.gecko.Locales;
import org.mozilla.gecko.R;
import org.mozilla.gecko.util.FileUtils;
import org.mozilla.gecko.util.GeckoJarReader;
import org.mozilla.gecko.util.HardwareUtils;
import org.mozilla.gecko.util.RawResource;
import org.mozilla.gecko.util.ThreadUtils;
import org.mozilla.gecko.distribution.Distribution;
import org.mozilla.search.Constants;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SearchEngineManager implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String LOG_TAG = "GeckoSearchEngineManager";

    // Gecko pref that defines the name of the default search engine.
    private static final String PREF_GECKO_DEFAULT_ENGINE = "browser.search.defaultenginename";

    // Key for shared preference that stores default engine name.
    private static final String PREF_DEFAULT_ENGINE_KEY = "search.engines.defaultname";

    // Key for shared preference that stores search region.
    private static final String PREF_REGION_KEY = "search.region";

    // URL for the geo-ip location service. Keep in sync with "browser.search.geoip.url" perference in Gecko.
    private static final String GEOIP_LOCATION_URL = "https://location.services.mozilla.com/v1/country?key=" + AppConstants.MOZ_MOZILLA_API_KEY;

    // This should go through GeckoInterface to get the UA, but the search activity
    // doesn't use a GeckoView yet. Until it does, get the UA directly.
    private static final String USER_AGENT = HardwareUtils.isTablet() ?
        AppConstants.USER_AGENT_FENNEC_TABLET : AppConstants.USER_AGENT_FENNEC_MOBILE;

    private Context context;
    private Distribution distribution;
    private SearchEngineCallback changeCallback;
    private SearchEngine engine;

    // Cached version of default locale included in Gecko chrome manifest.
    // This should only be accessed from the background thread.
    private String fallbackLocale;

    public static interface SearchEngineCallback {
        public void execute(SearchEngine engine);
    }

    public SearchEngineManager(Context context, Distribution distribution) {
        this.context = context;
        this.distribution = distribution;
        GeckoSharedPrefs.forApp(context).registerOnSharedPreferenceChangeListener(this);
    }

    /**
     * Sets a callback to be called when the default engine changes.
     *
     * @param changeCallback SearchEngineCallback to be called after the search engine
     *                       changed. This will run on the UI thread.
     *                       Note: callback may be called with null engine.
     */
    public void setChangeCallback(SearchEngineCallback changeCallback) {
        this.changeCallback = changeCallback;
    }

    /**
     * Perform an action with the user's default search engine.
     *
     * @param callback The callback to be used with the user's default search engine. The call
     *                 may be sync or async; if the call is async, it will be called on the
     *                 ui thread.
     */
    public void getEngine(SearchEngineCallback callback) {
        if (engine != null) {
            callback.execute(engine);
        } else {
            getDefaultEngine(callback);
        }
    }

    public void destroy() {
        GeckoSharedPrefs.forApp(context).unregisterOnSharedPreferenceChangeListener(this);
        context = null;
        distribution = null;
        changeCallback = null;
        engine = null;
    }

    private int ignorePreferenceChange = 0;

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (!TextUtils.equals(PREF_DEFAULT_ENGINE_KEY, key)) {
            return;
        }

        if (ignorePreferenceChange > 0) {
            ignorePreferenceChange--;
            return;
        }

        getDefaultEngine(changeCallback);
    }

    /**
     * Runs a SearchEngineCallback on the main thread.
     */
    private void runCallback(final SearchEngine engine, final SearchEngineCallback callback) {
        ThreadUtils.postToUiThread(new Runnable() {
            @Override
            public void run() {
                // Cache engine for future calls to getEngine.
                SearchEngineManager.this.engine = engine;
                callback.execute(engine);
            }
        });
    }

    /**
     * This method finds and creates the default search engine. It will first look for
     * the default engine name, then create the engine from that name.
     *
     * To find the default engine name, we first look in shared preferences, then
     * the distribution (if one exists), and finally fall back to the localized default.
     *
     * @param callback SearchEngineCallback to be called after successfully looking
     *                 up the search engine. This will run on the UI thread.
     *                 Note: callback may be called with null engine.
     */
    private void getDefaultEngine(final SearchEngineCallback callback) {
        // This runnable is posted to the background thread.
        distribution.addOnDistributionReadyCallback(new Distribution.ReadyCallback() {
            @Override
            public void distributionNotFound() {
                defaultBehavior();
            }

            @Override
            public void distributionFound(Distribution distribution) {
                defaultBehavior();
            }

            @Override
            public void distributionArrivedLate(Distribution distribution) {
                // Let's see if there's a name in the distro.
                // If so, just this once we'll override the saved value.
                final String name = getDefaultEngineNameFromDistribution();

                if (name == null) {
                    return;
                }

                // Store the default engine name for the future.
                // Increment an 'ignore' counter so that this preference change
                // won't cause getDefaultEngine to be called again.
                ignorePreferenceChange++;
                GeckoSharedPrefs.forApp(context)
                        .edit()
                        .putString(PREF_DEFAULT_ENGINE_KEY, name)
                        .apply();

                final SearchEngine engine = createEngineFromName(name);
                runCallback(engine, callback);
            }

            private void defaultBehavior() {
                // First look for a default name stored in shared preferences.
                String name = GeckoSharedPrefs.forApp(context).getString(PREF_DEFAULT_ENGINE_KEY, null);

                // Check for a region stored in shared preferences. If we don't have a region,
                // we should force a recheck of the default engine.
                String region = GeckoSharedPrefs.forApp(context).getString(PREF_REGION_KEY, null);

                if (name != null && region != null) {
                    Log.d(LOG_TAG, "Found default engine name in SharedPreferences: " + name);
                } else {
                    // First, look for the default search engine in a distribution.
                    name = getDefaultEngineNameFromDistribution();
                    if (name == null) {
                        // Otherwise, get the default engine that we ship.
                        name = getDefaultEngineNameFromLocale();
                    }

                    // Store the default engine name for the future.
                    // Increment an 'ignore' counter so that this preference change
                    // won't cause getDefaultEngine to be called again.
                    ignorePreferenceChange++;
                    GeckoSharedPrefs.forApp(context)
                                    .edit()
                                    .putString(PREF_DEFAULT_ENGINE_KEY, name)
                                    .apply();
                }

                final SearchEngine engine = createEngineFromName(name);
                runCallback(engine, callback);
            }
        });
    }

    /**
     * Looks for a default search engine included in a distribution.
     * This method must be called after the distribution is ready.
     *
     * @return search engine name.
     */
    private String getDefaultEngineNameFromDistribution() {
        if (!distribution.exists()) {
            return null;
        }

        final File prefFile = distribution.getDistributionFile("preferences.json");
        if (prefFile == null) {
            return null;
        }

        try {
            final JSONObject all = new JSONObject(FileUtils.getFileContents(prefFile));

            // First, check to see if there's a locale-specific override.
            final String languageTag = Locales.getLanguageTag(Locale.getDefault());
            final String overridesKey = "LocalizablePreferences." + languageTag;
            if (all.has(overridesKey)) {
                final JSONObject overridePrefs = all.getJSONObject(overridesKey);
                if (overridePrefs.has(PREF_GECKO_DEFAULT_ENGINE)) {
                    Log.d(LOG_TAG, "Found default engine name in distribution LocalizablePreferences override.");
                    return overridePrefs.getString(PREF_GECKO_DEFAULT_ENGINE);
                }
            }

            // Next, check to see if there's a non-override default pref.
            if (all.has("LocalizablePreferences")) {
                final JSONObject localizablePrefs = all.getJSONObject("LocalizablePreferences");
                if (localizablePrefs.has(PREF_GECKO_DEFAULT_ENGINE)) {
                    Log.d(LOG_TAG, "Found default engine name in distribution LocalizablePreferences.");
                    return localizablePrefs.getString(PREF_GECKO_DEFAULT_ENGINE);
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error getting search engine name from preferences.json", e);
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Error parsing preferences.json", e);
        }
        return null;
    }

    /**
     * Helper function for converting an InputStream to a String.
     * @param is InputStream you want to convert to a String
     *
     * @return String containing the data
     */
    private String getHttpResponse(HttpURLConnection conn) {
        InputStream is = null;
        try {
            is = new BufferedInputStream(conn.getInputStream());
            return new java.util.Scanner(is).useDelimiter("\\A").next();
        } catch (Exception e) {
            return "";
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Error closing InputStream", e);
                }
            }
        }
    }

    /**
     * Gets the country code based on the current IP, using the Mozilla Location Service.
     * We cache the country code in a shared preference, so we only fetch from the network
     * once.
     *
     * @return String containing the country code
     */
    private String fetchCountryCode() {
        // First, we look to see if we have a cached code.
        final String region = GeckoSharedPrefs.forApp(context).getString(PREF_REGION_KEY, null);
        if (region != null) {
            return region;
        }

        // Since we didn't have a cached code, we need to fetch a code from the service.
        try {
            String responseText = null;

            URL url = new URL(GEOIP_LOCATION_URL);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            try {
                // POST an empty JSON object.
                final String message = "{}";

                urlConnection.setDoOutput(true);
                urlConnection.setConnectTimeout(10000);
                urlConnection.setReadTimeout(10000);
                urlConnection.setRequestMethod("POST");
                urlConnection.setRequestProperty("User-Agent", USER_AGENT);
                urlConnection.setRequestProperty("Content-Type", "application/json");
                urlConnection.setFixedLengthStreamingMode(message.getBytes().length);

                final OutputStream out = urlConnection.getOutputStream();
                out.write(message.getBytes());
                out.close();

                responseText = getHttpResponse(urlConnection);
            } finally {
                urlConnection.disconnect();
            }

            if (responseText == null) {
                Log.e(LOG_TAG, "Country code fetch failed");
                return null;
            }

            // Extract the country code and save it for later in a cache.
            final JSONObject response = new JSONObject(responseText);
            return response.optString("country_code", null);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Country code fetch failed", e);
        }

        return null;
    }

    /**
     * Looks for the default search engine shipped in the locale.
     *
     * @return search engine name.
     */
    private String getDefaultEngineNameFromLocale() {
        try {
            final JSONObject browsersearch = new JSONObject(RawResource.getAsString(context, R.raw.browsersearch));

            // Get the region used to fence search engines.
            String region = fetchCountryCode();

            // Store the result, even if it's empty. If we fail to get a region, we never
            // try to get it again, and we will always fallback to the non-region engine.
            GeckoSharedPrefs.forApp(context)
                            .edit()
                            .putString(PREF_REGION_KEY, (region == null ? "" : region))
                            .apply();

            if (region != null) {
                if (browsersearch.has("regions")) {
                    final JSONObject regions = browsersearch.getJSONObject("regions");
                    if (regions.has(region)) {
                        final JSONObject regionData = regions.getJSONObject(region);
                        Log.d(LOG_TAG, "Found region-specific default engine name in browsersearch.json.");
                        return regionData.getString("default");
                    }
                }
            }

            // Either we have no geoip region, or we didn't find the right region and we are falling back to the default.
            if (browsersearch.has("default")) {
                Log.d(LOG_TAG, "Found default engine name in browsersearch.json.");
                return browsersearch.getString("default");
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error getting search engine name from browsersearch.json", e);
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Error parsing browsersearch.json", e);
        }
        return null;
    }

    /**
     * Creates a SearchEngine instance from an engine name.
     *
     * To create the engine, we first try to find the search plugin in the distribution
     * (if one exists), followed by the localized plugins we ship with the browser, and
     * then finally third-party plugins that are installed in the profile directory.
     *
     * This method must be called after the distribution is ready.
     *
     * @param name The search engine name (e.g. "Google" or "Amazon.com")
     * @return SearchEngine instance for name.
     */
    private SearchEngine createEngineFromName(String name) {
        // First, look in the distribution.
        SearchEngine engine = createEngineFromDistribution(name);

        // Second, look in the jar for plugins shipped with the locale.
        if (engine == null) {
            engine = createEngineFromLocale(name);
        }

        // Finally, look in the profile for third-party plugins.
        if (engine == null) {
            engine = createEngineFromProfile(name);
        }

        if (engine == null) {
            Log.e(LOG_TAG, "Could not create search engine from name: " + name);
        }

        return engine;
    }

    /**
     * Creates a SearchEngine instance for a distribution search plugin.
     *
     * This method iterates through the distribution searchplugins directory,
     * creating SearchEngine instances until it finds one with the right name.
     *
     * This method must be called after the distribution is ready.
     *
     * @param name Search engine name.
     * @return SearchEngine instance for name.
     */
    private SearchEngine createEngineFromDistribution(String name) {
        if (!distribution.exists()) {
            return null;
        }

        final File pluginsDir = distribution.getDistributionFile("searchplugins");
        if (pluginsDir == null) {
            return null;
        }

        final File[] files = (new File(pluginsDir, "common")).listFiles();
        if (files == null) {
            Log.e(LOG_TAG, "Could not find search plugin files in distribution directory");
            return null;
        }
        return createEngineFromFileList(files, name);
    }

    /**
     * Creates a SearchEngine instance for a search plugin shipped in the locale.
     *
     * This method reads the list of search plugin file names from list.txt, then
     * iterates through the files, creating SearchEngine instances until it finds one
     * with the right name. Unfortunately, we need to do this because there is no
     * other way to map the search engine "name" to the file for the search plugin.
     *
     * @param name Search engine name.
     * @return SearchEngine instance for name.
     */
    private SearchEngine createEngineFromLocale(String name) {
        final InputStream in = getInputStreamFromSearchPluginsJar("list.txt");
        final BufferedReader br = getBufferedReader(in);

        try {
            String identifier;
            while ((identifier = br.readLine()) != null) {
                final InputStream pluginIn = getInputStreamFromSearchPluginsJar(identifier + ".xml");
                final SearchEngine engine = createEngineFromInputStream(identifier, pluginIn);
                if (engine != null && engine.getName().equals(name)) {
                    return engine;
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error creating shipped search engine from name: " + name, e);
        } finally {
            try {
                br.close();
            } catch (IOException e) {
                // Ignore.
            }
        }
        return null;
    }

    /**
     * Creates a SearchEngine instance for a search plugin in the profile directory.
     *
     * This method iterates through the profile searchplugins directory, creating
     * SearchEngine instances until it finds one with the right name.
     *
     * @param name Search engine name.
     * @return SearchEngine instance for name.
     */
    private SearchEngine createEngineFromProfile(String name) {
        final File pluginsDir = GeckoProfile.get(context).getFile("searchplugins");
        if (pluginsDir == null) {
            return null;
        }

        final File[] files = pluginsDir.listFiles();
        if (files == null) {
            Log.e(LOG_TAG, "Could not find search plugin files in profile directory");
            return null;
        }
        return createEngineFromFileList(files, name);
    }

    /**
     * This method iterates through an array of search plugin files, creating
     * SearchEngine instances until it finds one with the right name.
     *
     * @param files Array of search plugin files. Should not be null.
     * @param name Search engine name.
     * @return SearchEngine instance for name.
     */
    private SearchEngine createEngineFromFileList(File[] files, String name) {
        for (int i = 0; i < files.length; i++) {
            try {
                final FileInputStream fis = new FileInputStream(files[i]);
                final SearchEngine engine = createEngineFromInputStream(null, fis);
                if (engine != null && engine.getName().equals(name)) {
                    return engine;
                }
            } catch (IOException e) {
                Log.e(LOG_TAG, "Error creating earch engine from name: " + name, e);
            }
        }
        return null;
    }

    /**
     * Creates a SearchEngine instance from an InputStream.
     *
     * This method closes the stream after it is done reading it.
     *
     * @param identifier Seach engine identifier. This only exists for search engines that
     *                   ship with the default set of engines in the locale.
     * @param in InputStream for search plugin XML file.
     * @return SearchEngine instance.
     */
    private SearchEngine createEngineFromInputStream(String identifier, InputStream in) {
        try {
            try {
                return new SearchEngine(identifier, in);
            } finally {
                in.close();
            }
        } catch (IOException | XmlPullParserException e) {
            Log.e(LOG_TAG, "Exception creating search engine", e);
        }

        return null;
    }

    /**
     * Reads a file from the searchplugins directory in the Gecko jar.
     *
     * @param fileName name of the file to read.
     * @return InputStream for file.
     */
    private InputStream getInputStreamFromSearchPluginsJar(String fileName) {
        final Locale locale = Locale.getDefault();

        // First, try a file path for the full locale.
        final String languageTag = Locales.getLanguageTag(locale);
        String url = getSearchPluginsJarURL(languageTag, fileName);

        InputStream in = GeckoJarReader.getStream(url);
        if (in != null) {
            return in;
        }

        // If that doesn't work, try a file path for just the language.
        final String language = Locales.getLanguage(locale);
        if (!languageTag.equals(language)) {
            url = getSearchPluginsJarURL(language, fileName);
            in = GeckoJarReader.getStream(url);
            if (in != null) {
                return in;
            }
        }

        // Finally, fall back to default locale defined in chrome registry.
        url = getSearchPluginsJarURL(getFallbackLocale(), fileName);
        return GeckoJarReader.getStream(url);
    }

    /**
     * Finds a fallback locale in the Gecko chrome registry. If a locale is declared
     * here, we should be guaranteed to find a searchplugins directory for it.
     *
     * This method should only be accessed from the background thread.
     */
    private String getFallbackLocale() {
        if (fallbackLocale != null) {
            return fallbackLocale;
        }

        final InputStream in = GeckoJarReader.getStream(getJarURL("!/chrome/chrome.manifest"));
        final BufferedReader br = getBufferedReader(in);

        try {
            String line;
            while ((line = br.readLine()) != null) {
                // We're looking for a line like "locale global en-US en-US/locale/en-US/global/"
                // https://developer.mozilla.org/en/docs/Chrome_Registration#locale
                if (line.startsWith("locale global ")) {
                    fallbackLocale = line.split(" ", 4)[2];
                    break;
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error reading fallback locale from chrome registry", e);
        } finally {
            try {
                br.close();
            } catch (IOException e) {
                // Ignore.
            }
        }
        return fallbackLocale;
    }

    /**
     * Gets the jar URL for a file in the searchplugins directory.
     *
     * @param locale String representing the Gecko locale (e.g. "en-US").
     * @param fileName The name of the file to read.
     * @return URL for jar file.
     */
    private String getSearchPluginsJarURL(String locale, String fileName) {
        final String path = "!/chrome/" + locale + "/locale/" + locale + "/browser/searchplugins/" + fileName;
        return getJarURL(path);
    }

    private String getJarURL(String path) {
        return "jar:jar:file://" + context.getPackageResourcePath() + "!/" + AppConstants.OMNIJAR_NAME + path;
    }

    private BufferedReader getBufferedReader(InputStream in) {
        try {
            return new BufferedReader(new InputStreamReader(in, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            // Cannot happen.
            return null;
        }
    }
}
