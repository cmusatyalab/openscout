// Copyright 2020 Carnegie Mellon University
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package edu.cmu.cs.openscout;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import java.util.Map;
import java.util.UUID;

import edu.cmu.cs.gabriel.Const;
import edu.cmu.cs.gabriel.serverlist.ServerListFragment;
import edu.cmu.cs.sinfonia.SinfoniaActivity;


public class ServerListActivity extends AppCompatActivity implements LocationListener {
    CameraManager camMan = null;
    private SharedPreferences mSharedPreferences;
    private static final int MY_PERMISSIONS_REQUEST_CAMERA = 23;
    private static final String TAG = "OpenScout/ServerListActivity";

    void loadPref(SharedPreferences sharedPreferences, String key) {
        Const.loadPref(sharedPreferences, key);
    }

    //activity menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        Intent intent;
        switch (id) {
            case R.id.about:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);

                builder.setMessage(this.getString(R.string.about_message, Const.UUID, BuildConfig.VERSION_NAME))
                        .setTitle(R.string.about_title);
                AlertDialog dialog = builder.create();
                dialog.show();
                return true;
            case R.id.settings:
                intent = new Intent(this, SettingsActivity.class);
                this.startActivity(intent);
                return true;
            case R.id.find_cloudlets:
                intent = new Intent(this, SinfoniaActivity.class);
                startActivity(intent);
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            AlertDialog.Builder builder = new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK);
            builder.setMessage(R.string.enable_gps_text)
                    .setTitle(R.string.enable_gps_title)
                    .setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {

                                }
                            }
                    )
                    .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    enableLocationSettings();
                                }
                            }
                    )
                    .setCancelable(false);

            AlertDialog dialog = builder.create();
            dialog.show();
        } else {
            // This verification should be done during onStart() because the system calls
            // this method when the user returns to the activity, which ensures the desired
            // location provider is enabled each time the activity resumes from the stopped state.
            LocationManager locationManager =
                    (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            final boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, Const.GPS_UPDATE_TIME, Const.GPS_UPDATE_DIST, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, Const.GPS_UPDATE_TIME, Const.GPS_UPDATE_DIST, this);
        }
    }

    private void enableLocationSettings() {
        Intent settingsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        startActivity(settingsIntent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestPermission();

        mSharedPreferences= PreferenceManager.getDefaultSharedPreferences(this);

        Map<String, ?> m = mSharedPreferences.getAll();
        for(Map.Entry<String,?> entry : m.entrySet()){
            Log.d("SharedPreferences",entry.getKey() + ": " +
                    entry.getValue().toString());
            this.loadPref(mSharedPreferences, entry.getKey());

        }
        if (mSharedPreferences.getAll().isEmpty()) {
            //Generate UUID for device identification
            String uniqueID = UUID.randomUUID().toString();

            SharedPreferences.Editor editor = mSharedPreferences.edit();
            editor.putString("uuid", uniqueID);

            // Add demo server if there are no other servers present

            editor.putString("server:".concat(getString(R.string.demo_server)),getString(R.string.demo_dns));
            editor.commit();

        }

        Const.UUID = mSharedPreferences.getString("uuid", "");

        setContentView(R.layout.activity_serverlist);
        Toolbar myToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(myToolbar);
        Fragment fragment =  new ServerListFragment("edu.cmu.cs.openscout","edu.cmu.cs.openscout.GabrielClientActivity");
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_serverlist, fragment)
                .commitNow();


        camMan = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    }

    void requestPermissionHelper(String permissions[]) {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            ActivityCompat.requestPermissions(this,
                    permissions,
                    MY_PERMISSIONS_REQUEST_CAMERA);
        }
    }

    void requestPermission() {
        String permissions[] = {Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
        this.requestPermissionHelper(permissions);
    }

    @Override
    public void onLocationChanged(Location location) {
        Log.i("ServerListActivity", "Location changed.");
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.i("ServerListActivity", String.format("Location provider %s enabled.", provider));
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.i("ServerListActivity", String.format("Location provider %s disabled.", provider));
    }
}
