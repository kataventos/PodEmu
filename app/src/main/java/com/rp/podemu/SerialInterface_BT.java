/**

 Copyright (C) 2015, Roman P., dev.roman [at] gmail

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program. If not, see http://www.gnu.org/licenses/

 */

package com.rp.podemu;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Created by rp on 07/22/2017.
 */

public class SerialInterface_BT extends SerialInterface_Common implements SerialInterface
{
    private BluetoothDevice btDevice;
    private static Context baseContext;
    private Activity baseActivity;
    private static SharedPreferences sharedPref;


    public final static String BTDEV_NAME_DEFAULT = "BT device not set";
    public final static String BTDEV_ADDRESS_DEFAULT = "unknown";
    public final static UUID BTDEV_UUID[]=
            { UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")//,
            //  UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
            //  UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
            };
    public static int btdev_uuid_idx = 0;
    private static final int REQUEST_DISCOVERABLE_BT = 0x1e;
    private static final int BUFFER_SIZE = 1024;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;
    public static final int STATE_SEARCHING = 1;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_CONNECTED = 3;
    public static final int STATE_LOST = 4;
    public static final int STATE_FAILED = 5;

    private final BluetoothServerSocket btServerSocket;
    private final BluetoothAdapter btAdapter;
    //private BluetoothSocket btSocket;
    private int btState;
    private Handler mHandler = null;

    //private AcceptThread mAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;

    private boolean isBtAskingDiscoverability = false;
    private static SerialInterface_BT serialInterfaceBTInstance = null;

    // timeout after which interface will be closed if not connected (ms)
    public final static int BT_CONNECT_TIMEOUT = 1500;

    private static SharedPreferences getSharedPref(Context context)
    {
        if(sharedPref == null)
            sharedPref = context.getSharedPreferences("PODEMU_PREFS", Context.MODE_PRIVATE);
        return sharedPref;
    }


    public static synchronized SerialInterface_BT getInstance(Context context)
    {
        baseContext = context;
        return getInstance();
    }

    public static synchronized SerialInterface_BT getInstance()
    {
        int bluetoothEnabled=getSharedPref(baseContext).getInt("bluetoothEnabled", 0);
        if(bluetoothEnabled==0)
        {
            serialInterfaceBTInstance = null;
        }
        else if(serialInterfaceBTInstance == null)
        {
            PodEmuLog.debug("SIBT: initializing new instance");
            serialInterfaceBTInstance = new SerialInterface_BT();
        }

        return serialInterfaceBTInstance;
    }

    public void setHandler(Handler handler)
    {
        mHandler = handler;
    }



    public SerialInterface_BT ()
    {
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null)
        {
            // Device does not support Bluetooth
            PodEmuLog.debug("SIBT: device does not support Bluetooth.");
            btServerSocket = null;
            return;
        }
        if (!btAdapter.isEnabled())
        {
            // Bluetooth is still disabled :(
            PodEmuLog.debug("SIBT: Bluetooth is disabled. Not trying to turn it on though...");
            btServerSocket = null;
            return;
        }

        // Use a temporary object that is later assigned to mmServerSocket
        // because mmServerSocket is final.
        BluetoothServerSocket tmp = null;
        try
        {
            PodEmuLog.debug("SIBT: SerialInterface_BT connecting with btdev_uuid_idx=0");
            tmp = btAdapter.listenUsingRfcommWithServiceRecord(getName(), BTDEV_UUID[0]);
        }
        catch (IOException e)
        {
            PodEmuLog.debug("SIBT: Socket's listen() method failed");
            PodEmuLog.printStackTrace(e);

        }
        btServerSocket = tmp;
        btState = STATE_NONE;
    }

    public synchronized boolean init(Context context)
    {
        boolean podEmuBTFound = false;
        baseContext = context;

        if(context instanceof MainActivity)
        {
            setHandler(((MainActivity) context).mHandler);
        }

        btdev_uuid_idx = (btdev_uuid_idx+1) % BTDEV_UUID.length;

        PodEmuLog.debug("SIBT: Bluetooth initialization started with btdev_uuid_idx=" + btdev_uuid_idx);
        ensureDiscoverable();

        if(mConnectedThread!=null)
        {
            PodEmuLog.debug("SIBT: mConnectedThread active. Aborting init.");
            return false;
        }

        if(mConnectThread!=null)
        {
            PodEmuLog.debug("SIBT: mConnectThread active. Aborting init.");
            return false;
        }

        /*
        if(mAcceptThread!=null)
        {
            PodEmuLog.debug("SIBT: mAcceptThread active. Aborting init.");
            return false;
        }
        */

        if(btAdapter == null)
        {
            PodEmuLog.error("SIBT: mBluetoothAdapter is not initialized. Aborting...");
            return false;
        }
        if(btServerSocket == null)
        {
            PodEmuLog.error("SIBT: mmServerSocket is not initialized. Aborting...");
            return false;
        }

        Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();

        if (pairedDevices.size() > 0)
        {
            // There are paired devices. Get the name and address of each paired device.
            for (BluetoothDevice device : pairedDevices)
            {
                String deviceName = device.getName();
                String deviceHardwareAddress = device.getAddress(); // MAC address

                PodEmuLog.debugVerbose("SIBT: paired device found: " + deviceName + " (" + deviceHardwareAddress + ")");

                if(deviceName.equals(getName()))
                {
                    podEmuBTFound = true;
                    btDevice = device;
                }

            }
        }

        if(!podEmuBTFound)
        {
            PodEmuLog.debug("SIBT: bluetooth device not found. Exiting...");
            return false;
        }

        PodEmuLog.debug("SIBT: bluetooth device found: " + btDevice.getName() + " (" + btDevice.getAddress() + "). Trying to connect...");

        //BluetoothDevice actualBtDevice = btAdapter.getRemoteDevice(btDevice.getAddress());
        connect(btDevice);

        /*
        // give background threads time to actually establish the connection
        try
        {
            Thread.sleep(BT_CONNECT_TIMEOUT + 150);
        }
        catch(java.lang.InterruptedException e)
        {
            // do nothing

        }
    */
        try
        {
            wait();
        }
        catch(InterruptedException e)
        {
            PodEmuLog.debug("SIBLE: connection wait interrupted.");
        }

        PodEmuLog.debug("SIBT: init finishing with status: " + isConnected());

        return isConnected();
    }

    public int write(byte[] buffer, int numBytes)
    {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this)
        {
            if (btState != STATE_CONNECTED) return -1;
            r = mConnectedThread;
        }
        // Perform the write asynchronously
        r.write(buffer, numBytes);

        return numBytes;
    }

    public int read(byte[] buffer)
    {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this)
        {
            if (btState != STATE_CONNECTED) return -1;
            r = mConnectedThread;
        }
        return r.read(buffer);
    }

    public String readString()
    {
        // FIXME
        return "";
    }

    public String getName()
    {
        return getSharedPref(baseContext).getString("bluetoothDeviceName", SerialInterface_BT.BTDEV_NAME_DEFAULT);
    }

    public String getAddress()
    {
        return getSharedPref(baseContext).getString("bluetoothDeviceAddress", SerialInterface_BT.BTDEV_ADDRESS_DEFAULT);
    }

    public int getVID()
    {
        return 0;
    }

    public int getPID()
    {
        return 0;
    }

    public void setBaudRate(int rate)
    {
        // FIXME:
        return;
    }

    public int getBaudRate()
    {
        // FIXME
        return 57600;
    }


    public boolean isConnecting()
    {
        return false;
    }


    public boolean isConnected()
    {
        return (btState == STATE_CONNECTED);
    }

    public int getReadBufferSize()
    {
        return BUFFER_SIZE;
    }

    public synchronized void close()
    {
        PodEmuLog.debug("SIBT: close requested");
        if (mConnectThread != null)
        {
            mConnectThread.cancel();
            PodEmuLog.debug("SIBT: close() resetting mConnectThread");
            mConnectThread = null;
        }
        if (mConnectedThread != null)
        {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        /*
        if (mAcceptThread != null)
        {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }
        */

        setState(STATE_NONE);
    }

    public void restart()
    {
        close();
        init(baseContext);
    }


    static public String getStateName(int state)
    {
        switch (state)
        {
            case STATE_CONNECTED: return "CONNECTED";
            case STATE_CONNECTING: return "CONNECTING";
            case STATE_LOST: return "LOST";
            case STATE_FAILED: return "FAILED";
            case STATE_SEARCHING: return "SEARCHING";
            case STATE_NONE:
            default:
                return "NONE";

        }
    }

    /**
     * Set the current state of the chat connection
     * @param state  An integer defining the current connection state
     */
    private synchronized void setState(int state) 
    {
        PodEmuLog.debug("SIBT: setState() " + getStateName(btState) + " -> " + getStateName(state));
        btState = state;
        //mHandler.obtainMessage(MainActivity.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();

        PodEmuService.communicateSerialStatusChange();
    }
    public synchronized int getState()
    {
        return btState;
    }
    
    private synchronized void ensureDiscoverable()
    {
        if(this.isBtAskingDiscoverability) return;

        //if(!btAdapter.isEnabled() )// ||
        //        ((statusBT != STATUS_DISCOVERABLE) && (statusBT != STATUS_CONNECTED)))
        if(btAdapter!=null && btAdapter.isEnabled())
        {
            PodEmuLog.debug("SIBT: Bluetooth adapter is enabled");
            if(btAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE)
            {
                PodEmuLog.debug("SIBT: Bluetooth discovery turned off. Turning on...");
                this.isBtAskingDiscoverability = true;
                Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                //discoverableIntent.putExtra("android.bluetooth.adapter.extra.DISCOVERABLE_DURATION", mPC.time_to_discoverable);
            }
            else
            {
                PodEmuLog.debug("SIBT: Bluetooth discovery is on.");
                //if(mBtService == null)
                //    initService();
            }
        }
        /*else
        {
            PodEmuLog.debug("SIBT: Bluetooth adapter is not enabled");
            //if(mBtService == null)
            //    initService();
        }*/
    }

    private void connectionFailed()
    {
        setState(STATE_FAILED);
    }

    private void connectionLost()
    {
        setState(STATE_LOST);
    }

    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device)
    {
        PodEmuLog.debug("SIBT: new BT communication started");
        if (mConnectThread != null)
        {
            mConnectThread.cancel();
            PodEmuLog.debug("SIBT: connected() resetting mConnectThread");
            mConnectThread = null;
        }
        if (mConnectedThread != null)
        {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        /*
        if (mAcceptThread != null)
        {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }
        */

        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();


        /*
        Message msg = mHandler.obtainMessage(MainActivity.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(MainActivity.DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        */
        setState(STATE_CONNECTED);
        synchronized (this)
        {
            notifyAll();
        }
    }

    /**
     * Start the chat service. Specifically start AcceptThread to begin a
     * session in listening (server) mode. Called by the Activity onResume() */
    public synchronized void start()
    {
        PodEmuLog.debug("SIBT: start()");

        if (mConnectThread != null)
        {
            mConnectThread.cancel();
            PodEmuLog.debug("SIBT: start() resetting mConnectThread");
            mConnectThread = null;
            connect(btDevice);
        }

        /*
        if (mConnectedThread != null)
        {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mAcceptThread == null)
        {
            mAcceptThread = new AcceptThread();
            mAcceptThread.start();
        }


        setState(STATE_SEARCHING);

        */
    }


    public synchronized void connect(BluetoothDevice device)
    {
        PodEmuLog.debug("SIBT: trying to connect to: " + device);
        if (btState == STATE_CONNECTING)
        {
            if (mConnectThread != null)
            {
                mConnectThread.cancel();
                PodEmuLog.debug("SIBT: connect() resetting mConnectThread");
                mConnectThread = null;
            }
        }
        if (mConnectedThread != null)
        {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }



    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */

    private class AcceptThread extends Thread
    {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread()
        {
            BluetoothServerSocket tmp = null;

            // Create a new listening server socket
            try
            {
                PodEmuLog.debug("SIBT: AcceptThread connecting with btdev_uuid_idx=" + btdev_uuid_idx);
                tmp = btAdapter.listenUsingRfcommWithServiceRecord(getName(), BTDEV_UUID[btdev_uuid_idx]);
            }
            catch (IOException e)
            {
                PodEmuLog.debug("SIBT: AcceptThread listen() failed");
                PodEmuLog.printStackTrace(e);
            }
            mmServerSocket = tmp;
        }

        public void run() 
        {
            PodEmuLog.debug("SIBT: BEGIN AcceptThread " + this);
            setName("SIBT: AcceptThread");
            BluetoothSocket socket = null;

            // Listen to the server socket if we're not connected
            while (btState != STATE_CONNECTED)
            {
                try
                {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket.accept();
                    PodEmuLog.debug("SIBT: AcceptThread Connection Accepted");
                }
                catch (IOException e)
                {
                    PodEmuLog.debug("SIBT: AcceptThread accept() failed");
                    //PodEmuLog.printStackTrace(e);
                    break;
                }

                // If a connection was accepted
                if (socket != null) 
                {
                    synchronized (SerialInterface_BT.this) 
                    {
                        PodEmuLog.debug("SIBT: AcceptThread connection accepted, State: " + btState);
                        switch (btState)
                        {
                            case STATE_SEARCHING:
                            case STATE_CONNECTING:
                                // Situation normal. Start the connected thread.
                                PodEmuLog.debug("SIBT: AcceptThread starting mConnectedThread.");
                                connected(socket, socket.getRemoteDevice());
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                // Either not ready or already connected. Terminate new socket.
                                PodEmuLog.debug("SIBT: AcceptThread Either not ready or already connected. Terminate new socket.");
                                try
                                {
                                    socket.close();
                                    PodEmuLog.debug("SIBT: AcceptThread close() successfull.");
                                }
                                catch (IOException e)
                                {
                                    PodEmuLog.debug("SIBT: AcceptThread could not close unwanted socket");
                                    //PodEmuLog.printStackTrace(e);
                                }
                                break;
                        }
                    }
                }
            }

            PodEmuLog.debug("SIBT: AcceptThread END");
        }

        public void cancel()
        {
            PodEmuLog.debug("SIBT: cancel " + this);
            try
            {
                mmServerSocket.close();
            }
            catch (IOException e)
            {
                PodEmuLog.debug("SIBT: AcceptThread close() or server failed");
                //PodEmuLog.printStackTrace(e);
            }
        }
    }


    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread
    {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device)
        {
            mmDevice = device;
            BluetoothSocket tmp = null;

            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try
            {
                PodEmuLog.debug("SIBT: ConnectThread connecting with btdev_uuid_idx=" + btdev_uuid_idx);
                setState(STATE_CONNECTING);
                tmp = device.createRfcommSocketToServiceRecord(BTDEV_UUID[btdev_uuid_idx]);
            }
            catch (IOException e)
            {
                PodEmuLog.debug("SIBT: ConnectThread createRfcomm() failed");
                PodEmuLog.printStackTrace(e);
            }
            mmSocket = tmp;
        }

        public void run()
        {
            PodEmuLog.debug("SIBT: BEGIN ConnectThread");
            setName("SIBT: ConnectThread");

            // Always cancel discovery because it will slow down a connection
            btAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try
            {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                PodEmuLog.debug("SIBT: ConnectThread - establishing BT connections started");
                mmSocket.connect();
            }
            catch (IOException e)
            {
                PodEmuLog.debug("SIBT: ConnectThread - connection failed");
                connectionFailed();
                // Close the socket
                try
                {
                    mmSocket.close();
                    PodEmuLog.debug("SIBT: ConnectThread - close() succesfull");
                }
                catch (IOException e2)
                {
                    PodEmuLog.debug("SIBT: ConnectThread - unable to close() socket during connection failure");
                    PodEmuLog.printStackTrace(e);
                }

                PodEmuLog.debug("SIBT: ConnectThread - trying to restart from");
                // Start the service over to restart listening mode
                SerialInterface_BT.this.start();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (SerialInterface_BT.this)
            {
                PodEmuLog.debug("SIBT: run() resetting mConnectThread");
                mConnectThread = null;
            }

            PodEmuLog.debug("SIBT: ConnectThread - BT connection established");
            // Start the connected thread
            connected(mmSocket, mmDevice);
        }

        public void cancel()
        {
            try
            {
                mmSocket.close();
            }
            catch (IOException e)
            {
                PodEmuLog.debug("SIBT: ConnectThread close() of connect socket failed");
                PodEmuLog.printStackTrace(e);
            }
        }
    }



    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket)
        {
            //PodEmuLog.debug("SIBT: ConnectedThread create ConnectedThread");
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try
            {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            }
            catch (IOException e)
            {
                PodEmuLog.debug("SIBT: ConnectedThread temp sockets not created");
                PodEmuLog.printStackTrace(e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run()
        {
            //PodEmuLog.debug("SIBT: ConnectedThread BEGIN (EMPTY)");
            /* RPP
            byte[] buffer = new byte[1024];
            int bytes;

            // Keep listening to the InputStream while connected

            while (true)
            {
                try
                {
                    // Read from the InputStream
                    //bytes = mmInStream.read(buffer);

                    // Send the obtained bytes to the UI Activity
                    mHandler.obtainMessage(MainActivity.MESSAGE_READ, bytes, -1, buffer)
                            .sendToTarget();
                }
                catch (IOException e)
                {
                    PodEmuLog.debug("SIBT: ConnectedThread disconnected");
                    PodEmuLog.printStackTrace(e);
                    connectionLost();
                    break;
                }
            }
            */
            //PodEmuLog.debug("SIBT: ConnectedThread END (EMPTY)");
        }
        /**
         * Write to the connected OutStream.
         * @param buffer  The bytes to write
         */
        public void write(byte[] buffer, int numBytes)
        {
            try
            {
                mmOutStream.write(buffer, 0, numBytes);
            }
            catch (IOException e)
            {
                PodEmuLog.debug("SIBT: ConnectedThread Exception during write");
                PodEmuLog.printStackTrace(e);
            }
        }

        /**
         * Read from the connected InputStream.
         * @param buffer: The buffer to read to
         * @return numBytes: number of bytes read
         *
         * WARNING: this function is blocking until at least one byte is read or EOF encountered
         */
        public int read(byte[] buffer)
        {
            int numBytes = -1;

            try
            {
                numBytes = mmInStream.read(buffer,0,BUFFER_SIZE);
            }
            catch (IOException e)
            {
                PodEmuLog.debug("SIBT: ConnectedThread Exception during read");
                // PodEmuLog.printStackTrace(e);
            }
            return numBytes;
        }
        public void cancel()
        {
            try
            {
                mmSocket.close();
            }
            catch (IOException e)
            {
                PodEmuLog.debug("SIBT: ConnectedThread close() of connect socket failed");
                PodEmuLog.printStackTrace(e);
            }
        }
    }
}
