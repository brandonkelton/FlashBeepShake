package com.example.flashbeepshake

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.app.ActivityCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.util.*

class MainActivity : AppCompatActivity() {
    private var isLightOn = false
    private var isBeepOn = false
    private var isVibrateOn = false
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var interactionType = InteractionType.LOCAL
    private var myUUID: UUID? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var server: AcceptThread? = null
    private var client: ConnectThread? = null
    private var clientHasPaired = false
    private val mPREFIX = "Galaxy Note9"

    private val mReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(LOG_TAG, "BroadcastReceiver onReceive()")
            handleBTDevice(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        myUUID = UUID.fromString(MY_UUID_STRING)
        setContentView(R.layout.activity_main)
        textView_bluetoothStatus.movementMethod = ScrollingMovementMethod()
        setInteractionType(InteractionType.LOCAL)
        setupListeners()
    }

    private fun setInteractionType(interactionType: InteractionType) {
        this.interactionType = interactionType
        when (interactionType) {
            InteractionType.LOCAL -> {
                button_vibrate.isEnabled = true
                button_light.isEnabled = true
                button_beep.isEnabled = true
                textView_currentSystem.text = "Local"
            }
            InteractionType.SERVER -> {
                button_vibrate.isEnabled = false
                button_light.isEnabled = false
                button_beep.isEnabled = false
                textView_currentSystem.text = "Server Receives Requests"
            }
            InteractionType.CLIENT -> {
                button_vibrate.isEnabled = true
                button_light.isEnabled = true
                button_beep.isEnabled = true
                textView_currentSystem.text = "Client Sends Requests"
            }
        }
    }

    private fun setupListeners() {
        button_beServer.setOnClickListener {
            setInteractionType(InteractionType.SERVER)
            Log.i(TSERVER, "Connect Button setting up server")
            textView_bluetoothStatus.append("Connect Button: setting up server\n")
            getPairedDevices()
            //make server discoverable for N_SECONDS
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, N_SECONDS)
            startActivity(discoverableIntent)
            //create server thread
            server = AcceptThread()
            if (server != null) {   //start server thread
                Log.i(TSERVER, "Connect Button spawning server thread")
                textView_bluetoothStatus.append("Connect Button: spawning server thread $server \n")
                server!!.start()     //calls AcceptThread's run() method
            } else {
                Log.i(TSERVER, "setupButtons(): server is null")
            }
        }
        button_beClient.setOnClickListener {
            setInteractionType(InteractionType.CLIENT)
            getPairedDevices()
            setUpBroadcastReceiver()
        }
        button_light.setOnClickListener {
            if (this.interactionType == InteractionType.LOCAL) {
                setLightState()
            } else if (this.interactionType == InteractionType.CLIENT) {
                client?.sendMessage("LIGHT")
            }
        }
        button_beep.setOnClickListener {
            if (this.interactionType == InteractionType.LOCAL) {
                setBeepState()
            } else if (this.interactionType == InteractionType.CLIENT) {
                client?.sendMessage("BEEP")
            }
        }
        button_vibrate.setOnClickListener {
            if (this.interactionType == InteractionType.LOCAL) {
                setVibrateState()
            } else if (this.interactionType == InteractionType.CLIENT) {
                client?.sendMessage("VIBRATE")
            }
        }
    }

    private fun setLightState() {
        isLightOn = !isLightOn
        button_light.text = if (isLightOn) resources.getString(R.string.button_lightOff) else resources.getString(R.string.button_lightOn)
        setLightStatus(isLightOn)
    }

    private fun setBeepState() {
        isBeepOn = !isBeepOn
        button_beep.text = if (isBeepOn) resources.getString(R.string.button_beepOff) else resources.getString(R.string.button_beepOn)
        setBeepStatus(isBeepOn)
    }

    private fun setVibrateState() {
        isVibrateOn = !isVibrateOn
        button_vibrate.text = if (isVibrateOn) resources.getString(R.string.button_vibrateOff) else resources.getString(R.string.button_vibrateOn)
        setVibrateStatus(isVibrateOn)
    }

    public override fun onResume() {
        super.onResume()
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        Log.i(LOG_TAG, "onResume()")
        if (mBluetoothAdapter == null) {
            // Device does not support Bluetooth
            Log.i(LOG_TAG, "No Bluetooth on this device")
            Toast.makeText(baseContext,
                "No Bluetooth on this device", Toast.LENGTH_LONG).show()
        } else if (!mBluetoothAdapter!!.isEnabled) {
            Log.i(LOG_TAG, "enabling Bluetooth")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        }
        textView_bluetoothStatus.append("This device is:  ${mBluetoothAdapter?.name} \n")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.i(LOG_TAG, "onActivityResult(): requestCode = $requestCode")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                Log.i(LOG_TAG, "  --    Bluetooth is enabled")
                getPairedDevices() //find already known paired devices
                setUpBroadcastReceiver()
            }
        }
    }

    private fun getPairedDevices() {//find already known paired devices
        val pairedDevices = mBluetoothAdapter!!.bondedDevices
        Log.i(LOG_TAG, "--------------------------\ngetPairedDevices() - Known Paired Devices")
        // If there are paired devices
        if (pairedDevices.size > 0) {
            for (device in pairedDevices) {
                Log.i(LOG_TAG, device.name + "\n" + device)
                textView_bluetoothStatus.append("" + device.name + "\n" + device + "\n")
                if (!clientHasPaired && tryToPair(device, device.name)) {
                    clientHasPaired = true
                    break
                }
            }
        }
        Log.i(LOG_TAG, "getPairedDevices() - End of Known Paired Devices\n-------------------------")
    }

    /**
     * Client scans for nearby Bluetooth devices
     */
    private fun setUpBroadcastReceiver() {
        // Create a BroadcastReceiver for ACTION_FOUND
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED)    {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                ACCESS_FINE_LOCATION)
            Log.i(TCLIENT,"Getting Permission")
            return
            //Discovery will be setup in onRequestPermissionResult() if permission is granted
        }
        setupDiscovery()
    }

    /**
     * Callback when request for permission is addressed by the user.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            ACCESS_FINE_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(LOG_TAG, "Fine_Location Permission granted")
                    setupDiscovery()
                } else {    //tracking won't happen since user denied permission
                    Log.i(LOG_TAG, "Fine_Location Permission refused")
                }
                return
            }
        }
    }

    /**
     * Activate Bluetooth discovery for the client
     */
    private fun setupDiscovery() {
        Log.i(TCLIENT,"Activating Discovery")
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(mReceiver, filter)
        mBluetoothAdapter!!.startDiscovery()
    }

    /**
     * called by BroadcastReceiver callback when a new BlueTooth device is found
     */
    private fun handleBTDevice(intent: Intent) {
        Log.i(TCLIENT, "handleBRDevice() -- starting   <<<<--------------------")
        val action = intent.action
        // When discovery finds a device
        if (BluetoothDevice.ACTION_FOUND == action) {
            // Get the BluetoothDevice object from the Intent
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val deviceName =
                if (device?.name != null) {
                    device.name.toString()
                } else {
                    //"--Unnamed device found - should ignore this device--"
                    return      //ignoring this device
                }
            Log.i(TCLIENT, deviceName + "\n" + device)
            textView_bluetoothStatus.append("$deviceName, $device \n")
            if (!clientHasPaired) clientHasPaired =  tryToPair(device, deviceName)
        }
    }

    private fun tryToPair(device:BluetoothDevice, who:String) : Boolean {
        // The following is specific to this App for the client
        if (who.length >= 12) { //for now, looking for MSU prefix
            val prefix = who.subSequence(0,12)
            textView_bluetoothStatus.append("Prefix = $prefix\n    ")
            if (prefix == mPREFIX) {//This is the server
                Log.i(LOG_TAG,"Canceling Discovery")
                mBluetoothAdapter!!.cancelDiscovery()
                Log.i(LOG_TAG,"Connecting")
                client = ConnectThread(device)  //FIX** remember and reconnect if interrupted?
                Log.i(LOG_TAG,"Running Connect Thread")
                client?.start()
                return true
            }
        }
        return false
    }

    override fun onStop() {
        super.onStop()
        mBluetoothAdapter!!.cancelDiscovery()    //stop looking for Bluetooth devices
        client?.cancel()
    }

    /**
     * Called from server thread to display received message.
     * This action is specific to this App.
     * @param msg The received info to display
     */
    fun echoMsg(nBytes: Int, msg: String) {
        textView_bluetoothStatus.append("\nReceived $nBytes:  [$msg]\n")
        when (msg) {
            "LIGHT" -> {
                setLightState()
                textView_bluetoothStatus.append("LIGHT INTERACTION")
            }
            "VIBRATE" -> {
                setVibrateState()
                textView_bluetoothStatus.append("VIBRATE INTERACTION")
            }
            "BEEP" -> {
                setBeepState()
                textView_bluetoothStatus.append("BEEP INTERACTION")
            }
        }
    }

    ///////////////////////////////////// Client Thread to talk to Server here //////////////////////

    private inner class ConnectThread(mmDevice: BluetoothDevice) : Thread() { //from android developer
        private var mmSocket: BluetoothSocket? = null

        init {
            // Get a BluetoothSocket to connect with the given BluetoothDevice
            Log.i(TCLIENT, "ConnectThread: init()")
            try {
                // myUUID is the app's UUID string, also used by the server code
                mmSocket = mmDevice.createRfcommSocketToServiceRecord(myUUID)
            } catch (e: IOException) {
                Log.i(TCLIENT, "IOException when creating RFcommSocket\n $e")
            }
        }

        override fun run() {
            // Cancel discovery because it will slow down the connection
            Log.i(TCLIENT, "ConnectThread: run()")
            Log.i(TCLIENT, "in ClientThread - Canceling Discovery")
            mBluetoothAdapter!!.cancelDiscovery()
            if (mmSocket == null) {
                runOnUiThread { echoMsg(0, "mmSocket is null!") }
                Log.e(TCLIENT,"ConnectThread:run(): mmSocket is null")
                return
            }
            try {
                // Connect the device through the socket. This will block
                // until it succeeds or throws an exception after 12 seconds (or so)
                val who:String = mmSocket!!.remoteDevice.name
                Log.i(TCLIENT, "Connecting to server: $who")
                mmSocket!!.connect()
            } catch (connectException: IOException) {
                Log.i(TCLIENT, "Connect IOException when trying socket connection\n $connectException")
                runOnUiThread { echoMsg(0, connectException.message!!)}
                // Unable to connect; close the socket and get out
                try {
                    Log.i(TCLIENT, "Closing the connection\n $connectException")
                    mmSocket!!.close()
                } catch (closeException: IOException) {
                    Log.i(TCLIENT, "Close IOException when trying socket connection\n $closeException")
                }
                Log.i(TCLIENT, "Client Thread run() returning having failed")

                return
            }
            Log.i(TCLIENT, "Connection Established")
        }

        fun sendMessage(message:String) {
            val out: OutputStream
            val msg = message.toByteArray()
            try {
                Log.i(TCLIENT, "In sendMessage: sending the message: [$message]")
                out = mmSocket!!.outputStream
                out.write(msg)
            } catch (ioe: IOException) {
                Log.e(TCLIENT, "IOException when opening outputStream\n $ioe")
                runOnUiThread { echoMsg(0, ioe.message!!) }
                return
            }

            // cancel() //done, so close the socket - might kill the conversation and nothing is sent
        }

        /**
         * Will cancel an in-progress connection, and close the socket
         */
        fun cancel() {
            try {
                mmSocket!!.close()
            } catch (ioe: IOException) {
                Log.e(TCLIENT, "IOException when closing outputStream\n $ioe")
            }
        }
    }


    /////////////////////////////////////  ServerSocket stuff here ///////////////////////////

    private inner class AcceptThread : Thread() {  //from android developer
        private var mmServerSocket: BluetoothServerSocket? = null

        init {
            // Use a temporary object that is later assigned to mmServerSocket,
            // because mmServerSocket is supposed to be final
            val tmp: BluetoothServerSocket
            try {
                // myUUID is the app's UUID string, also used by the client code
                tmp = mBluetoothAdapter!!.listenUsingRfcommWithServiceRecord(SERVICE_NAME, myUUID)
                Log.i(TSERVER, "AcceptThread registered the server\n")
                mmServerSocket = tmp
            } catch (e: IOException) {
                Log.e(TSERVER, "AcceptThread registering the server failed\n $e")
            }
        }

        override fun run() {
            var socket: BluetoothSocket?
            // Keep listening until exception occurs or a socket is returned
            while (true) {
                Log.i(TSERVER, "AcceptTread.run(): Server Looking for a Connection")
                runOnUiThread { echoMsg(0, "Calling accept again") }
                try {
                    socket = mmServerSocket!!.accept()  //blocks until connection made or exception
                    Log.i(TSERVER, "Server socket accepting a connection")
                } catch (e: IOException) {
                    Log.e(TSERVER, "socket accept threw an exception\n $e")
                    runOnUiThread { echoMsg(0, e.message!!) }
                    break
                }

                // If a connection was accepted
                if (socket != null) {
                    Log.i(TSERVER, "Server Thread run(): Connection accepted")
                    // Do work to manage the connection (in a separate thread)
                    manageConnectedSocket(socket)
                    break
                } else {
                    Log.i(TSERVER, "Server Thread run(): The socket is null")
                    runOnUiThread { echoMsg(0, "Socket is NULL") }
                }
            }
        }

        //manage the Server's end of the conversation on the passed-in socket
        fun manageConnectedSocket(socket: BluetoothSocket) {
            Log.i(TSERVER, "\nManaging the Socket\n")
            while (true) {
                val inSt: InputStream
                val nBytes: Int
                val msg = ByteArray(255) //arbitrary size
                try {
                    inSt = socket.inputStream
                    nBytes = inSt.read(msg)
                    Log.i(TSERVER, "\nServer Received $nBytes \n")
                } catch (ioe: IOException) {
                    Log.e(TSERVER, "IOException when opening inputStream\n $ioe")
                    return
                }

                try {
                    val msgString = msg.toString(Charsets.UTF_8).substring(0, nBytes)
                    Log.i(TSERVER, "\nServer Received  $nBytes, Bytes:  [$msgString]\n")
                    runOnUiThread { echoMsg(nBytes, msgString) }
                } catch (uee: UnsupportedEncodingException) {
                    runOnUiThread { echoMsg(0, uee.message!! )}
                    Log.e(
                        TSERVER,
                        "UnsupportedEncodingException when converting bytes to String\n $uee"
                    )
                } catch (e: Exception) {
                    runOnUiThread { echoMsg(0, e.message!! )}
                    Log.e(
                        TSERVER,
                        e.message
                    )
                } finally {
                    // cancel()        //for this App - close() after 1 (or no) message received
                }
            }
        }

        /**
         * Will cancel the listening socket, and cause the thread to finish
         */
        fun cancel() {
            try {
                mmServerSocket!!.close()
            } catch (ioe: IOException) {
                Log.e(TSERVER, "IOException when canceling serverSocket\n $ioe")
            }
        }
    }

    private fun setLightStatus(isLightOn: Boolean) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0]
        cameraManager.setTorchMode(cameraId, isLightOn)
    }

    private fun setBeepStatus(isBeepOn: Boolean) {
        if (tone == null) {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        }
        if (isBeepOn) {
            tone!!.startTone(ToneGenerator.TONE_DTMF_S)
        } else {
            tone!!.stopTone()
        }
    }

    private fun setVibrateStatus(isVibrateOn: Boolean) {
        if (vibrator == null) {
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (isVibrateOn) {
            if (Build.VERSION.SDK_INT >= 26) {
                val pattern = longArrayOf(0, 200, 100, 200, 100, 500, 200, 200, 100, 200, 100, 500, 200, 200, 100, 200, 100, 200, 100, 200, 100, 700, 500)
                val effect = VibrationEffect.createWaveform(pattern, 5)
                // vibrator!!.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                vibrator!!.vibrate(effect)
            } else {
                vibrator!!.vibrate(60000)
            }
        } else {
            vibrator!!.cancel()
        }
    }

    companion object {
        private const val ACCESS_FINE_LOCATION = 1
        private const val N_SECONDS = 255
        private const val TCLIENT = "--Talker Client--"  //for Log.X
        private const val TSERVER = "--Talker SERVER--"  //for Log.X
        private const val REQUEST_ENABLE_BT = 3313  //our own code used with Intents
        private const val MY_UUID_STRING = "ed47697a-87e6-11ea-bc55-0242ac130003" //from www.uuidgenerator.net
        private const val SERVICE_NAME = "Talker"
        private const val LOG_TAG = "--Talker----"
    }
}
