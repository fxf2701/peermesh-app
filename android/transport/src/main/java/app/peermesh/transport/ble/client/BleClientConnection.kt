package app.peermesh.transport.ble.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt

interface BleClientConnection {
    val device: BluetoothDevice
    val address: String
    val gatt: BluetoothGatt?
    fun disconnect()
    fun close()
}
