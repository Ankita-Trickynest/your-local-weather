package org.thosp.yourlocalweather.service;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.thosp.yourlocalweather.ConnectionDetector;
import org.thosp.yourlocalweather.R;
import org.thosp.yourlocalweather.model.CurrentWeatherDbHelper;
import org.thosp.yourlocalweather.model.LocationsDbHelper;
import org.thosp.yourlocalweather.model.WeatherForecastDbHelper;
import org.thosp.yourlocalweather.utils.AppPreference;
import org.thosp.yourlocalweather.utils.Constants;
import org.thosp.yourlocalweather.utils.NotificationUtils;
import org.thosp.yourlocalweather.utils.PermissionUtil;
import org.thosp.yourlocalweather.utils.PreferenceUtil;
import org.thosp.yourlocalweather.utils.Utils;

import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

import static org.thosp.yourlocalweather.utils.LogToFile.appendLog;

public class LocationUpdateService extends AbstractCommonService implements ProcessResultFromAddressResolution, LocationListener {

    private static final String TAG = "LocationUpdateService";

    private static final long LOCATION_TIMEOUT_IN_MS = 30000L;
    private static final long NETWORK_AVAILABILITY_TIMEOUT_IN_MS = 30000L;
    private static final long GPS_LOCATION_TIMEOUT_IN_MS = 60000L;
    private static final long GPS_MAX_LOCATION_AGE_IN_MS = 350000L; //5min
    private static final long LOCATION_UPDATE_RESEND_INTERVAL_IN_MS = 10000L; //20s

    public enum LocationUpdateServiceActions {
        START_LOCATION_AND_WEATHER_UPDATE, START_LOCATION_ONLY_UPDATE, LOCATION_UPDATE
    }

    private final IBinder binder = new LocationUpdateServiceBinder();
    private static Queue<NetworkLocationProviderActionData> networkLocationProviderActions = new LinkedList<>();
    NetworkLocationProvider networkLocationProvider;

    private LocationManager locationManager;

    private String updateSource;
    private boolean forceUpdate;
    private volatile long lastLocationUpdateTime;
    public static volatile boolean updateLocationInProcess;

    @Override
    public void onCreate() {
        super.onCreate();
        Intent intent = new Intent(getApplicationContext(), NetworkLocationProvider.class);
        getApplicationContext().bindService(intent, networkLocationProviderConnection, Context.BIND_AUTO_CREATE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (networkLocationProvider != null) {
            getApplicationContext().unbindService(networkLocationProviderConnection);
        }
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, final int startId) {
        int ret = super.onStartCommand(intent, flags, startId);
        if (intent == null) {
            return ret;
        }
        forceUpdate = false;
        updateSource = null;
        appendLog(getBaseContext(), TAG, "onStartCommand:intent.getAction():", intent.getAction());
        switch (intent.getAction()) {
            case "android.intent.action.START_LOCATION_AND_WEATHER_UPDATE": startLocationAndWeatherUpdate(intent); return ret;
            case "android.intent.action.START_LOCATION_ONLY_UPDATE": updateNetworkLocation(intent); return ret;
            default: return ret;
        }
    }

    public void onLocationChangedCanceled() {
        updateLocationInProcess = false;
        stopRefreshRotation("onLocationChangedCanceled",3);
    }

    @Override
    public void processAddresses(Location location, List<Address> addresses) {
        appendLog(getBaseContext(), TAG, "processUpdateOfLocation:addresses:", addresses);
        Address resolvedAddress = null;
        if ((addresses != null) && (addresses.size() > 0)) {
            resolvedAddress = addresses.get(0);
        }
        appendLog(getBaseContext(), TAG, "processUpdateOfLocation:location:", location, ", address=", resolvedAddress);
        onLocationChanged(location, resolvedAddress);
    }

    @Override
    public void processCanceledRequest() {
        onLocationChangedCanceled();
    }

    @Override
    public void onLocationChanged(Location location) {
        String locale = PreferenceUtil.getLanguage(getBaseContext());
        NominatimLocationService.getInstance().getFromLocation(
                getBaseContext(),
                location.getLatitude(),
                location.getLongitude(),
                1,
                locale,
                this,
                location);
    }
    
    public void onLocationChanged(Location location, Address address) {
        appendLog(getBaseContext(), TAG, "onLocationChanged");
        sendMessageToWakeUpService(
                AppWakeUpManager.FALL_DOWN,
                AppWakeUpManager.SOURCE_LOCATION_UPDATE
        );
        lastLocationUpdateTime = System.currentTimeMillis();
        timerHandler.removeCallbacksAndMessages(null);
        removeUpdates(this);

        /*org.thosp.yourlocalweather.model.Location autoLocation = locationsDbHelper.getLocationByOrderId(0);
        float storedLocationAccuracy = autoLocation.getAccuracy();
        long storedLocationTime = autoLocation.getLastLocationUpdate();

        Calendar now = Calendar.getInstance();
        now.add(Calendar.MILLISECOND, -300000);

        // remove acccuracy checking to get fast responses
        if ((storedLocationTime > now.getTimeInMillis()) && (location != null) && (location.getAccuracy() > storedLocationAccuracy)) {
            appendLog(getBaseContext(), TAG, "stored location is recent and more accurate, stored location accuracy = " +
                    storedLocationAccuracy + ", location accuracy =" + ((location != null)?location.getAccuracy():"") +
                    ", stored location time = " + storedLocationTime + ", location time" + ((location != null)?location.getTime():""));
            locationDbHelper.updateLocationSource(currentLocation.getId(), locationSource);
            requestWeatherCheck();
            return;
        }*/

        if ((location == null) && gpsRequestLocation()) {
            updateLocationInProcess = false;
            stopRefreshRotation("onLocationChanged",3);
            return;
        }

        org.thosp.yourlocalweather.model.Location currentLocation;
        if (location == null) {
            LocationsDbHelper locationsDbHelper = LocationsDbHelper.getInstance(getBaseContext());
            currentLocation = locationsDbHelper.getLocationByOrderId(0);
            locationsDbHelper.updateLocationSource(currentLocation.getId(), getString(R.string.location_weather_update_status_location_not_reachable));
        } else {
            currentLocation = processUpdateOfLocation(location, address);
        }
        appendLog(getBaseContext(), TAG, "send intent to get weather, updateSource ", updateSource);
        updateLocationInProcess = false;
        stopRefreshRotation("onLocationChanged",3);
        sendMessageToCurrentWeatherService(currentLocation, updateSource, AppWakeUpManager.SOURCE_CURRENT_WEATHER, forceUpdate, false);
        sendMessageToWeatherForecastService(currentLocation.getId(), updateSource, forceUpdate);
        stopForeground(true);
    }

    private org.thosp.yourlocalweather.model.Location processUpdateOfLocation(Location location, Address address) {
        LocationsDbHelper locationsDbHelper = LocationsDbHelper.getInstance(getBaseContext());

        String updateDetailLevel = PreferenceManager.getDefaultSharedPreferences(getBaseContext()).getString(
                Constants.KEY_PREF_UPDATE_DETAIL, "preference_display_update_nothing");

        org.thosp.yourlocalweather.model.Location currentLocation = locationsDbHelper.getLocationByOrderId(0);

        String currentLocationSource = currentLocation.getLocationSource();
        if ("gps".equals(location.getProvider())) {
            if ((currentLocationSource == null) || !currentLocationSource.contains(getString(R.string.location_weather_update_status_location_from_gps))) {
                currentLocationSource = getString(R.string.location_weather_update_status_location_from_gps);
            }
        } else if (updateDetailLevel.equals("preference_display_update_location_source")) {
            StringBuilder networkSourceBuilder = new StringBuilder();
            networkSourceBuilder.append(getString(R.string.location_weather_update_status_location_from_network));
            boolean additionalSourceSetted = false;

            if ((location.getExtras() != null) && (location.getExtras().containsKey("source"))) {
                String networkSource = location.getExtras().getString("source");
                if (networkSource != null) {
                    if (networkSource.contains("cells")) {
                        networkSourceBuilder.append(getString(R.string.location_weather_update_status_location_from_network_cells));
                        additionalSourceSetted = true;
                    }
                    if (networkSource.contains("wifis")) {
                        networkSourceBuilder.append(getString(R.string.location_weather_update_status_location_from_network_wifis));
                        additionalSourceSetted = true;
                    }
                }
            }
            if (!additionalSourceSetted) {
                networkSourceBuilder.append(location.getProvider().substring(0, 1));
            }
            currentLocationSource = networkSourceBuilder.toString();
            appendLog(getBaseContext(), TAG, "send update source to ", currentLocationSource);
        } else if (getString(R.string.location_weather_update_status_update_started).equals(currentLocationSource)) {
            currentLocationSource = getString(R.string.location_weather_update_status_location_from_network);
        }
        currentLocation = locationsDbHelper.getLocationById(currentLocation.getId());
        checkDistanceAndRemoveForecastIfTheNewLocationIsFarAway(location, currentLocation);
        locationsDbHelper.updateAutoLocationGeoLocation(location.getLatitude(), location.getLongitude(), currentLocationSource, location.getAccuracy(), getLocationTimeInMilis(location));
        appendLog(getBaseContext(), TAG, "put new location from location update service, latitude=", location.getLatitude(), ", longitude=", location.getLongitude());
        if (address != null) {
            locationsDbHelper.updateAutoLocationAddress(getBaseContext(), PreferenceUtil.getLanguage(getBaseContext()), address);
        } else {
            String geocoder = AppPreference.getLocationGeocoderSource(this);
            boolean resolveAddressByOS = !"location_geocoder_local".equals(geocoder);
            Utils.getAndWriteAddressFromGeocoder(new Geocoder(this, new Locale(PreferenceUtil.getLanguage(this))),
                    address,
                    location.getLatitude(),
                    location.getLongitude(),
                    resolveAddressByOS,
                    this);
        }
        return currentLocation;
    }

    private void checkDistanceAndRemoveForecastIfTheNewLocationIsFarAway(Location location, org.thosp.yourlocalweather.model.Location currentLocation) {
        double distanceBetweenLocationsInKm = distance(location.getLatitude(), location.getLongitude(), currentLocation.getLatitude(), currentLocation.getLongitude());
        appendLog(getBaseContext(), TAG, "Distance between old and new location (in Km)=", distanceBetweenLocationsInKm);
        if (distanceBetweenLocationsInKm > 10) {
            appendLog(getBaseContext(), TAG, "Distance between old and new location is more than 10 Km, removing current weather and forecast");
            WeatherForecastDbHelper weatherForecastDbHelper = WeatherForecastDbHelper.getInstance(getBaseContext());
            weatherForecastDbHelper.deleteRecordByLocation(currentLocation);
            CurrentWeatherDbHelper currentWeatherDbHelper = CurrentWeatherDbHelper.getInstance(getBaseContext());
            currentWeatherDbHelper.deleteRecordByLocation(currentLocation);
        }
    }

    private double distance(double lat1, double lon1, double lat2, double lon2) {
        if ((lat1 == lat2) && (lon1 == lon2)) {
            return 0;
        } else {
            double theta = lon1 - lon2;
            double dist = Math.sin(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(Math.toRadians(theta));
            dist = Math.acos(dist);
            dist = Math.toDegrees(dist);
            return dist * 60 * 1.853159616;
        }
    }

    Handler lastKnownLocationTimerHandler = new Handler();
    Runnable lastKnownLocationTimerRunnable = new Runnable() {

        @Override
        public void run() {
            appendLog(getBaseContext(), TAG, "send update source to N - update location by network, lastKnownLocation timeouted");
            updateNetworkLocationByNetwork(null, false, null, 0);
        }
    };

    Handler timerHandler = new Handler();
    Runnable timerRunnable = new Runnable() {

        @Override
        public void run() {
            appendLog(getBaseContext(), TAG, "timerRunnable:requestWeatherCheck");
            LocationsDbHelper locationsDbHelper = LocationsDbHelper.getInstance(getBaseContext());
            org.thosp.yourlocalweather.model.Location currentLocation = locationsDbHelper.getLocationByOrderId(0);
            requestWeatherCheck(currentLocation.getId(), updateSource, AppWakeUpManager.SOURCE_CURRENT_WEATHER, forceUpdate);
        }
    };

    Handler timerNetworkAvailabilityHandler = new Handler();
    Runnable timerNetworkAvailabilityRunnable = new Runnable() {

        @Override
        public void run() {
            appendLog(getBaseContext(), TAG, "timerNetworkAvailabilityRunnable:run");
            ConnectionDetector connectionDetector = new ConnectionDetector(getApplicationContext());
            LocationsDbHelper locationsDbHelper = LocationsDbHelper.getInstance(getApplicationContext());
            org.thosp.yourlocalweather.model.Location currentLocationForSensorEvent = locationsDbHelper.getLocationByOrderId(0);
            if (!connectionDetector.isNetworkAvailableAndConnected()) {
                locationsDbHelper.updateLocationSource(
                        currentLocationForSensorEvent.getId(),
                        getString(R.string.location_weather_update_status_location_not_reachable));
                stopRefreshRotation("updateNetworkLocation", 3);
                sendMessageToWakeUpService(
                        AppWakeUpManager.FALL_DOWN,
                        AppWakeUpManager.SOURCE_LOCATION_UPDATE
                );
                sendIntent("android.intent.action.CLEAR_SENSOR_VALUES");
                updateLocationInProcess = false;
            } else {
                updateNetworkLocation(false, null, 0);
            }
        }
    };

    Handler timerHandlerGpsLocation = new Handler();
    Runnable timerRunnableGpsLocation = new Runnable() {

        @Override
        public void run() {
            locationManager.removeUpdates(gpsLocationListener);
            appendLog(getBaseContext(), TAG, "Timeout getting location from GPS");
            setNoLocationFound();
            updateLocationInProcess = false;
            stopRefreshRotation("timerRunnableGpsLocation", 3);
        }
    };

    final LocationListener gpsLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            locationManager.removeUpdates(gpsLocationListener);
            timerHandlerGpsLocation.removeCallbacksAndMessages(null);
            appendLog(getBaseContext(), TAG, "start START_LOCATION_UPDATE:locationsource is N or G");
            startLocationUpdate(location);
            appendLog(getBaseContext(), TAG, "start START_LOCATION_UPDATE:locationSource G");
            LocationsDbHelper locationsDbHelper = LocationsDbHelper.getInstance(getBaseContext());
            org.thosp.yourlocalweather.model.Location currentLocation = locationsDbHelper.getLocationByOrderId(0);
            locationsDbHelper.updateLocationSource(currentLocation.getId(), getString(R.string.location_weather_update_status_location_from_gps));
            timerHandler.postDelayed(timerRunnable, LOCATION_TIMEOUT_IN_MS);
        }

        @Override
        public void onStatusChanged(String s, int i, Bundle bundle) {}

        @Override
        public void onProviderEnabled(String s) {}

        @Override
        public void onProviderDisabled(String s) {
            locationManager.removeUpdates(gpsLocationListener);
        }
    };
    
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        removeUpdates(this);
    }

    private void startLocationUpdateOnly(Intent intent) {
        if (intent.getExtras() == null) {
            return;
        }
        Location inputLocation = null;
        if (intent.getExtras().getParcelable("inputLocation") != null) {
            inputLocation = (Location) intent.getExtras().getParcelable("inputLocation");
        }
        Address addresses = null;
        if (intent.getExtras().getParcelable("addresses") != null) {
            addresses = (Address) intent.getExtras().getParcelable("addresses");
        }
        appendLog(getBaseContext(), TAG, "LOCATION_UPDATE recieved:", inputLocation, ":", addresses);
        onLocationChanged(inputLocation, addresses);
    }

    private void startLocationAndWeatherUpdate(Intent intent) {
        appendLog(getBaseContext(), TAG, "startLocationAndWeatherUpdate:", intent);
        if (intent.getExtras() == null) {
            return;
        }
        this.updateSource = intent.getStringExtra("updateSource");
        this.forceUpdate = intent.getBooleanExtra("forceUpdate", false);

        if ("MAIN".equals(updateSource)) {
            startForeground(1, NotificationUtils.getNotificationForActivity(getBaseContext()));
        }
        processLocationAndWeatherUpdate(intent);
    }

    public void startLocationAndWeatherUpdate(String updateSource) {
        this.updateSource = updateSource;
        processLocationAndWeatherUpdate(null);
    }

    public void startLocationAndWeatherUpdate(boolean forceUpdate) {
        this.forceUpdate = forceUpdate;
        processLocationAndWeatherUpdate(null);
    }

    public void processLocationAndWeatherUpdate(Intent intent) {
        boolean isGPSEnabled = AppPreference.isGpsEnabledByPreferences(this) &&
                locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER)
                && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean isNetworkEnabled = locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)
                && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        appendLog(getBaseContext(), TAG, "startLocationAndWeatherUpdate:isGPSEnabled=",
                                        isGPSEnabled, ", isNetworkEnabled=", isNetworkEnabled);

        LocationsDbHelper locationsDbHelper = LocationsDbHelper.getInstance(getBaseContext());
        org.thosp.yourlocalweather.model.Location currentLocation = locationsDbHelper.getLocationByOrderId(0);
        locationsDbHelper.updateLocationSource(currentLocation.getId(), getString(R.string.location_weather_update_status_update_started));

        boolean isUpdateOfLocationEnabled = AppPreference.isUpdateLocationEnabled(this, currentLocation);
        appendLog(this, TAG,
                "START_LOCATION_AND_WEATHER_UPDATE, isUpdateOfLocationEnabled=",
                isUpdateOfLocationEnabled,
                ", isGPSEnabled=",
                isGPSEnabled,
                ", isNetworkEnabled=",
                isNetworkEnabled);
        String geocoder = AppPreference.getLocationGeocoderSource(this);
        if (isUpdateOfLocationEnabled && (isGPSEnabled || isNetworkEnabled || !"location_geocoder_system".equals(geocoder))) {
            appendLog(getBaseContext(), TAG, "Widget calls to update location, geocoder = ", geocoder);
            sendMessageToWakeUpService(
                    AppWakeUpManager.WAKE_UP,
                    AppWakeUpManager.SOURCE_LOCATION_UPDATE
            );
            if ("location_geocoder_local".equals(geocoder)) {
                updateNetworkLocation(false, intent, 0, forceUpdate);
            } else {
                detectLocation();
            }
        } else {
            appendLog(getBaseContext(), TAG, "startLocationAndWeatherUpdate:requestWeatherCheck");
            requestWeatherCheck(currentLocation.getId(), updateSource, AppWakeUpManager.SOURCE_CURRENT_WEATHER, forceUpdate);
        }
    }

    private boolean gpsRequestLocation() {
        boolean isGPSEnabled = AppPreference.isGpsEnabledByPreferences(getBaseContext()) &&
                locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER)
                && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (isGPSEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Looper locationLooper = Looper.myLooper();
            appendLog(getBaseContext(), TAG, "get location from GPS");
            timerHandlerGpsLocation.postDelayed(timerRunnableGpsLocation, GPS_LOCATION_TIMEOUT_IN_MS);
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, gpsLocationListener, locationLooper);
            startRefreshRotation("gpsRequestLocation", 3);
            return true;
        } else {
            return false;
        }
    }

    private void setNoLocationFound() {
        final LocationsDbHelper locationDbHelper = LocationsDbHelper.getInstance(getBaseContext());
        long lastLocationUpdate = locationDbHelper.getLastUpdateLocationTime();
        Calendar now = Calendar.getInstance();
        now.add(Calendar.MINUTE, -5);
        if (lastLocationUpdate > now.getTimeInMillis()) {
            return;
        }
        locationDbHelper.setNoLocationFound();
        stopRefreshRotation("setNoLocationFound", 3);
        updateWidgets(updateSource);
    }

    private void updateNetworkLocation(Intent intent) {
        if (intent.getExtras() == null) {
            return;
        }
        boolean byLastLocationOnly = intent.getExtras().getBoolean("byLastLocationOnly");
        updateNetworkLocation(byLastLocationOnly, intent, 0);
    }

    public boolean updateNetworkLocation(boolean bylastLocationOnly,
                                         Intent originalIntent,
                                         Integer attempts) {
        return updateNetworkLocation(bylastLocationOnly, originalIntent, attempts, false);
    }

    public boolean updateNetworkLocation(boolean bylastLocationOnly,
                                      Intent originalIntent,
                                      Integer attempts,
                                      boolean forceUpdate) {
        this.forceUpdate = forceUpdate;
        updateLocationInProcess = true;
        startRefreshRotation("updateNetworkLocation", 3);
        boolean permissionsGranted = PermissionUtil.checkPermissionsAndSettings(this);
        appendLog(getBaseContext(), TAG, "updateNetworkLocation:", permissionsGranted);
        if (!permissionsGranted) {
            updateLocationInProcess = false;
            stopRefreshRotation("updateNetworkLocation", 3);
            return false;
        }
        boolean isNetworkEnabled = locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)
                && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        boolean isGPSEnabled = AppPreference.isGpsEnabledByPreferences(getBaseContext()) &&
                locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER)
                && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        String geocoder = AppPreference.getLocationGeocoderSource(getBaseContext());
        boolean networkNotEnabled = !isNetworkEnabled &&
                ("location_geocoder_system".equals(geocoder) || "location_geocoder_local".equals(geocoder)/*TODO: temporary solution*/);

        appendLog(getBaseContext(), TAG,
                "updateNetworkLocation:networkNotEnabled=",
                networkNotEnabled,
                ", isGPSEnabled=",
                isGPSEnabled,
                ", bylastLocationOnly=",
                bylastLocationOnly,
                ", isNetworkEnabled=",
                isNetworkEnabled);
        sendMessageToWakeUpService(
                AppWakeUpManager.WAKE_UP,
                AppWakeUpManager.SOURCE_LOCATION_UPDATE
        );
        if (networkNotEnabled && isGPSEnabled && !bylastLocationOnly) {
            appendLog(getBaseContext(), TAG, "updateNetworkLocation:request GPS and start rotation");
            if (gpsRequestLocation()) {
                return true;
            }
        }

        try {
            ConnectionDetector connectionDetector = new ConnectionDetector(getApplicationContext());
            LocationsDbHelper locationsDbHelper = LocationsDbHelper.getInstance(getApplicationContext());
            org.thosp.yourlocalweather.model.Location currentLocationForSensorEvent = locationsDbHelper.getLocationByOrderId(0);
            if (!connectionDetector.isNetworkAvailableAndConnected()) {
                appendLog(this, TAG, "Network is not available");
                if (!timerNetworkAvailabilityHandler.hasMessages(0) && !updateLocationInProcess) {
                    timerNetworkAvailabilityHandler.postDelayed(timerNetworkAvailabilityRunnable, NETWORK_AVAILABILITY_TIMEOUT_IN_MS);
                }
                updateLocationInProcess = false;
                stopRefreshRotation("updateNetworkLocation", 3);
                sendMessageToWakeUpService(
                        AppWakeUpManager.FALL_DOWN,
                        AppWakeUpManager.SOURCE_LOCATION_UPDATE
                );
                return false;
            }
            timerNetworkAvailabilityHandler.removeCallbacksAndMessages(null);
            /*locationsDbHelper.updateLocationSource(currentLocationForSensorEvent.getId(),
                    getString(R.string.location_weather_update_status_update_started));*/
        } catch (Exception e) {
            appendLog(this, TAG, "Exception occured during database update", e);
            updateLocationInProcess = false;
            stopRefreshRotation("updateNetworkLocation", 3);
            sendMessageToWakeUpService(
                    AppWakeUpManager.FALL_DOWN,
                    AppWakeUpManager.SOURCE_LOCATION_UPDATE
            );
            return false;
        }

        appendLog(getBaseContext(), TAG, "updateNetworkLocation:wakeup and start rotation");
        try {
            Location lastLocation = null;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                lastKnownLocationTimerHandler.postDelayed(lastKnownLocationTimerRunnable, LOCATION_TIMEOUT_IN_MS);
                lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                lastKnownLocationTimerHandler.removeCallbacksAndMessages(null);
            }
            updateNetworkLocationByNetwork(lastLocation, bylastLocationOnly, originalIntent, attempts);
            return true;
        } catch (Exception e) {
            appendLog(getBaseContext(), TAG, "Exception during update of network location", e);
        }
        updateLocationInProcess = false;
        stopRefreshRotation("updateNetworkLocation", 3);
        sendMessageToWakeUpService(
                AppWakeUpManager.FALL_DOWN,
                AppWakeUpManager.SOURCE_LOCATION_UPDATE
        );
        return false;
    }

    private void updateNetworkLocationByNetwork(Location lastLocation,
                                                boolean byLastLocationOnly,
                                                Intent originalIntent,
                                                Integer attempts) {
        updateLocationInProcess = true;
        startRefreshRotation("updateNetworkLocationByNetwork", 3);
        LocationsDbHelper locationsDbHelper = LocationsDbHelper.getInstance(getBaseContext());
        org.thosp.yourlocalweather.model.Location currentLocation = locationsDbHelper.getLocationByOrderId(0);
        if (resendRequestWhenNetworkNotAvailable(byLastLocationOnly, originalIntent, attempts)) {
            return;
        }

        Calendar now = Calendar.getInstance();
        now.add(Calendar.MINUTE, -5);

        org.thosp.yourlocalweather.model.Location autoLocation = locationsDbHelper.getLocationByOrderId(0);
        long lastLocationUpdate = autoLocation.getLastLocationUpdate();
        long gpsLastLocationTime = getLocationTimeInMilis(lastLocation);
        appendLog(getBaseContext(), TAG,
                "Comparison of last location from GPS time = ",
                gpsLastLocationTime,
                ", and location last update time = ",
                lastLocationUpdate);
        Location inputLocation = null;
        if ((lastLocation != null) &&
                (gpsLastLocationTime > (System.currentTimeMillis() - GPS_MAX_LOCATION_AGE_IN_MS)) &&
                (gpsLastLocationTime > lastLocationUpdate)) {
            inputLocation = lastLocation;
            locationsDbHelper.updateLocationSource(currentLocation.getId(),
                    getString(R.string.location_weather_update_status_location_from_gps) + getString(R.string.location_weather_update_status_location_from_last_location));
        } else if (byLastLocationOnly) {
            updateLocationInProcess = false;
            sendMessageToWakeUpService(
                    AppWakeUpManager.FALL_DOWN,
                    AppWakeUpManager.SOURCE_LOCATION_UPDATE
            );
            stopRefreshRotation("updateNetworkLocationByNetwork:3", 3);
            return;
        }

        appendLog(getBaseContext(), TAG, "start START_LOCATION_UPDATE:locationsource is N or G");
        String geocoder = AppPreference.getLocationGeocoderSource(getBaseContext());
        boolean systemNetworkLocation = ("location_geocoder_system".equals(geocoder) || "location_geocoder_local".equals(geocoder)/*TODO: temporary solution*/);
        if (systemNetworkLocation) {
            detectLocation();
        } else {
            startLocationUpdate(inputLocation);
            timerHandler.postDelayed(timerRunnable, LOCATION_TIMEOUT_IN_MS);
        }
    }

    /**
     * Resending of network location request helps to get response when:
     * - andoroid has wifi connection only
     * - and wifi connection is established when the screen is on only
     *
     * Therefore when user swtich the phone on the wifi connection is starting and
     * is not available at the moment. Updater has to wait to get wifi on and
     * try to get the location again.
     *
     * @param byLastLocationOnly - param to be resend for JobService
     * @param originalIntent - original intent for old API solution
     * @param attempts - number of attempts for JobService
     * @return true when we cannot continue with location discovery
     */
    private boolean resendRequestWhenNetworkNotAvailable(boolean byLastLocationOnly,
                                                         Intent originalIntent,
                                                         Integer attempts) {
        ConnectionDetector connectionDetector = new ConnectionDetector(this);
        if (connectionDetector.isNetworkAvailableAndConnected()) {
            return false;
        }
        LocationsDbHelper locationsDbHelper = LocationsDbHelper.getInstance(getBaseContext());
        org.thosp.yourlocalweather.model.Location currentLocation = locationsDbHelper.getLocationByOrderId(0);

        int numberOfAttempts;
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.M) {
            numberOfAttempts = attempts;
        } else if (originalIntent != null) {
            numberOfAttempts = originalIntent.getIntExtra("attempts", 0);
        } else {
            updateLocationInProcess = false;
            stopRefreshRotation("updateNetworkLocationByNetwork:1", 3);
            sendMessageToWakeUpService(
                    AppWakeUpManager.FALL_DOWN,
                    AppWakeUpManager.SOURCE_LOCATION_UPDATE
            );
            return true;
        }

        if (numberOfAttempts > 2) {
            locationsDbHelper.updateLocationSource(
                    currentLocation.getId(),
                    getString(R.string.location_weather_update_status_location_not_reachable));
            updateLocationInProcess = false;
            stopRefreshRotation("updateNetworkLocationByNetwork:2", 3);
            sendMessageToWakeUpService(
                    AppWakeUpManager.FALL_DOWN,
                    AppWakeUpManager.SOURCE_LOCATION_UPDATE
            );
            return true;
        }

        numberOfAttempts++;

        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.M) {
            PersistableBundle bundle = new PersistableBundle();
            bundle.putBoolean("byLastLocationOnly", byLastLocationOnly);
            bundle.putInt("attempts", numberOfAttempts);
            ComponentName serviceComponent = new ComponentName(this, LocationUpdateServiceRetryJob.class);
            JobInfo.Builder builder = new JobInfo.Builder(LocationUpdateServiceRetryJob.JOB_ID, serviceComponent);
            builder.setMinimumLatency(LOCATION_UPDATE_RESEND_INTERVAL_IN_MS); // wait at least
            builder.setOverrideDeadline(LOCATION_UPDATE_RESEND_INTERVAL_IN_MS + (5 * 1000)); // maximum delay
            builder.setExtras(bundle);
            JobScheduler jobScheduler = getSystemService(JobScheduler.class);
            jobScheduler.schedule(builder.build());
        } else {
            originalIntent.putExtra("attempts", numberOfAttempts);
            resendTheIntentInSeveralSeconds(LOCATION_UPDATE_RESEND_INTERVAL_IN_MS, originalIntent);
        }
        updateLocationInProcess = false;
        stopRefreshRotation("updateNetworkLocationByNetwork:2", 3);
        sendMessageToWakeUpService(
                AppWakeUpManager.FALL_DOWN,
                AppWakeUpManager.SOURCE_LOCATION_UPDATE
        );
        return true;
    }

    private void removeUpdates(LocationListener locationListener) {
        String geocoder = AppPreference.getLocationGeocoderSource(this);
        if("location_geocoder_system".equals(geocoder) || "location_geocoder_local".equals(geocoder)/*TODO: temporary solution*/) {
            locationManager.removeUpdates(locationListener);
        }
    }

    private void detectLocation() {
        if (!PermissionUtil.checkPermissionsAndSettings(this)) {
            updateWidgets(updateSource);
            stopSelf();
            return;
        }
        boolean isNetworkEnabled = locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)
                && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        appendLog(getBaseContext(), TAG, "detectLocation:isNetworkEnabled=", isNetworkEnabled);
        if (isNetworkEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            appendLog(getBaseContext(), TAG, "detectLocation:afterCheckSelfPermission");
            startRefreshRotation("detectLocation", 3);
            final Looper locationLooper = Looper.myLooper();
            locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, this, locationLooper);
            final LocationListener locationListener = this;
            final Handler locationHandler = new Handler(locationLooper);
            locationHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    locationManager.removeUpdates(locationListener);
                    appendLog(getBaseContext(), TAG, "detectLocation:lastLocationUpdateTime=", lastLocationUpdateTime);
                    if ((System.currentTimeMillis() - (2 * LOCATION_TIMEOUT_IN_MS)) < lastLocationUpdateTime) {
                        return;
                    }
                    final LocationsDbHelper locationsDbHelper = LocationsDbHelper.getInstance(getBaseContext());
                    final org.thosp.yourlocalweather.model.Location currentLocation = locationsDbHelper.getLocationByOrderId(0);
                    if (ContextCompat.checkSelfPermission(LocationUpdateService.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        Location lastNetworkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                        Location lastGpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        if ((lastGpsLocation == null) && (lastNetworkLocation != null)) {
                            locationsDbHelper.updateLocationSource(currentLocation.getId(),
                                    getString(R.string.location_weather_update_status_location_from_network) + getString(R.string.location_weather_update_status_location_from_last_location));
                            locationListener.onLocationChanged(lastNetworkLocation);
                        } else if ((lastGpsLocation != null) && (lastNetworkLocation == null)) {
                            locationsDbHelper.updateLocationSource(currentLocation.getId(),
                                    getString(R.string.location_weather_update_status_location_from_gps) + getString(R.string.location_weather_update_status_location_from_last_location));
                            locationListener.onLocationChanged(lastGpsLocation);
                        } else if (AppPreference.isGpsEnabledByPreferences(getBaseContext())){
                            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener);
                            new CountDownTimer(30000, 10000) {
                                @Override
                                public void onTick(long millisUntilFinished) {
                                }

                                @Override
                                public void onFinish() {
                                    locationManager.removeUpdates(LocationUpdateService.this);
                                    Location lastGpsLocation = null;
                                    if (ContextCompat.checkSelfPermission(LocationUpdateService.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                        lastGpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                                    }
                                    if (lastGpsLocation != null) {
                                        locationsDbHelper.updateLocationSource(currentLocation.getId(),
                                                getString(R.string.location_weather_update_status_location_from_gps) + getString(R.string.location_weather_update_status_location_from_last_location));
                                        locationListener.onLocationChanged(lastGpsLocation);
                                        return;
                                    }
                                    if (updateLocationInProcess) {
                                        updateLocationInProcess = false;
                                        stopRefreshRotation("updateNetworkLocationByNetwork:2", 3);
                                        sendMessageToWakeUpService(
                                                AppWakeUpManager.FALL_DOWN,
                                                AppWakeUpManager.SOURCE_LOCATION_UPDATE
                                        );
                                    }
                                    updateWidgets(updateSource);
                                    stopSelf();
                                }
                            }.start();
                        }
                    }
                }
            }, LOCATION_TIMEOUT_IN_MS);
        }
    }

    private long getLocationTimeInMilis(Location location) {
        if (location == null) {
            return 0;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
             return System.currentTimeMillis()
                - SystemClock.elapsedRealtime()
                + (location.getElapsedRealtimeNanos() / 1000000);
        } else {
            return location.getTime();
        }

    }

    private void resendTheIntentInSeveralSeconds(long timeInMilis, Intent intent) {
        AlarmManager alarmManager = (AlarmManager) getBaseContext().getSystemService(Context.ALARM_SERVICE);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(getBaseContext(),
                0,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + timeInMilis, pendingIntent);
    }

    private void startLocationUpdate(Location inputLocation) {
        appendLog(getBaseContext(), TAG, "startLocationUpdate");
        if (networkLocationProvider == null) {
            networkLocationProviderActions.add(new NetworkLocationProviderActionData(
                    NetworkLocationProvider.NetworkLocationProviderActions.START_LOCATION_UPDATE,
                    inputLocation));
            return;
        }
        networkLocationProvider.startLocationUpdate(inputLocation);
    }

    private final ServiceConnection networkLocationProviderConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            NetworkLocationProvider.NetworkLocationProviderBinder binder =
                    (NetworkLocationProvider.NetworkLocationProviderBinder) service;
            networkLocationProvider = binder.getService();
            NetworkLocationProviderActionData bindedServiceActions;
            while ((bindedServiceActions = networkLocationProviderActions.poll()) != null) {
                switch (bindedServiceActions.getAction()) {
                    case START_LOCATION_UPDATE:
                        networkLocationProvider.startLocationUpdate(bindedServiceActions.getInputLocation());
                        break;
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            networkLocationProvider = null;
        }
    };

    public class LocationUpdateServiceBinder extends Binder {
        public LocationUpdateService getService() {
            return LocationUpdateService.this;
        }
    }

    private class NetworkLocationProviderActionData {
        NetworkLocationProvider.NetworkLocationProviderActions action;
        Location inputLocation;

        public NetworkLocationProviderActionData(NetworkLocationProvider.NetworkLocationProviderActions action,
                                                 Location inputLocation) {
            this.action = action;
            this.inputLocation = inputLocation;
        }

        public NetworkLocationProvider.NetworkLocationProviderActions getAction() {
            return action;
        }

        public Location getInputLocation() {
            return inputLocation;
        }
    }
}
