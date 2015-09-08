package cz.tmapy.android.trex.service;

import android.app.PendingIntent;
import android.app.Service;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import cz.tmapy.android.trex.Const;
import cz.tmapy.android.trex.MainScreen;
import cz.tmapy.android.trex.R;

/**
 * BackgroundLocationService used for tracking user location in the background.
 * https://gist.github.com/blackcj/20efe2ac885c7297a676
 */
public class BackgroundLocationService extends Service implements
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        LocationListener {

    private static final String TAG = BackgroundLocationService.class.getName();

    private PowerManager.WakeLock mWakeLock;

    SharedPreferences mSharedPref;

    IBinder mBinder = new LocalBinder();

    KalmanLatLong mKalman;

    private Location mLastAcceptedLocation; //last accepted location, filtered by Mr. Kalman

    private GoogleApiClient mGoogleApiClient;

    private int NOTIFICATION = 1975; //Unique number for this notification

    private String mTargetServerURL;
    private String mDeviceIdentifier;
    private String mServerResponse;
    private String mListPrefs;
    private Integer mFrequency = 20;
    private Integer mMinDistance = 60;
    private Integer mMaxInterval = 120;
    private Integer mKalmanMPS = 0;

    public class LocalBinder extends Binder {
        public BackgroundLocationService getServerInstance() {
            return BackgroundLocationService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mSharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        mTargetServerURL = mSharedPref.getString(Const.PREF_KEY_TARGET_SERVUER_URL, "");
        mDeviceIdentifier = mSharedPref.getString(Const.PREF_KEY_DEVICE_ID, "");
        mListPrefs = mSharedPref.getString(Const.PREF_STRATEGY, "PRIORITY_HIGH_ACCURACY");
        mFrequency = Integer.valueOf(mSharedPref.getString(Const.PREF_FREQUENCY, String.valueOf(mFrequency)));
        mMinDistance = Integer.valueOf(mSharedPref.getString(Const.PREF_MIN_DIST, String.valueOf(mMinDistance)));
        mMaxInterval = Integer.valueOf(mSharedPref.getString(Const.PREF_MAX_TIME, String.valueOf(mMaxInterval)));
        mKalmanMPS = Integer.valueOf(mSharedPref.getString(Const.PREF_KALMAN_MPS, String.valueOf(mKalmanMPS)));

        // Kalman filters generally work better when the accuracy decays a bit quicker than one might expect,
        // so for walking around with an Android phone I find that Q=3 metres per second works fine,
        // even though I generally walk slower than that.
        // But if travelling in a fast car a much larger number should obviously be used.
        mKalman = new KalmanLatLong(mKalmanMPS);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (!mGoogleApiClient.isConnected()) {
            mGoogleApiClient.connect();

            NotificationCompat.Builder mBuilder =
                    new NotificationCompat.Builder(this)
                            .setSmallIcon(R.drawable.trainers)
                            .setContentTitle(getResources().getString(R.string.notif_title))
                            .setContentText(getResources().getString(R.string.notif_text));

            // Creates an explicit intent for an Activity in your app
            Intent resultIntent = new Intent(this, MainScreen.class);
            //resultIntent.putExtra(Const.STATE_LOCALIZATION_IS_RUNNING, true);
            // The stack builder object will contain an artificial back stack for the started Activity.
            // This ensures that navigating backward from the Activity leads out of
            // your application to the Home screen.
            TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
            // Adds the back stack for the Intent (but not the Intent itself)
            stackBuilder.addParentStack(MainScreen.class);
            // Adds the Intent that starts the Activity to the top of the stack
            stackBuilder.addNextIntent(resultIntent);
            PendingIntent resultPendingIntent =
                    stackBuilder.getPendingIntent(0,
                            PendingIntent.FLAG_UPDATE_CURRENT
                    );
            mBuilder.setContentIntent(resultPendingIntent);

            startForeground(NOTIFICATION, mBuilder.build()); //spuštění služby s vyšší prioritou na popředí - http://developer.android.com/reference/android/app/Service.html

            mSharedPref.edit().putBoolean(Const.PREF_LOC_IS_RUNNING, true).commit(); //store state

            //Keep CPU on
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TRexWakelockTag");
            mWakeLock.acquire();

            if (Const.LOG_ENHANCED) Log.i(TAG, "Localization Started");
            Toast.makeText(this, R.string.localiz_started, Toast.LENGTH_SHORT).show();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {

        stopForeground(true); //http://developer.android.com/reference/android/app/Service.html

        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {

            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this);

            mGoogleApiClient.disconnect();
            // Destroy the current location client
            mGoogleApiClient = null;
        }
        //remove wakelock
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        mSharedPref.edit().putBoolean(Const.PREF_LOC_IS_RUNNING, false).commit(); //store state
        // Display the connection status
        Toast.makeText(this, R.string.localiz_stopped, Toast.LENGTH_SHORT).show();
        if (Const.LOG_ENHANCED) Log.i(TAG, "Localization Stopped");

        super.onDestroy();
    }

    /*
     * Called by Location Services when the request to connect the
     * client finishes successfully. At this point, you can
     * request the current location or start periodic updates
     */
    @Override
    public void onConnected(Bundle bundle) {
        Location loc = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
        if (loc != null) {
            //Kalman initiation
            mKalman.SetState(loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(), loc.getTime());
            processLocation(loc);
        }

        LocationRequest mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(mFrequency * 1000);
        mLocationRequest.setFastestInterval(1000);

        switch (mListPrefs) {
            case "PRIORITY_HIGH_ACCURACY":
                mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
                break;
            case "PRIORITY_LOW_POWER":
                mLocationRequest.setPriority(LocationRequest.PRIORITY_LOW_POWER);
                break;
            case "PRIORITY_NO_POWER":
                mLocationRequest.setPriority(LocationRequest.PRIORITY_NO_POWER);
                break;
            default:
                mLocationRequest.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
                break;
        }

        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, mLocationRequest, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Toast.makeText(this, "Location Services suspended: " + i, Toast.LENGTH_LONG).show();
        if (Const.LOG_BASIC) Log.e(TAG, "Location Services suspended: " + i);
    }

    /*
     * Called by Location Services if the attempt to
     * Location Services fails.
     */
    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Toast.makeText(this, "Location services failed", Toast.LENGTH_LONG).show();
        if (Const.LOG_BASIC)
            Log.e(TAG, "Connection to location services failed: " + connectionResult.getErrorCode());
    }

    /**
     * Called when the location has changed.
     * <p/>
     * <p> There are no restrictions on the use of the supplied Location object.
     *
     * @param location The new location, as a Location object.
     */
    @Override
    public void onLocationChanged(Location location) {
        processLocation(location);
    }

    /**
     * Process new location
     *
     * @param location
     */
    private void processLocation(Location location) {
        if (location != null) {
            try {
                //If Kalman MPS settings > 0 apply Kalman filter
                if (mKalmanMPS > 0)
                {
                    mKalman.Process(location.getLatitude(), location.getLongitude(), location.getAccuracy(), location.getTime());
                    //update position by Mr. Kalman
                    location.setLatitude(mKalman.get_lat());
                    location.setLongitude(mKalman.get_lng());
                }

                if (mLastAcceptedLocation == null) { //ths is first position
                    mLastAcceptedLocation = location; //location is accepted even if no connection
                    SendPosition(location);
                    return;
                }

                //calculate minutes diff between last and current location
                long diff = location.getTime() - mLastAcceptedLocation.getTime();
                long diffSeconds = diff / 1000 % 60;

                if ((diffSeconds >= mMaxInterval) || (mLastAcceptedLocation.distanceTo(location) >= mMinDistance)) {
                    mLastAcceptedLocation = location; //location is accepted even if no connection
                    SendPosition(location);
                }

            } catch (Exception e) {
                Toast.makeText(this, e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
                if (Const.LOG_BASIC) Log.e(TAG, "Error during location parsing:", e);
            }
        }
    }

    /**
     * Send location to the server
     *
     * @param location
     */
    private void SendPosition(Location location) {

        if (mTargetServerURL != null && !mTargetServerURL.isEmpty()) {
            if (isNetworkOnline()) {

                String lastUpdateTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(location.getTime());//2014-06-28T15:07:59
                String lat = Double.toString(location.getLatitude());
                String lon = Double.toString(location.getLongitude());
                String alt = Double.toString(location.getAltitude());
                String speed = Float.toString(location.getSpeed());
                String bearing = Float.toString(location.getBearing());

                new NetworkTask().execute(mTargetServerURL, mDeviceIdentifier, lastUpdateTime, lat, lon, alt, speed, bearing);

                if (Const.LOG_ENHANCED)
                    Log.i(TAG, "Position sent to server " + lat + ", " + lon);
            } else {
                Toast.makeText(this, "Cannot connect to server: '" + mTargetServerURL + "'", Toast.LENGTH_LONG).show();
                if (Const.LOG_BASIC)
                    Log.e(TAG, "Cannot connect to server: '" + mTargetServerURL + "'");
            }
        } else {
            Toast.makeText(this, "Missing target server URL", Toast.LENGTH_LONG).show();
            if (Const.LOG_BASIC) Log.e(TAG, "Missing target server URL");
        }
    }

    /**
     * Broadcasts the Intent to receivers in this app.
     */
    private void SendBroadcast() {
        Intent localIntent = new Intent(Const.LOCATION_BROADCAST);
        //localIntent.putExtra(Const.STATE_LOCALIZATION_IS_RUNNING, true);
        localIntent.putExtra(Const.EXTRAS_POSITION_DATA, mLastAcceptedLocation);
        localIntent.putExtra(Const.EXTRAS_SERVER_RESPONSE, mServerResponse);

        LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent);
    }

    /**
     * Checks network connectivity
     *
     * @return
     */
    public boolean isNetworkOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    /**
     * Private class for sending data
     */
    private class NetworkTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            String response = "";
            try {
                URL url = new URL(params[0]);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setReadTimeout((mFrequency - 1) * 1000);
                conn.setConnectTimeout((mFrequency - 1) * 1000);
                conn.setRequestMethod("POST");
                conn.setDoInput(true);
                conn.setDoOutput(true);

                HashMap<String, String> postDataParams = new HashMap<>();
                postDataParams.put("i", params[1]);
                postDataParams.put("t", params[2]);
                postDataParams.put("a", params[3]);
                postDataParams.put("o", params[4]);
                postDataParams.put("l", params[5]);
                postDataParams.put("s", params[6]);
                postDataParams.put("b", params[7]);

                OutputStream os = conn.getOutputStream();
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
                writer.write(getPostDataString(postDataParams));
                writer.flush();
                writer.close();
                os.close();

                int responseCode = conn.getResponseCode();

                if (responseCode == HttpsURLConnection.HTTP_OK) {
                    String line;
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    while ((line = br.readLine()) != null) {
                        response += line;
                    }
                } else {
                    response = "HTTP response: " + responseCode;
                }
            } catch (Exception e) {
                if (Const.LOG_BASIC) Log.e(TAG, "Network connection:", e);
                response = e.getLocalizedMessage();
            }
            return response;
        }

        private String getPostDataString(HashMap<String, String> params) throws UnsupportedEncodingException {
            StringBuilder result = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (first)
                    first = false;
                else
                    result.append("&");

                result.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
                result.append("=");
                result.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            return result.toString();
        }

        /**
         * Response processing
         *
         * @param result
         */
        @Override
        protected void onPostExecute(String result) {
            mServerResponse = result;
            SendBroadcast();
        }
    }
}