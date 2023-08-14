package edu.cmu.cs.sinfonia;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

public class SinfoniaActivity extends AppCompatActivity {
    private static final String TAG = "OpenScout/SinfoniaActivity";
    private static final int REQUEST_CODE_PERMISSION = 44;
    private SinfoniaService sinfoniaService;
    private boolean isServiceBound = false;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Log.i(TAG, "onServiceConnected");
            SinfoniaService.MyBinder binder = (SinfoniaService.MyBinder) iBinder;
            sinfoniaService = binder.getService();
            isServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.i(TAG, "onServiceDisconnected");
            sinfoniaService = null;
            isServiceBound = false;
        }

        @Override
        public void onBindingDied(ComponentName componentName) {
            Log.i(TAG, "onBindingDied");
            isServiceBound = false;
        }

        @Override
        public void onNullBinding(ComponentName componentName) {
            Log.i(TAG, "onNullBinding");
            isServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

//        requestPermissions();

        Intent intent = new Intent(this, SinfoniaService.class)
                .setAction(SinfoniaService.ACTION_BIND);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (getSupportFragmentManager().findFragmentById(android.R.id.content) == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, new SinfoniaFragment())
                    .commit();
        }
    }

    @Override
    protected void onDestroy() {
        if (isServiceBound) unbindService(serviceConnection);
        super.onDestroy();
    }

    public SinfoniaService getSinfoniaService() {
        return sinfoniaService;
    }
}
