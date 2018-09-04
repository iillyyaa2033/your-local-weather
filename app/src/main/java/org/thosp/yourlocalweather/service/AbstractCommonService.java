package org.thosp.yourlocalweather.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.annotation.Nullable;

import org.thosp.yourlocalweather.model.CurrentWeatherDbHelper;
import org.thosp.yourlocalweather.model.LocationsDbHelper;
import org.thosp.yourlocalweather.utils.ForecastUtil;
import org.thosp.yourlocalweather.utils.WidgetUtils;
import org.thosp.yourlocalweather.widget.ExtLocationWidgetService;
import org.thosp.yourlocalweather.widget.ExtLocationWidgetWithForecastService;
import org.thosp.yourlocalweather.widget.LessWidgetService;
import org.thosp.yourlocalweather.widget.MoreWidgetService;
import org.thosp.yourlocalweather.widget.WeatherForecastWidgetService;
import org.thosp.yourlocalweather.widget.WidgetRefreshIconService;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.thosp.yourlocalweather.utils.LogToFile.appendLog;

public class AbstractCommonService extends Service {

    private static final String TAG = "AbstractCommonService";

    protected String updateSource;
    private static Messenger widgetRefreshIconService;
    private static Queue<Message> unsentMessages = new LinkedList<>();
    private static Lock widgetRotationServiceLock = new ReentrantLock();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        bindWidgetRefreshIconService();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unbindWidgetRefreshIconService();
        } catch (Throwable t) {
            appendLog(getBaseContext(), TAG, t.getMessage(), t);
        }
    }

    protected void updateNetworkLocation(boolean byLastLocationOnly,
                                         boolean isInteractive) {
        startRefreshRotation("updateNetworkLocation", 3);
        Intent sendIntent = new Intent("android.intent.action.START_LOCATION_ONLY_UPDATE");
        sendIntent.putExtra("destinationPackageName", "org.thosp.yourlocalweather");
        sendIntent.putExtra("byLastLocationOnly", byLastLocationOnly);
        sendIntent.putExtra("isInteractive", isInteractive);
        WidgetUtils.startBackgroundService(getBaseContext(), sendIntent);
    }

    protected void updateWidgets() {
        if (WidgetRefreshIconService.isRotationActive) {
            return;
        }
        WidgetUtils.updateWidgets(getBaseContext());
        if (updateSource != null) {
            switch (updateSource) {
                case "MAIN":
                    sendIntentToMain();
                    break;
                case "NOTIFICATION":
                    WidgetUtils.startBackgroundService(
                            getBaseContext(),
                            new Intent(getBaseContext(), NotificationService.class));
                    break;
            }
        }
    }

    protected void sendIntentToMain() {
        Intent intent = new Intent(CurrentWeatherService.ACTION_WEATHER_UPDATE_RESULT);
        intent.putExtra(
                CurrentWeatherService.ACTION_WEATHER_UPDATE_RESULT,
                CurrentWeatherService.ACTION_WEATHER_UPDATE_FAIL);
        WidgetUtils.startBackgroundService(getBaseContext(), intent);
    }

    protected void sendIntentToMain(String result) {
        Intent intent = new Intent(CurrentWeatherService.ACTION_WEATHER_UPDATE_RESULT);
        if (result.equals(CurrentWeatherService.ACTION_WEATHER_UPDATE_OK)) {
            intent.putExtra(
                    CurrentWeatherService.ACTION_WEATHER_UPDATE_RESULT,
                    CurrentWeatherService.ACTION_WEATHER_UPDATE_OK);
        } else if (result.equals(CurrentWeatherService.ACTION_WEATHER_UPDATE_FAIL)) {
            intent.putExtra(
                    CurrentWeatherService.ACTION_WEATHER_UPDATE_RESULT,
                    CurrentWeatherService.ACTION_WEATHER_UPDATE_FAIL);
        }
        sendBroadcast(intent);
    }

    protected void requestWeatherCheck(String locationSource, boolean isInteractive) {
        appendLog(getBaseContext(), TAG, "startRefreshRotation");
        boolean updateLocationInProcess = LocationUpdateService.updateLocationInProcess;
        appendLog(getBaseContext(), TAG, "requestWeatherCheck, updateLocationInProcess=" +
                updateLocationInProcess);
        if (updateLocationInProcess) {
            updateWidgets();
            return;
        }
        updateNetworkLocation(true, isInteractive);
        LocationsDbHelper locationsDbHelper = LocationsDbHelper.getInstance(getBaseContext());
        org.thosp.yourlocalweather.model.Location currentLocation = locationsDbHelper.getLocationByOrderId(0);
        if (locationSource != null) {
            locationsDbHelper.updateLocationSource(currentLocation.getId(), "-");
            currentLocation = locationsDbHelper.getLocationById(currentLocation.getId());
        }
        sendIntentToGetWeather(currentLocation, isInteractive);
    }

    protected void sendIntentToGetWeather(org.thosp.yourlocalweather.model.Location currentLocation, boolean isInteractive) {
        CurrentWeatherDbHelper currentWeatherDbHelper = CurrentWeatherDbHelper.getInstance(getBaseContext());
        currentWeatherDbHelper.updateLastUpdatedTime(currentLocation.getId(), System.currentTimeMillis());
        Intent intentToCheckWeather = new Intent(getBaseContext(), CurrentWeatherService.class);
        intentToCheckWeather.putExtra("locationId", currentLocation.getId());
        intentToCheckWeather.putExtra("updateSource", updateSource);
        intentToCheckWeather.putExtra("isInteractive", isInteractive);
        WidgetUtils.startBackgroundService(getBaseContext(), intentToCheckWeather);
        startWeatherForecastUpdate(currentLocation.getId());
    }

    private void startWeatherForecastUpdate(long locationId) {
        if (!ForecastUtil.shouldUpdateForecast(this, locationId)) {
            return;
        }
        Intent intentToCheckWeather = new Intent(this, ForecastWeatherService.class);
        intentToCheckWeather.putExtra("locationId", locationId);
        WidgetUtils.startBackgroundService(getBaseContext(), intentToCheckWeather);
    }

    protected void startRefreshRotation(String where, int rotationSource) {
        appendLog(getBaseContext(), TAG, "startRefreshRotation:" + where);
        sendMessageToWidgetIconService(WidgetRefreshIconService.START_ROTATING_UPDATE, rotationSource);
    }

    protected void stopRefreshRotation(String where, int rotationSource) {
        appendLog(getBaseContext(), TAG, "stopRefreshRotation:" + where);
        sendMessageToWidgetIconService(WidgetRefreshIconService.STOP_ROTATING_UPDATE, rotationSource);
    }

    protected void sendMessageToWidgetIconService(int action, int rotationsource) {
        widgetRotationServiceLock.lock();
        try {
            Message msg = Message.obtain(null, action, rotationsource, 0);
            if (checkIfWidgetIconServiceIsNotBound()) {
                //appendLog(getBaseContext(), TAG, "WidgetIconService is still not bound");
                unsentMessages.add(msg);
                return;
            }
            //appendLog(getBaseContext(), TAG, "sendMessageToService:");
            widgetRefreshIconService.send(msg);
        } catch (RemoteException e) {
            appendLog(getBaseContext(), TAG, e.getMessage(), e);
        } finally {
            widgetRotationServiceLock.unlock();
        }
    }

    private boolean checkIfWidgetIconServiceIsNotBound() {
        if (widgetRefreshIconService != null) {
            return false;
        }
        try {
            bindWidgetRefreshIconService();
        } catch (Exception ie) {
            appendLog(getBaseContext(), TAG, "checkIfWidgetIconServiceIsNotBound interrupted:", ie);
        }
        return (widgetRefreshIconService == null);
    }

    private void bindWidgetRefreshIconService() {
        bindService(
                new Intent(this, WidgetRefreshIconService.class),
                widgetRefreshIconConnection,
                Context.BIND_AUTO_CREATE);
    }

    private void unbindWidgetRefreshIconService() {
        if (widgetRefreshIconService == null) {
            return;
        }
        unbindService(widgetRefreshIconConnection);
    }


    private ServiceConnection widgetRefreshIconConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binderService) {
            widgetRefreshIconService = new Messenger(binderService);
            widgetRotationServiceLock.lock();
            try {
                while (!unsentMessages.isEmpty()) {
                    widgetRefreshIconService.send(unsentMessages.poll());
                }
            } catch (RemoteException e) {
                appendLog(getBaseContext(), TAG, e.getMessage(), e);
            } finally {
                widgetRotationServiceLock.unlock();
            }
        }
        public void onServiceDisconnected(ComponentName className) {
            widgetRefreshIconService = null;
        }
    };
}
