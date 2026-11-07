package app.peermesh.transport.ble.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Build

class GattClientBackend : BleClientBackend {
    private class GattClientConnection(
        override val device: BluetoothDevice,
        override val gatt: BluetoothGatt,
    ) : BleClientConnection {
        override val address: String = device.address

        override fun disconnect() {
            gatt.disconnect()
        }

        override fun close() {
            gatt.close()
        }
    }

    override fun connect(
        context: Context,
        device: BluetoothDevice,
        callbacks: BleClientCallbacks,
    ): BleClientConnection? {
        var connection: GattClientConnection? = null
        val callback = object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                val conn = connection ?: GattClientConnection(device, gatt).also { connection = it }
                callbacks.onConnectionStateChange(conn, status, newState)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val conn = connection ?: GattClientConnection(device, gatt).also { connection = it }
                callbacks.onServicesDiscovered(conn, status)
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val conn = connection ?: GattClientConnection(device, gatt).also { connection = it }
                callbacks.onMtuChanged(conn, mtu, status)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val conn = connection ?: GattClientConnection(device, gatt).also { connection = it }
                callbacks.onCharacteristicWrite(conn, characteristic, status)
            }
        }
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
        if (gatt != null) {
            connection = GattClientConnection(device, gatt)
        }
        return connection
    }

    override fun discoverServices(connection: BleClientConnection): Boolean {
        val gatt = connection.gatt ?: return false
        return gatt.discoverServices()
    }

    override fun write(
        connection: BleClientConnection,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        val gatt = connection.gatt ?: return false
        return gatt.writeCharacteristic(characteristic)
    }

    override fun requestMtu(connection: BleClientConnection, mtu: Int): Boolean {
        val gatt = connection.gatt ?: return false
        return gatt.requestMtu(mtu)
    }
}
