package app.peermesh.transport.ble.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import app.peermesh.transport.ble.client.BleClientCallbacks

interface BleClientBackend {
    fun connect(
        context: Context,
        device: BluetoothDevice,
        callbacks: BleClientCallbacks,
    ): BleClientConnection?

    fun discoverServices(connection: BleClientConnection): Boolean

    fun write(
        connection: BleClientConnection,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean

    fun requestMtu(connection: BleClientConnection, mtu: Int): Boolean
}
