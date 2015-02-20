package com.tenforwardconsulting.cordova.bgloc;

import java.util.List;
import java.util.Iterator;
import java.util.Random;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;

import com.tenforwardconsulting.cordova.bgloc.data.DAOFactory;
import com.tenforwardconsulting.cordova.bgloc.data.LocationDAO;

import android.annotation.TargetApi;

import android.media.AudioManager;
import android.media.ToneGenerator;

import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import static android.telephony.PhoneStateListener.*;
import android.telephony.CellLocation;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.Activity;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;

import android.location.Location;
import android.location.Criteria;
import android.location.LocationListener;
import android.location.LocationManager;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.FusedLocationProviderApi;

import static java.lang.Math.*;

public class LocationUpdateService extends Service implements GoogleApiClient.ConnectionCallbacks {

    private static final String TAG = "LocationUpdateService";
    private static final String LOCATION_UPDATE = "com.tenforwardconsulting.cordova.bgloc.LOCATION_UPDATE";
    private static final String STOP_RECORDING  = "com.tenforwardconsulting.cordova.bgloc.STOP_RECORDING";
    private static final String START_RECORDING = "com.tenforwardconsulting.cordova.bgloc.START_RECORDING";

    private Location lastLocation;
    private long lastUpdateTime = 0l;

    private JSONObject params;
    private JSONObject headers;
    private String url = "http://192.168.2.15:3000/users/current_location.json";

    private PendingIntent locationUpdatePI;
    private GoogleApiClient locationClientAPI;

    private Integer desiredAccuracy = 100;
    private Integer distanceFilter  = 30;
    private Integer locationTimeout = 30;
    private Integer scaledDistanceFilter;

    private static final Integer SECONDS_PER_MINUTE      = 60;
    private static final Integer MILLISECONDS_PER_SECOND = 60;

    private long  interval             = (long)  SECONDS_PER_MINUTE * MILLISECONDS_PER_SECOND * 5;
    private long  fastestInterval      = (long)  SECONDS_PER_MINUTE * MILLISECONDS_PER_SECOND;
    private float stationaryRadius     = (float) 0;

    private Boolean isDebugging;
    private String notificationTitle = "Background checking";
    private String notificationText = "ENABLED";
    private Boolean stopOnTerminate;

    private ToneGenerator toneGenerator;

    private Criteria criteria;

    private ConnectivityManager connectivityManager;
    private NotificationManager notificationManager;

    @Override
    public IBinder onBind(Intent intent) {
        // TODO Auto-generated method stub
        Log.i(TAG, "OnBind" + intent);
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "OnCreate");
        Log.d(TAG, "RUNNING JOSHUA'S MOD!!!!!!!!!!!!!!!");

        toneGenerator           = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
        notificationManager     = (NotificationManager)this.getSystemService(Context.NOTIFICATION_SERVICE);
        connectivityManager     = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        // Location Update PI
        Intent locationUpdateIntent = new Intent(LOCATION_UPDATE);
        locationUpdatePI = PendingIntent.getBroadcast(this, 9001, locationUpdateIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        registerReceiver(locationUpdateReceiver, new IntentFilter(LOCATION_UPDATE));

        // Receivers for start/stop recording
        registerReceiver(startRecordingReceiver, new IntentFilter(START_RECORDING));
        registerReceiver(stopRecordingReceiver, new IntentFilter(STOP_RECORDING));

        // Location criteria

        criteria = new Criteria();
        criteria.setAltitudeRequired(false);
        criteria.setBearingRequired(false);
        criteria.setSpeedRequired(true);
        criteria.setCostAllowed(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);
        if (intent != null) {
            try {
                params = new JSONObject(intent.getStringExtra("params"));
                headers = new JSONObject(intent.getStringExtra("headers"));
            } catch (JSONException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            url = intent.getStringExtra("url");
            stationaryRadius = Float.parseFloat(intent.getStringExtra("stationaryRadius"));
            distanceFilter = Integer.parseInt(intent.getStringExtra("distanceFilter"));
            scaledDistanceFilter = distanceFilter;
            desiredAccuracy = Integer.parseInt(intent.getStringExtra("desiredAccuracy"));
            locationTimeout = Integer.parseInt(intent.getStringExtra("locationTimeout"));

            interval             = Integer.parseInt(intent.getStringExtra("interval"));
            fastestInterval      = Integer.parseInt(intent.getStringExtra("fastestInterval"));

            isDebugging = Boolean.parseBoolean(intent.getStringExtra("isDebugging"));
            notificationTitle = intent.getStringExtra("notificationTitle");
            notificationText = intent.getStringExtra("notificationText");

            // Build a Notification required for running service in foreground.
            Intent main = new Intent(this, BackgroundGpsPlugin.class);
            main.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, main,  PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder builder = new Notification.Builder(this);
            builder.setContentTitle(notificationTitle);
            builder.setContentText(notificationText);
            builder.setSmallIcon(android.R.drawable.ic_menu_mylocation);

            // Make clicking the event link back to the main cordova activity
            //builder.setContentIntent(pendingIntent);
            setClickEvent(builder);

            Notification notification;
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                notification = buildForegroundNotification(builder);
            } else {
                notification = buildForegroundNotificationCompat(builder);
            }

            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_NO_CLEAR;
            startForeground(startId, notification);
        }
        Log.i(TAG, "- url: " + url);
        Log.i(TAG, "- params: "  + params.toString());
        Log.i(TAG, "- headers: " + headers.toString());
        Log.i(TAG, "- interval: "             + interval);
        Log.i(TAG, "- fastestInterval: "      + fastestInterval);

        Log.i(TAG, "- stationaryRadius: "   + stationaryRadius);
//        Log.i(TAG, "- distanceFilter: "     + distanceFilter);
        Log.i(TAG, "- desiredAccuracy: "    + desiredAccuracy);
//        Log.i(TAG, "- locationTimeout: "    + locationTimeout);
        Log.i(TAG, "- isDebugging: "        + isDebugging);
        Log.i(TAG, "- notificationTitle: "  + notificationTitle);
        Log.i(TAG, "- notificationText: "   + notificationText);

        // Todo: Probably not necessary
        // this.stopRecording();

        //We want this service to continue running until it is explicitly stopped
        return START_REDELIVER_INTENT;
    }

    /**
     * Adds an onclick handler to the notification
     */
    private Notification.Builder setClickEvent (Notification.Builder notification) {
        Context context     = getApplicationContext();
        String packageName  = context.getPackageName();
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int requestCode = new Random().nextInt();

        PendingIntent contentIntent = PendingIntent.getActivity(context, requestCode, launchIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        return notification.setContentIntent(contentIntent);
    }

    /**
     * Broadcast receiver for receiving a single-update from LocationManager.
     */
    private BroadcastReceiver locationUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "- locationUpdateReceiver TRIGGERED!!!!!!!!!!");
            String key = FusedLocationProviderApi.KEY_LOCATION_CHANGED;
            Location location = (Location)intent.getExtras().get(key);

            if (location != null) {
                Log.d(TAG, "- locationUpdateReceiver" + location.toString());

                // Go ahead and cache, push to server
                lastLocation = location;

                postLocation(com.tenforwardconsulting.cordova.bgloc.data.Location.fromAndroidLocation(location));
            }
        }
    };

    private BroadcastReceiver startRecordingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "- START_RECORDING RECEIVER");
            startRecording();
        }
    };

    private BroadcastReceiver stopRecordingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "- STOP_RECORDING RECEIVER");
            stopRecording();
        }
    };

    private boolean running = false;
    private boolean enabled = false;
    private boolean startRecordingOnConnect = true;

    private void enable() {
        this.enabled = true;
    }

    private void disable() {
        this.enabled = false;
    }

    public void startRecording() {
        Log.d(TAG, "- locationUpdateReceiver STARTING RECORDING!!!!!!!!!!");
        this.startRecordingOnConnect = true;
        attachRecorder();
    }

    public void stopRecording() {
        Log.d(TAG, "- locationUpdateReceiver STOPPING RECORDING!!!!!!!!!!");
        this.startRecordingOnConnect = false;
        detachRecorder();
    }

    private void connectToPlayAPI() {
        Log.d(TAG, "- CONNECTING TO GOOGLE PLAY SERVICES API!!!!!!!!!!");
        locationClientAPI =  new GoogleApiClient.Builder(this)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(this)
                //.addOnConnectionFailedListener(this)
                .build();
        locationClientAPI.connect();
    }

    private void attachRecorder() {
        if (locationClientAPI == null) {
            connectToPlayAPI();
        } else if (locationClientAPI.isConnected()) {
            LocationRequest locationRequest = LocationRequest.create()
                    .setPriority(translateDesiredAccuracy(desiredAccuracy)) // this.accuracy
                    .setFastestInterval(fastestInterval)
                    .setInterval(interval)
                    .setSmallestDisplacement(stationaryRadius);
            LocationServices.FusedLocationApi.requestLocationUpdates(locationClientAPI, locationRequest, locationUpdatePI);
            this.running = true;
            Log.d(TAG, "- locationUpdateReceiver NOW RECORDING!!!!!!!!!!");
        } else {
            locationClientAPI.connect();
        }
    }

    private void detachRecorder() {
        if (locationClientAPI == null) {
            connectToPlayAPI();
        } else if (locationClientAPI.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(locationClientAPI, locationUpdatePI);
            this.running = false;
            Log.d(TAG, "- locationUpdateReceiver NO LONGER RECORDING!!!!!!!!!!");
        } else {
            locationClientAPI.connect();
        }
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        Log.d(TAG, "- CONNECTED TO GOOGLE PLAY SERVICES API!!!!!!!!!!");
        if (this.startRecordingOnConnect) {
            attachRecorder();
        } else {
            detachRecorder();
        }
    }

    @Override
    public void onConnectionSuspended(int cause) {
        // locationClientAPI.connect();
    }

    @TargetApi(16)
    private Notification buildForegroundNotification(Notification.Builder builder) {
        return builder.build();
    }

    @SuppressWarnings("deprecation")
    @TargetApi(15)
    private Notification buildForegroundNotificationCompat(Notification.Builder builder) {
        return builder.getNotification();
    }

    /**
    * Translates a number representing desired accuracy of GeoLocation system from set [0, 10, 100, 1000].
    * 0:  most aggressive, most accurate, worst battery drain
    * 1000:  least aggressive, least accurate, best for battery.
    */
    private Integer translateDesiredAccuracy(Integer accuracy) {
        switch (accuracy) {
            case 10000:
                accuracy = LocationRequest.PRIORITY_NO_POWER;
                break;
            case 1000:
                accuracy = LocationRequest.PRIORITY_LOW_POWER;
                break;
            case 100:
                accuracy = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
                break;
            case 10:
                accuracy = LocationRequest.PRIORITY_HIGH_ACCURACY;
                break;
            case 0:
                accuracy = LocationRequest.PRIORITY_HIGH_ACCURACY;
                break;
            default:
                accuracy = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
        }
        return accuracy;
    }

    /**
     * Plays debug sound
     * @param name
     */
    private void startTone(String name) {
        int tone = 0;
        int duration = 1000;

        if (name.equals("beep")) {
            tone = ToneGenerator.TONE_PROP_BEEP;
        } else if (name.equals("beep_beep_beep")) {
            tone = ToneGenerator.TONE_CDMA_CONFIRM;
        } else if (name.equals("long_beep")) {
            tone = ToneGenerator.TONE_CDMA_ABBR_ALERT;
        } else if (name.equals("doodly_doo")) {
            tone = ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE;
        } else if (name.equals("chirp_chirp_chirp")) {
            tone = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD;
        } else if (name.equals("dialtone")) {
            tone = ToneGenerator.TONE_SUP_RINGTONE;
        }
        toneGenerator.startTone(tone, duration);
    }

    private void postLocation(com.tenforwardconsulting.cordova.bgloc.data.Location location) {

        PostLocationTask task = new LocationUpdateService.PostLocationTask();
        Log.d(TAG, "beforeexecute " +  task.getStatus());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, location);
        else
            task.execute(location);
        Log.d(TAG, "afterexecute " + task.getStatus());

    }

    private class PostLocationTask extends AsyncTask<Object, Integer, Boolean> {

        @Override
        protected Boolean doInBackground(Object... objects) {
            Log.d(TAG, "Executing PostLocationTask#doInBackground");
            LocationDAO locationDAO = DAOFactory.createLocationDAO(LocationUpdateService.this.getApplicationContext());
            return postLocationSync((com.tenforwardconsulting.cordova.bgloc.data.Location) objects[0], locationDAO);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            Log.d(TAG, "PostLocationTask#onPostExecture");
        }
    }

    private boolean postLocationSync(com.tenforwardconsulting.cordova.bgloc.data.Location l, LocationDAO dao) {
        if (l == null) {
            Log.w(TAG, "postLocation: null location");
            return false;
        }
        try {
            lastUpdateTime = SystemClock.elapsedRealtime();
            Log.i(TAG, "Posting  native location update: " + l);
            DefaultHttpClient httpClient = new DefaultHttpClient();
            HttpPost request = new HttpPost(url);

            JSONObject location = new JSONObject();
            location.put("latitude", l.getLatitude());
            location.put("longitude", l.getLongitude());
            location.put("accuracy", l.getAccuracy());
            location.put("speed", l.getSpeed());
            location.put("bearing", l.getBearing());
            location.put("altitude", l.getAltitude());
            location.put("recorded_at", dao.dateToString(l.getRecordedAt()));
            params.put("location", location);

            Log.i(TAG, "location: " + location.toString());

            StringEntity se = new StringEntity(params.toString());
            request.setEntity(se);
            request.setHeader("Accept", "application/json");
            request.setHeader("Content-type", "application/json");

            Iterator<String> headkeys = headers.keys();
            while( headkeys.hasNext() ){
        String headkey = headkeys.next();
        if(headkey != null) {
                    Log.d(TAG, "Adding Header: " + headkey + " : " + (String)headers.getString(headkey));
                    request.setHeader(headkey, (String)headers.getString(headkey));
        }
            }
            Log.d(TAG, "Posting to " + request.getURI().toString());
            HttpResponse response = httpClient.execute(request);
            Log.i(TAG, "Response received: " + response.getStatusLine());
            if (response.getStatusLine().getStatusCode() == 200) {
                return true;
            } else {
                return false;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Exception posting location: " + e);
            e.printStackTrace();
            return false;
        }
    }

    private boolean isNetworkConnected() {
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        if (networkInfo != null) {
            Log.d(TAG, "Network found, type = " + networkInfo.getTypeName());
            return networkInfo.isConnected();
        } else {
            Log.d(TAG, "No active network info");
            return false;
        }
    }

    @Override
    public boolean stopService(Intent intent) {
        Log.i(TAG, "- Received stop: " + intent);
        this.stopRecording();
        this.cleanUp();
        if (isDebugging) {
            Toast.makeText(this, "Background location tracking stopped", Toast.LENGTH_SHORT).show();
        }
        return super.stopService(intent);
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "------------------------------------------ Destroyed Location update Service");
        this.cleanUp();
        super.onDestroy();
    }

    private void cleanUp() {
        // this.disable();
        toneGenerator.release();
        unregisterReceiver(locationUpdateReceiver);
        unregisterReceiver(startRecordingReceiver);
        unregisterReceiver(stopRecordingReceiver);
        locationClientAPI.disconnect();
        stopForeground(true);

        // For some reason, it saves old values and starts sending them before being sent to the background on the next
        // restart. This should clear out the old values... >_>
        LocationDAO locationDAO = DAOFactory.createLocationDAO(LocationUpdateService.this.getApplicationContext());
        locationDAO.deleteAllLocations();
    }

    //@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        this.stopRecording();
        this.stopSelf();
        super.onTaskRemoved(rootIntent);
    }

}
