package org.quark.dr.canapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.quark.dr.ecu.EcuDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.quark.dr.canapp.ElmThread.STATE_CONNECTED;
import static org.quark.dr.canapp.ElmThread.STATE_CONNECTING;
import static org.quark.dr.canapp.ElmThread.STATE_DISCONNECTED;
import static org.quark.dr.canapp.ElmThread.STATE_LISTEN;
import static org.quark.dr.canapp.ElmThread.STATE_NONE;
import static org.quark.dr.canapp.ScreenActivity.MESSAGE_DEVICE_NAME;
import static org.quark.dr.canapp.ScreenActivity.MESSAGE_QUEUE_STATE;
import static org.quark.dr.canapp.ScreenActivity.MESSAGE_READ;
import static org.quark.dr.canapp.ScreenActivity.MESSAGE_STATE_CHANGE;
import static org.quark.dr.canapp.ScreenActivity.MESSAGE_TOAST;
import static org.quark.dr.canapp.ScreenActivity.TOAST;

public class MainActivity extends AppCompatActivity {
    final static String TAG = "EcuTweaker";
    final static int PERMISSIONS_ACCESS_EXTERNAL_STORAGE = 0;
    // Intent request codes
    private static final int    REQUEST_CONNECT_DEVICE = 1;
    private static final int    REQUEST_ENABLE_BT      = 3;
    private static final String DEFAULT_PREF_TAG = "default";

    public static final String PREF_DEVICE_ADDRESS = "btAdapterAddress";
    public static final String PREF_ECUZIPFILE = "ecuZipFile";


    private EcuDatabase m_ecuDatabase;
    private TextView m_statusView;
    private Button m_btButton, m_scanButton;
    private ImageButton m_chooseProjectButton;
    private ListView m_ecuListView, m_specificEcuListView;
    private ArrayList<EcuDatabase.EcuInfo> m_currentEcuInfoList;
    private String m_ecuFilePath, m_btDeviceAddress, m_currentProject;

    private ElmThread m_chatService;
    private Handler mHandler = null;
    private BluetoothAdapter mBluetoothAdapter = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        m_currentProject = "";

        if (mBluetoothAdapter != null) {
            if (!mBluetoothAdapter.isEnabled()){
                Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            } else {
                Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            }
        }

        mHandler = new MainActivity.messageHandler(this);
        setupChat();

        m_statusView = findViewById(R.id.statusView);
        m_btButton = findViewById(R.id.btButton);
        m_ecuListView = findViewById(R.id.ecuListView);
        m_specificEcuListView = findViewById(R.id.deviceView);
        m_scanButton = findViewById(R.id.buttonScan);
        m_chooseProjectButton = findViewById(R.id.projectButton);

        m_scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanBus();
            }
        });

        m_btButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectBtDevice();
            }
        });

        m_ecuListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String info = ((TextView) view).getText().toString();
                ecuTypeSelected(info, m_currentProject);
            }
        });

        m_specificEcuListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if ( m_currentEcuInfoList == null || m_ecuFilePath == null){
                    return;
                }
                String stringToSearch = ((TextView)view).getText().toString();
                for (EcuDatabase.EcuInfo ecuinfo : m_currentEcuInfoList){
                    if (stringToSearch.equals(ecuinfo.ecuName)){
                        startScreen(m_ecuFilePath, ecuinfo.href);
                    }
                }
            }
        });

        m_chooseProjectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseProject();
            }
        });

        SharedPreferences defaultPrefs = this.getSharedPreferences(DEFAULT_PREF_TAG, MODE_PRIVATE);
        m_btDeviceAddress = defaultPrefs.getString(PREF_DEVICE_ADDRESS, "");

        m_ecuDatabase = new EcuDatabase();

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED){
            parseDatabase();
        } else {
            askPermission();
        }

        m_scanButton.setEnabled(false);
        m_chatService.start();
        // Only for debug purpose
        //startScreen("/sdcard/ecu.zip", "UCH_84P2_85_V3.json");
    }

    private void connectDevice(String address) {
        if (mBluetoothAdapter == null)
            return;

        // address is the device MAC address
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
        m_chatService.connect(device);
    }

    private void setupChat() {
        Log.d(TAG, "setupChat()");
        // Initialize the BluetoothChatService to perform bluetooth connections
        m_chatService = new ElmThread(mHandler);
    }

    void scanBus(){
        if(m_chatService.getState() != STATE_CONNECTED){
            return;
        }
        m_chatService.initElm();
    }

    void ecuTypeSelected(String type, String project){
        int ecuAddress = m_ecuDatabase.getAddressByFunction(type);
        if (ecuAddress < 0) {
            m_specificEcuListView.setAdapter(null);
            return;
        }

        ArrayList<EcuDatabase.EcuInfo> ecuArray = m_ecuDatabase.getEcuInfo(ecuAddress);
        m_currentEcuInfoList = ecuArray;
        if (ecuArray == null) {
            m_specificEcuListView.setAdapter(null);
            return;
        }
        ArrayList<String> ecuNames = new ArrayList<>();
        for(EcuDatabase.EcuInfo info : ecuArray){
            if (project.isEmpty() || info.projects.contains(project))
                ecuNames.add(info.ecuName);
        }
        Collections.sort(ecuNames);
        ArrayAdapter<String> adapter;

        adapter=new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1,
                ecuNames);
        m_specificEcuListView.setAdapter(adapter);
    }

    void selectBtDevice(){
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (bluetoothAdapter == null) {
            return;
        }

        if (bluetoothAdapter.isEnabled()) {
            try {
                Intent serverIntent = new Intent(this, DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
            } catch (android.content.ActivityNotFoundException e) {
                Log.e(TAG, "+++ ActivityNotFoundException +++");
            }
        }
    }

    void startScreen(String ecuFile, String ecuHREFName){
        try {
            Intent serverIntent = new Intent(this, ScreenActivity.class);
            Bundle b = new Bundle();
            b.putString("ecuFile", ecuFile);
            b.putString("ecuRef", ecuHREFName);
            b.putString("deviceAddress", m_btDeviceAddress);
            serverIntent.putExtras(b);
            startActivity(serverIntent);
        } catch (android.content.ActivityNotFoundException e) {
            Log.e(TAG, "+++ ActivityNotFoundException +++");
        }
    }

    void askPermission(){
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSIONS_ACCESS_EXTERNAL_STORAGE);
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSIONS_ACCESS_EXTERNAL_STORAGE);
            }
        } else {
            parseDatabase();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[],
                                           int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_ACCESS_EXTERNAL_STORAGE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    parseDatabase();
                } else {

                }
            }
        }
    }

    @Override
    public void onDestroy()
    {
        Log.e(TAG, "+ ON DESTROY +");
        super.onDestroy();
        if (m_chatService != null)
            m_chatService.stop();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    Log.d(TAG, "onActivityResult " + address);
                    SharedPreferences defaultPrefs = this.getSharedPreferences(DEFAULT_PREF_TAG, MODE_PRIVATE);
                    SharedPreferences.Editor edit = defaultPrefs.edit();
                    edit.putString(PREF_DEVICE_ADDRESS, address);
                    edit.commit();
                    m_btDeviceAddress = address;
                    connectDevice(address);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {

                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled");
                }
        }
    }

    void parseDatabase(){
        String ecuFile = "";
        SharedPreferences defaultPrefs = this.getSharedPreferences(DEFAULT_PREF_TAG, MODE_PRIVATE);
        if (defaultPrefs.contains(PREF_ECUZIPFILE)) {
            ecuFile = defaultPrefs.getString(PREF_ECUZIPFILE, "");
        }
        m_statusView.setText("INDEXING DATABASE...");
        new LoadDbTask(m_ecuDatabase).execute(ecuFile);
    }

    void updateListView(String ecuFile, String project){
        if (ecuFile.isEmpty()){
            m_statusView.setText("DATABASE NOT FOUND");
            return;
        }
        m_statusView.setText("DATABASE LOADED");
        SharedPreferences defaultPrefs = getSharedPreferences(DEFAULT_PREF_TAG, MODE_PRIVATE);
        SharedPreferences.Editor edit = defaultPrefs.edit();
        edit.putString(PREF_ECUZIPFILE, ecuFile);
        edit.commit();

        m_ecuFilePath = ecuFile;

        ArrayAdapter<String> adapter;
        ArrayList<String> adapterList = m_ecuDatabase.getEcuByFunctionsAndType(project);
        Collections.sort(adapterList);
        if (adapterList.isEmpty())
            return;
        adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1,
                adapterList);

        m_ecuListView.setAdapter(adapter);

        Log.i(TAG, "Database sucessfully loaded");
    }

    public class LoadDbTask extends AsyncTask<String, Void, String> {

        private final EcuDatabase db;

        public LoadDbTask(EcuDatabase data) {
            this.db = data;
        }

        @Override
        protected String doInBackground(String... params) {
            String ecuFile = params[0];
            try {
                String appDir = getApplicationContext().getFilesDir().getAbsolutePath();
                ecuFile = m_ecuDatabase.loadDatabase(ecuFile, appDir);
            } catch (EcuDatabase.DatabaseException e){
                Log.e(TAG, "Database exception : " + e.getMessage());
                return "";
            }

            return ecuFile;
        }

        @Override
        protected void onPostExecute(String ecuFile) {
            updateListView(ecuFile, "");
        }
    }

    private void chooseProject(){
        if (!m_ecuDatabase.isLoaded())
            return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Choose a project");

        final String[] projects = m_ecuDatabase.getProjects();
        Arrays.sort(projects);
        builder.setItems(projects, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                m_currentProject = projects[which];
                updateListView(m_ecuFilePath, m_currentProject);
            }
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private static class messageHandler extends Handler {
        private MainActivity activity;
        messageHandler(MainActivity ac){
            activity = ac;
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_STATE_CHANGE:
                    Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                    switch (msg.arg1) {
                        case STATE_CONNECTED:
                            activity.setConnected(true);
                            break;
                        case STATE_CONNECTING:
                            activity.setConnected(false);
                            break;
                        case STATE_LISTEN:
                        case STATE_NONE:

                            break;
                        case STATE_DISCONNECTED:
                            activity.setConnected(false);
                            break;
                    }
                    break;
                case MESSAGE_READ:
                    byte[] m = (byte[]) msg.obj;
                    String readMessage = new String(m, 0, msg.arg1);
                    int txa = msg.arg2;
                    break;
                case MESSAGE_DEVICE_NAME:

                    break;
                case MESSAGE_TOAST:
                    Toast.makeText(activity.getApplicationContext(), msg.getData().getString(TOAST), Toast.LENGTH_SHORT).show();
                    break;
                case MESSAGE_QUEUE_STATE:
                    int queue_len = msg.arg1;
                    break;
            }
        }
    }

    void setConnected(boolean c){
        m_scanButton.setEnabled(c);
    }

}