package app.peermesh.transport.ble.client

import android.bluetooth.BluetoothGattCharacteristic

interface BleClientCallbacks {
    fun onConnectionStateChange(connection: BleClientConnection, status: Int, newState: Int)
    fun onServicesDiscovered(connection: BleClientConnection, status: Int)
    fun onMtuChanged(connection: BleClientConnection, mtu: Int, status: Int)
    fun onCharacteristicWrite(
        connection: BleClientConnection,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    )
}
