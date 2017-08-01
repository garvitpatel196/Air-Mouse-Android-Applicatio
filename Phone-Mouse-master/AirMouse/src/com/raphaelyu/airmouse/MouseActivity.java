package com.raphaelyu.airmouse;

import java.io.IOException;         //provides for system input & output thorough data  like file not found
import java.net.DatagramPacket;     //used to implement connectionless packet delivery service...multi packet sends to one machine to another
import java.net.DatagramSocket;     //connectionless socket for sending and receiving datagram packets
import java.net.Inet4Address;       //IPv4 address
import java.net.InetAddress;        //IP address
import java.net.InetSocketAddress;  //create socket endpoint with the given port number
import java.net.SocketAddress;      //provides ip + port number
import java.net.UnknownHostException;//Thrown when hostname can't be resolved
import java.nio.ByteBuffer;            //Allocate memory block
import java.nio.channels.DatagramChannel;//send and receive udp packet


import android.app.Activity;            //Provides screen with which useer  can interact
import android.app.AlertDialog.Builder; //Alert popup message
import android.app.AlertDialog;         //Alert message
import android.app.ProgressDialog;      //When we have to wait for some operation (like...loading please wait)
import android.app.Dialog;              //Base class for dialog..works like alert
import android.content.DialogInterface; //creator of dialog to run some code when an item on the dialog is clicked(onclick listener)
import android.content.pm.PackageInfo;  //all info about the package
import android.content.pm.PackageManager.NameNotFoundException;//thrown exception when a given packge ,app or component name can't be found
import android.hardware.Sensor;         //provides hardware sensor features
import android.hardware.SensorEvent;    // holds info such as sensor's type,time-stamp,accuracy
import android.hardware.SensorEventListener;//used for receiving notification from the sensormanager when sensor value have changed
import android.hardware.SensorManager;  //gives the access of device's sensor
import android.os.AsyncTask;            //perform background operation and publish results on the user interface thread
import android.os.Bundle;               //passing data between various activities of android
import android.os.Handler;              //allows to send and process massage
import android.os.HandlerThread;        // class for starting a new thread that has a looper
import android.os.Looper;               //used to execute a message in queue
import android.os.Message;              //contain description and data object that can be sent to handler
import android.text.InputType;          //Datatypes of inputs like...phone number,email id,etc
import android.text.method.NumberKeyListener;//For numeric text entry
import android.view.Menu;               //managing items in menu
import android.view.MenuInflater;       //allow to change the implementation in future version
import android.view.MenuItem;           //Interface for direct access to a previously created menu item
import android.view.View;               //Provides classes that handle screen layout and interaction with the user
import android.view.View.OnClickListener;//Called when a view has been clicked
import android.view.MotionEvent;           //used to report movement (mouse, pen, finger, trackball) events
import android.view.View.OnTouchListener;   //Called when a touch event is dispatched to a view
import android.view.ViewGroup;              //The view group is the base class for layouts and views containers
import android.view.WindowManager;          //interface that apps use to talk to window manager
import android.widget.Button;               //buttons thta can be pushed or clicked by user to perform action
import android.widget.EditText;             //a thin cover over TextView that configures itself to be editable
import android.widget.Toast;                //create and show a quick little message for the user
import android.widget.ToggleButton;         //Displays checked/unchecked states as a button

public class MouseActivity extends Activity implements SensorEventListener, OnTouchListener,
        OnClickListener {
    final static boolean TEST_MODE = false;
    final static String TEST_SERVER_IP = "192.168.11.143";


    final static int PROTOCAL_VERSION = 2;
    final static short SERVER_PORT = 5329;
    final static int MAX_PACKET_LENGTH = 64;
    final static byte PACKET_TYPE_DISCOVER = 0x1;
    final static byte PACKET_TYPE_REPLY = 0x2;
    final static byte PACKET_TYPE_MOVE = 0x10;
    final static byte PACKET_TYPE_PRESS = 0x11;
    final static byte PACKET_TYPE_RELEASE = 0x12;
    final static byte PACKET_MOUSE_BUTTON_LEFT = 1;
    final static byte PACKET_MOUSE_BUTTON_RIGHT = 2;
    final static byte PACKET_MOUSE_BUTTON_MIDDLE = 3;

    private static final int MSG_MOVE_COMMAND = 1;
    private static final int MSG_PRESS_COMMAND = 2;
    private static final int MSG_RELEASE_COMMAND = 3;
    private static final int MSG_WHEEL_COMMAND = 4;

    private static final float EPSILON_ACCELEROMETER = 0.02f;
    private static final float EPSILON_GRAVITY = 0.0015f;

    private float mEpsilon;

    private float mLastX = 0.0f;

    private float mLastY = 0.0f;

    private boolean mPausing;

    private ToggleButton mTbSwitch;
    private ViewGroup mLayoutNoServer;
    private ViewGroup mLayoutMouse;
    private Button mBtnRetry;
    private Button mBtnManual;
    private View mBtnLeft;
    private View mBtnRight;
    private EditText mEtIpAddr;
    private Dialog mAboutDialog;

    private Sensor mSensor;
    private SensorManager mSensorManager;

    private Handler mSocketHandler;
    DatagramChannel mSocketChannel;
    private SocketAddress mServerAddr = null;

    private final class CommandHandler extends Handler {
        private CommandHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_MOVE_COMMAND:
                float pos[] = (float[]) msg.obj;
                onMoveCommand(pos[0], pos[1]);
                break;
            case MSG_PRESS_COMMAND:
                onPressCommand(msg.arg1);
                break;
            case MSG_RELEASE_COMMAND:
                onReleaseCommand(msg.arg1);
                break;
            }
        }

        private void onMoveCommand(float x, float y) {
            if (mServerAddr != null) {
                mPacketBuffer.clear();
                mPacketBuffer.put(PACKET_TYPE_MOVE);
                mPacketBuffer.putLong(System.currentTimeMillis());
                mPacketBuffer.putFloat(x);
                mPacketBuffer.putFloat(y);
                mPacketBuffer.flip();

                sendPacket();
            }
        }



        private void onReleaseCommand(int button) {
            if (mServerAddr != null) {
                mPacketBuffer.clear();
                mPacketBuffer.put(PACKET_TYPE_RELEASE);
                mPacketBuffer.putLong(System.currentTimeMillis());
                mPacketBuffer.putInt(button);
                mPacketBuffer.flip();

                for (int i = 0; i < 3; i++) {
                    sendPacket();
                    mPacketBuffer.rewind();
                }
            }
        }

        private void sendPacket() {
            if (mServerAddr != null && !mPausing) {
                try {
                    mSocketChannel.send(mPacketBuffer, mServerAddr);
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }

        private ByteBuffer mPacketBuffer = ByteBuffer.allocate(MAX_PACKET_LENGTH);
    }

    private class DiscoverTask extends AsyncTask<Void, Void, Void> {

        Dialog mDialog;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mDialog = ProgressDialog.show(MouseActivity.this, null, getString(R.string.searching), true, true);
        }

        @Override
        protected void onPostExecute(Void result) {
            super.onPostExecute(result);
            if (mServerAddr != null) {
                mLayoutNoServer.setVisibility(View.GONE);
                mLayoutMouse.setVisibility(View.VISIBLE);
            } else {
                mLayoutMouse.setVisibility(View.GONE);
                mLayoutNoServer.setVisibility(View.VISIBLE);
            }
            mDialog.dismiss();
        }

        @Override
        protected Void doInBackground(Void... params) {
            // Test mode
            // - specified server address

            if (TEST_MODE) {
                mServerAddr = new InetSocketAddress(TEST_SERVER_IP, SERVER_PORT);
                return null;
            }

            byte[] buf = new byte[2];
            InetAddress broadcastIP;
            try {
                broadcastIP = Inet4Address.getByName("255.255.255.255");
                try {
                    DatagramSocket socket = new DatagramSocket();
                    socket.setSoTimeout(8000);

                    DatagramPacket packet = new DatagramPacket(buf, buf.length, broadcastIP,SERVER_PORT);
                    buf[0] = PACKET_TYPE_DISCOVER;
                    buf[1] = PROTOCAL_VERSION;
                    for (int i = 0; i < 3; i++) {
                        socket.send(packet);
                    }
                    socket.receive(packet);
                    if (packet.getLength() == 1) {
                        byte[] buffer = packet.getData();
                        if (buffer[0] != PACKET_TYPE_REPLY) {
                            mServerAddr = null;
                            return null;
                        }
                    } else {
                        mServerAddr = null;
                        return null;
                    }
                    mServerAddr = packet.getSocketAddress();
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } catch (UnknownHostException e1) {
                e1.printStackTrace();
            }
            return null;
        }
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createContentView();

        PackageInfo pinfo;
        String versionName = "Unknown";
        try {
            pinfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = pinfo.versionName;
        } catch (NameNotFoundException e1) {
        }
        String aboutContent = getText(R.string.about_content).toString();
        AlertDialog.Builder builder = new Builder(this);
        builder.setTitle(R.string.about);
        builder.setMessage(String.format(aboutContent, versionName));
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        mAboutDialog = builder.create();

        HandlerThread thread = new HandlerThread("Socket Thread");
        thread.start();

        mSocketHandler = new CommandHandler(thread.getLooper());

        try {
            mSocketChannel = DatagramChannel.open();
            mSocketChannel.configureBlocking(false);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.about:
            showAboutDialog();
            return true;
        case R.id.exit:
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAboutDialog() {
        mAboutDialog.show();
    }

    private void createContentView() {
        setContentView(R.layout.main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mBtnRetry = (Button) findViewById(R.id.btn_retry);
        mBtnManual = (Button) findViewById(R.id.btn_manual);
        mBtnLeft = (View) findViewById(R.id.btn_mouse_left);
        mBtnRight = (View) findViewById(R.id.btn_mouse_right);
        mTbSwitch = (ToggleButton) findViewById(R.id.tb_switch);
        mLayoutMouse = (ViewGroup) findViewById(R.id.layout_mouse);
        mLayoutNoServer = (ViewGroup) findViewById(R.id.layout_no_server);
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        mTbSwitch.setChecked(false);
        mLayoutNoServer.setVisibility(View.GONE);
        mLayoutMouse.setVisibility(View.GONE);
        mBtnRetry.setOnClickListener(this);
        mBtnManual.setOnClickListener(this);
        mBtnLeft.setOnTouchListener(this);
        mBtnRight.setOnTouchListener(this);

        chooseSensor();
    }

    // GRAVITY Sensor
    private void chooseSensor() {
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        mEpsilon = EPSILON_GRAVITY;
        if (mSensor == null) {
            mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mEpsilon = EPSILON_ACCELEROMETER;//Acce measure rate of change in velicity
        }
    }

    @Override
    protected void onDestroy() {
        try {
            mSocketChannel.close();
        } catch (IOException e) {
        }
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        new DiscoverTask().execute();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPausing = false;
        mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPausing = true;
        mSensorManager.unregisterListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mTbSwitch.setChecked(false);
        mLayoutMouse.setVisibility(View.GONE);
        mLayoutNoServer.setVisibility(View.GONE);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        boolean valuesUpdated = false;
        float values[] = event.values;


        float newX = -values[0];


        float newY = -values[1];

        if (mTbSwitch.isChecked()) {

            float deltaX = 0f;
            float deltaY = 0f;
            if (Math.abs(newX - mLastX) > mEpsilon) {
                deltaX = newX - mLastX;
                mLastX = newX;
                valuesUpdated = true;
            }
            if (Math.abs(newY - mLastY) > mEpsilon) {
                deltaY = newY - mLastY;
                mLastY = newY;
                valuesUpdated = true;
            }
            if (valuesUpdated) {
                float pos[] = {
                        deltaX,
                        deltaY };
                Message msg = mSocketHandler.obtainMessage(MSG_MOVE_COMMAND, pos);
                mSocketHandler.sendMessage(msg);
            }
        } else {
            mLastX = newX;
            mLastY = newY;
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (mTbSwitch.isChecked()) {
            Message msg = mSocketHandler.obtainMessage();
            int action = event.getAction();

            switch (v.getId()) {
            case R.id.btn_mouse_left:
                msg.arg1 = PACKET_MOUSE_BUTTON_LEFT;
                break;
            case R.id.btn_mouse_right:
                msg.arg1 = PACKET_MOUSE_BUTTON_RIGHT;
                break;
            default:
                return false;
            }

            switch (action) {
            case MotionEvent.ACTION_DOWN:
                msg.what = MSG_PRESS_COMMAND;
                mSocketHandler.sendMessage(msg);
                break;
            case MotionEvent.ACTION_UP:
                msg.what = MSG_RELEASE_COMMAND;
                mSocketHandler.sendMessage(msg);
                break;
            }
        }
        return false;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
        case R.id.btn_retry:
            new DiscoverTask().execute();
            break;
        case R.id.btn_manual:
            Builder builder = new Builder(this);
            builder.setTitle("IP");
            mEtIpAddr = new EditText(this);
            mEtIpAddr.setKeyListener(new NumberKeyListener() {
                public int getInputType() {
                    return InputType.TYPE_CLASS_NUMBER;
                }

                protected char[] getAcceptedChars() {
                    return new char[] {
                            '0',
                            '1',
                            '2',
                            '3',
                            '4',
                            '5',
                            '6',
                            '7',
                            '8',
                            '9',
                            '.' };
                }
            });
            builder.setView(mEtIpAddr);
            builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    String ip = mEtIpAddr.getText().toString();
                    if (ip.equals("")) {
                        return;
                    }
                    try {
                        mServerAddr = new InetSocketAddress(Inet4Address.getByName(ip),
                                SERVER_PORT);
                        dialog.dismiss();
                        mLayoutNoServer.setVisibility(View.GONE);
                        mLayoutMouse.setVisibility(View.VISIBLE);
                    } catch (UnknownHostException e) {
                        Toast.makeText(MouseActivity.this, R.string.invalid_addr,
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            builder.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });
            builder.show();
            // mLayoutMouse.setVisibility(View.GONE);
            // mLayoutNoServer.setVisibility(View.VISIBLE);
            break;
        }
    }
}