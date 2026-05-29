package studio.midoridesign.compassx;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle; 
import androidx.core.content.ContextCompat;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.EventChannel;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import java.util.HashMap;

public class CompassXPlugin implements FlutterPlugin, EventChannel.StreamHandler, ActivityAware {
    private EventChannel channel;
    private Context context;
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private Sensor headingSensor;
    private SensorEventListener sensorEventListener;
    private LocationManager locationManager;
    private Location currentLocation;
    private final LocationListener locationListener;
    private final float headingChangeThreshold = 0.1f;
    private float lastTrueHeading = 0f;
    private Activity activity;

    public CompassXPlugin() {
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                currentLocation = location;
            }

            @SuppressWarnings({"deprecation", "RedundantSuppression"})
            @TargetApi(28)
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };
    }

    @Override
    public void onAttachedToEngine(FlutterPlugin.FlutterPluginBinding flutterPluginBinding) {
        context = flutterPluginBinding.getApplicationContext();
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEADING);
        channel = new EventChannel(flutterPluginBinding.getBinaryMessenger(),
                "studio.midoridesign/compassx");
        channel.setStreamHandler(this);
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        startLocationUpdates();
    }

    @Override
    public void onDetachedFromEngine(FlutterPlugin.FlutterPluginBinding binding) {
        unregisterListener();
        channel.setStreamHandler(null);
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        sensorEventListener = createSensorEventListener(events);
        if (headingSensor != null) {
            sensorManager.registerListener(sensorEventListener, headingSensor,
                    SensorManager.SENSOR_DELAY_GAME);
        } else if (rotationVectorSensor != null) {
            sensorManager.registerListener(sensorEventListener, rotationVectorSensor,
                    SensorManager.SENSOR_DELAY_GAME);
        } else {
            events.error("SENSOR_NOT_FOUND", "No compass sensor found.", null);
            events.endOfStream();
        }
    }

    @Override
    public void onCancel(Object arguments) {
        unregisterListener();
    }

    private void unregisterListener() {
        sensorManager.unregisterListener(sensorEventListener);
    }

    private SensorEventListener createSensorEventListener(final EventChannel.EventSink events) {
        return new SensorEventListener() {
            boolean shouldCalibrate = false;

            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                    if (event.values == null || event.values.length < 3) return;

                    try {
                        float[] rotationMatrix = new float[9];
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                        float[] orientationAngles = new float[3];
                        SensorManager.getOrientation(rotationMatrix, orientationAngles);

                        float azimuth = (float) Math.toDegrees(orientationAngles[0]);
                        if (!Float.isFinite(azimuth)) return;

                        float trueHeading = calculateTrueHeading(azimuth);
                        if (!Float.isFinite(trueHeading)) return;

                        float accuracy = -1f;
                        if (event.values.length > 4) {
                            float accuracyRadian = event.values[4];
                            if (accuracyRadian != -1f) {
                                float computedAccuracy = (float) Math.toDegrees(accuracyRadian);
                                if (Float.isFinite(computedAccuracy)) {
                                    accuracy = computedAccuracy;
                                }
                            }
                        }

                        if (Math.abs(lastTrueHeading - trueHeading) > headingChangeThreshold) {
                            lastTrueHeading = trueHeading;
                            notifyCompassChangeListeners(events, trueHeading, accuracy,
                                    shouldCalibrate);
                        }
                    } catch (RuntimeException ignored) {
                        return;
                    }
                } else if (event.sensor.getType() == Sensor.TYPE_HEADING) {
                    if (event.values == null || event.values.length < 1) return;

                    float heading = event.values[0];
                    if (!Float.isFinite(heading)) return;

                    float accuracy = -1f;
                    if (event.values.length > 1 && Float.isFinite(event.values[1])) {
                        accuracy = event.values[1];
                    }

                    if (Math.abs(lastTrueHeading - heading) > headingChangeThreshold) {
                        lastTrueHeading = heading;
                        notifyCompassChangeListeners(events, heading, accuracy, shouldCalibrate);
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                if (sensor != rotationVectorSensor && sensor != headingSensor) return;
                shouldCalibrate = accuracy != SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
            }

            private float calculateTrueHeading(float azimuth) {
                if (!Float.isFinite(azimuth)) return Float.NaN;

                float declination =
                        currentLocation != null
                                ? new GeomagneticField((float) currentLocation.getLatitude(),
                                        (float) currentLocation.getLongitude(),
                                        (float) currentLocation.getAltitude(),
                                        System.currentTimeMillis()).getDeclination()
                                : 0f;

                if (!Float.isFinite(declination)) return Float.NaN;

                float trueHeading = (azimuth + declination + 360f) % 360f;
                return Float.isFinite(trueHeading) ? trueHeading : Float.NaN;
            }

            private void notifyCompassChangeListeners(EventChannel.EventSink events, float heading,
                    float accuracy, boolean shouldCalibrate) {
                if (!Float.isFinite(heading)) return;
                if (!Float.isFinite(accuracy) && accuracy != -1f) return;

                events.success(new HashMap<String, Object>() {
                    {
                        put("heading", heading);
                        put("accuracy", accuracy);
                        put("shouldCalibrate", shouldCalibrate);
                    }
                });

            }
        };
    }

    @Override
    public void onAttachedToActivity(ActivityPluginBinding binding) {
        activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(
                new PluginRegistry.RequestPermissionsResultListener() {
                    @Override
                    public boolean onRequestPermissionsResult(int requestCode, String[] permissions,
                            int[] grantResults) {
                        if (permissions.length == 0 || grantResults.length == 0) return false;
                        String permission = permissions[0];
                        int grantResult = grantResults[0];
                        if ((permission.equals(Manifest.permission.ACCESS_FINE_LOCATION)
                                || permission.equals(Manifest.permission.ACCESS_COARSE_LOCATION))
                                && grantResult == PackageManager.PERMISSION_GRANTED) {
                            startLocationUpdates();
                        }
                        return false;
                    }
                });
    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    private void startLocationUpdates() {
        boolean hasFineLocation = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarseLocation = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!hasFineLocation && !hasCoarseLocation) return;

        // Since this location is used for GeomagneticField, it is acceptable to update it less
        // frequently.
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 300000L, 10f,
                locationListener);
    }
}
