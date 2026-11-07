package app.peermesh.transport.ble.nordic

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.peermesh.transport.ble.BleConfig
import app.peermesh.transport.ble.client.BleClientBackend
import app.peermesh.transport.ble.client.BleClientCallbacks
import app.peermesh.transport.ble.client.BleClientConnection
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.BleManagerCallbacks
import java.util.concurrent.ConcurrentHashMap

class NordicClientBackend : BleClientBackend {
    private inner class NordicClientConnection(
        override val device: BluetoothDevice,
        internal val manager: NordicClientManager,
    ) : BleClientConnection {
        override val address: String = device.address
        @Volatile override var gatt: BluetoothGatt? = null
        @Volatile private var closed: Boolean = false

        override fun disconnect() {
            manager.disconnect().enqueue()
        }

        override fun close() {
            if (closed) return
            closed = true
            manager.close()
            managers.remove(address, manager)
        }
    }

    private class NordicClientManager(
        context: Context,
        private val clientCallbacks: BleClientCallbacks,
    ) : BleManager(context) {
        lateinit var connection: NordicClientConnection
        @Volatile private var requiredSupported: Boolean = false

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            connection.gatt = gatt
            val service = gatt.getService(BleConfig.serviceUuid)
            val characteristic = service?.getCharacteristic(BleConfig.characteristicUuid)
            requiredSupported = characteristic != null
            return requiredSupported
        }

        override fun onServicesInvalidated() {
            connection.gatt = null
        }

        fun notifyServicesDiscovered() {
            val status = if (requiredSupported) {
                BluetoothGatt.GATT_SUCCESS
            } else {
                BluetoothGatt.GATT_FAILURE
            }
            clientCallbacks.onServicesDiscovered(connection, status)
        }

        fun notifyConnected() {
            clientCallbacks.onConnectionStateChange(
                connection,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED,
            )
        }

        fun notifyDisconnected() {
            clientCallbacks.onConnectionStateChange(
                connection,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_DISCONNECTED,
            )
        }

        fun notifyError(status: Int) {
            clientCallbacks.onConnectionStateChange(
                connection,
                status,
                BluetoothProfile.STATE_DISCONNECTED,
            )
        }

        fun enqueueWrite(
            connection: BleClientConnection,
            characteristic: BluetoothGattCharacteristic,
            payload: ByteArray,
        ) {
            writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
                .done { clientCallbacks.onCharacteristicWrite(connection, characteristic, BluetoothGatt.GATT_SUCCESS) }
                .fail { _, status -> clientCallbacks.onCharacteristicWrite(connection, characteristic, status) }
                .enqueue()
        }

        fun enqueueMtu(
            connection: BleClientConnection,
            mtu: Int,
        ) {
            requestMtu(mtu)
                .with { _, negotiated ->
                    clientCallbacks.onMtuChanged(
                        connection,
                        negotiated,
                        BluetoothGatt.GATT_SUCCESS,
                    )
                }
                .fail { _, status -> clientCallbacks.onMtuChanged(connection, mtu, status) }
                .enqueue()
        }
    }

    private val managers = ConcurrentHashMap<String, NordicClientManager>()

    override fun connect(
        context: Context,
        device: BluetoothDevice,
        callbacks: BleClientCallbacks,
    ): BleClientConnection? {
        val address = device.address
        managers.remove(address)?.close()
        val manager = NordicClientManager(context, callbacks)
        val connection = NordicClientConnection(device, manager)
        manager.connection = connection
        manager.setGattCallbacks(object : BleManagerCallbacks {
            override fun onDeviceConnecting(device: BluetoothDevice) {
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                manager.notifyConnected()
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
            }

            override fun onDeviceDisconnected(device: BluetoothDevice) {
                manager.notifyDisconnected()
            }

            override fun onLinkLossOccurred(device: BluetoothDevice) {
                manager.notifyDisconnected()
            }

            override fun onServicesDiscovered(device: BluetoothDevice, optionalServicesFound: Boolean) {
                manager.notifyServicesDiscovered()
            }

            override fun onDeviceReady(device: BluetoothDevice) {
            }

            override fun onBondingRequired(device: BluetoothDevice) {
            }

            override fun onBonded(device: BluetoothDevice) {
            }

            override fun onBondingFailed(device: BluetoothDevice) {
            }

            override fun onError(device: BluetoothDevice, message: String, errorCode: Int) {
                manager.notifyError(errorCode)
            }

            override fun onDeviceNotSupported(device: BluetoothDevice) {
                manager.notifyError(BluetoothGatt.GATT_FAILURE)
            }
        })
        managers[address] = manager
        manager.connect(device)
            .retry(2, 100)
            .useAutoConnect(false)
            .enqueue()
        return connection
    }

    override fun write(
        connection: BleClientConnection,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        val nordic = connection as? NordicClientConnection ?: return false
        val payload = characteristic.value ?: return false
        nordic.manager.enqueueWrite(connection, characteristic, payload)
        return true
    }

    override fun requestMtu(connection: BleClientConnection, mtu: Int): Boolean {
        val nordic = connection as? NordicClientConnection ?: return false
        nordic.manager.enqueueMtu(connection, mtu)
        return true
    }

    override fun discoverServices(connection: BleClientConnection): Boolean {
        // BleManager handles service discovery internally.
        return true
    }
}
