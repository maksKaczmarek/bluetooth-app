package com.example.bluetooth_app

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = "MicroBitBLEApp"

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleScanner: BluetoothLeScanner
    private val deviceList = mutableListOf<BluetoothDevice>()
    private lateinit var deviceListAdapter: ArrayAdapter<String>
    private lateinit var listView: ListView
    private lateinit var receivedMessagesTextView: TextView
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var recyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter

    private val SCAN_PERIOD: Long = 10000
    private val REQUEST_ENABLE_BT = 1
    private val PERMISSIONS_REQUEST_CODE = 2

    // UUIDs for UART service and characteristics
    private val UART_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val TX_CHARACTERISTIC_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val RX_CHARACTERISTIC_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Define permissions array
    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private lateinit var enableBluetoothLauncher: ActivityResultLauncher<Intent>
    private var isDataDisplayed = false
    private var isConnecting = false
    private var connectedDevice: BluetoothDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContentView(R.layout.activity_main)

        enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startScan()
            } else {
                Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_SHORT).show()
            }
        }

        try {
            listView = findViewById(R.id.deviceListView)
            receivedMessagesTextView = findViewById(R.id.receivedMessagesTextView)
            dbHelper = DatabaseHelper(this)
            recyclerView = findViewById(R.id.recyclerView)
            messageAdapter = MessageAdapter(mutableListOf())
            recyclerView.layoutManager = LinearLayoutManager(this)
            recyclerView.adapter = messageAdapter

            deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
            listView.adapter = deviceListAdapter

            listView.setOnItemClickListener { _, _, position, _ ->
                val device = deviceList[position]
                connectToDevice(device)
            }

            val scanButton: Button = findViewById(R.id.scanButton)
            scanButton.setOnClickListener {
                if (!hasPermissions()) {
                    requestPermissions()
                } else {
                    startScan()
                }
            }

            val resetButton: Button = findViewById(R.id.resetButton)
            resetButton.setOnClickListener {
                receivedMessagesTextView.text = ""
            }

            val displayButton: Button = findViewById(R.id.displayButton)
            displayButton.setOnClickListener {
                if (isDataDisplayed) {
                    hideDatabaseContents()
                } else {
                    displayDatabaseContents()
                }
                isDataDisplayed = !isDataDisplayed
            }

            val toggleDevicesButton: ToggleButton = findViewById(R.id.toggleDevicesButton)
            toggleDevicesButton.setOnCheckedChangeListener { _, isChecked ->
                listView.visibility = if (isChecked) View.VISIBLE else View.GONE
            }

            if (!hasPermissions()) {
                requestPermissions()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing views: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun hasPermissions(): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, permissions, PERMISSIONS_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScan()
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            if (!bluetoothAdapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(enableBtIntent)
            } else {
                bleScanner = bluetoothAdapter.bluetoothLeScanner
                deviceList.clear()
                deviceListAdapter.clear()
                scanLeDevice(true)
            }
        } else {
            requestPermissions()
        }
    }

    private fun scanLeDevice(enable: Boolean) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (enable) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bleScanner.stopScan(leScanCallback)
                }
            }, SCAN_PERIOD)
            bleScanner.startScan(leScanCallback)
        } else {
            bleScanner.stopScan(leScanCallback)
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
                val deviceName = device.name ?: "Unknown"
                if (!deviceList.contains(device) && deviceName.contains("micro:bit", true)) {
                    deviceList.add(device)
                    deviceListAdapter.add("$deviceName\n${device.address}")
                    deviceListAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (connectedDevice == device) {
            // Disconnect the device
            bluetoothGatt?.disconnect()
            connectedDevice = null
            isConnecting = false
            runOnUiThread {
                val deviceName = device.name ?: "Unknown"
                val deviceInfo = "$deviceName\n${device.address}"
                val position = deviceList.indexOf(device)
                if (position >= 0) {
                    deviceListAdapter.remove(deviceListAdapter.getItem(position))
                    deviceListAdapter.insert(deviceInfo, position)
                    deviceListAdapter.notifyDataSetChanged()
                }
                Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
            }
        } else {
            isConnecting = true
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        return
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                        runOnUiThread {
                            val deviceName = device.name ?: "Unknown"
                            val deviceInfo = "$deviceName\n${device.address} (Connected)"
                            val position = deviceList.indexOf(device)
                            if (position >= 0) {
                                deviceListAdapter.remove(deviceListAdapter.getItem(position))
                                deviceListAdapter.insert(deviceInfo, position)
                                deviceListAdapter.notifyDataSetChanged()
                            }
                            connectedDevice = device
                            bluetoothGatt = gatt
                            isConnecting = false
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                            connectedDevice = null
                            bluetoothGatt = null
                            isConnecting = false
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val uartService = gatt.getService(UART_SERVICE_UUID)
                        uartService?.let {
                            val txCharacteristic = it.getCharacteristic(TX_CHARACTERISTIC_UUID)
                            enableNotifications(gatt, txCharacteristic)
                        }
                    }
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (characteristic.uuid == TX_CHARACTERISTIC_UUID) {
                        val data = characteristic.getStringValue(0)
                        Log.d(TAG, "Received: $data")
                        runOnUiThread {
                            receivedMessagesTextView.append("$data\n")
                            val message = Message(0, gatt.device.name ?: "Unknown", gatt.device.address, data)
                            dbHelper.insertMessage(gatt.device.name ?: "Unknown", gatt.device.address, data)
                            messageAdapter.addMessage(message) // Add the new message to the adapter
                            recyclerView.scrollToPosition(0) // Scroll to the top to make the new message visible
                        }
                    }
                }

                private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        return
                    }
                    if (!gatt.setCharacteristicNotification(characteristic, true)) {
                        Log.e(TAG, "Failed to set characteristic notification")
                        return
                    }
                    val descriptor = characteristic.getDescriptor(CCCD_UUID)
                    if (descriptor.value != BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            device.connectGatt(this, false, gattCallback)
        }
    }

    // Function to fetch and display data from the database
    private fun displayDatabaseContents() {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DatabaseHelper.TABLE_NAME,
            null, null, null, null, null,
            "${DatabaseHelper.COLUMN_ID} DESC" // Sort by ID in descending order
        )

        val messages = mutableListOf<Message>()
        while (cursor.moveToNext()) {
            val id = cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_NAME))
            val address = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ADDRESS))
            val message = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_MESSAGE))
            messages.add(Message(id, name, address, message))
        }
        cursor.close()

        messageAdapter = MessageAdapter(messages)
        recyclerView.adapter = messageAdapter
        recyclerView.visibility = View.VISIBLE
    }

    // Function to hide data from the RecyclerView
    private fun hideDatabaseContents() {
        messageAdapter = MessageAdapter(mutableListOf())
        recyclerView.adapter = messageAdapter
    }
}