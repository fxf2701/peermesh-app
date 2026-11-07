package app.peermesh.nearby

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

data class NearbyReadinessState(
    val bluetoothPermissionGranted: Boolean,
    val bluetoothEnabled: Boolean,
    val bluetoothScanGranted: Boolean,
    val bluetoothAdvertiseGranted: Boolean,
    val bluetoothConnectGranted: Boolean,
    val locationRequired: Boolean,
    val locationPermissionGranted: Boolean,
    val locationServicesEnabled: Boolean,
    val canDiscover: Boolean,
    val canCreateRoom: Boolean,
    val canUseRoom: Boolean,
    val blockingReasons: List<NearbyBlockingReason>,
)

enum class NearbyBlockingReason {
    BLUETOOTH_PERMISSION,
    BLUETOOTH_SCAN_PERMISSION,
    BLUETOOTH_ADVERTISE_PERMISSION,
    BLUETOOTH_CONNECT_PERMISSION,
    BLUETOOTH_DISABLED,
    LOCATION_PERMISSION,
    LOCATION_SERVICES,
}

object NearbyReadinessHelper {
    fun evaluate(context: Context): NearbyReadinessState {
        val appContext = context.applicationContext
        val locationRequired = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        val bluetoothScanGranted = hasPermission(appContext, Manifest.permission.BLUETOOTH_SCAN, grantedOnPreS = true)
        val bluetoothAdvertiseGranted = hasPermission(appContext, Manifest.permission.BLUETOOTH_ADVERTISE, grantedOnPreS = true)
        val bluetoothConnectGranted = hasPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT, grantedOnPreS = true)
        val locationPermissionGranted = if (locationRequired) {
            hasPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            true
        }
        val bluetoothPermissionGranted = bluetoothScanGranted && bluetoothConnectGranted
        val bluetoothEnabled = isBluetoothEnabled(appContext)
        val locationServicesEnabled = if (locationRequired) {
            val locationManager =
                appContext.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            LocationManagerCompat.isLocationEnabled(locationManager)
        } else {
            true
        }
        val canDiscover =
            bluetoothEnabled &&
                bluetoothScanGranted &&
                bluetoothConnectGranted &&
                (!locationRequired || (locationPermissionGranted && locationServicesEnabled))
        val canCreateRoom =
            canDiscover &&
                bluetoothAdvertiseGranted
        val canUseRoom =
            bluetoothEnabled &&
                bluetoothConnectGranted &&
                bluetoothScanGranted &&
                (!locationRequired || (locationPermissionGranted && locationServicesEnabled))

        val blockingReasons = buildList {
            if (!bluetoothPermissionGranted) add(NearbyBlockingReason.BLUETOOTH_PERMISSION)
            if (!bluetoothScanGranted) add(NearbyBlockingReason.BLUETOOTH_SCAN_PERMISSION)
            if (!bluetoothAdvertiseGranted) add(NearbyBlockingReason.BLUETOOTH_ADVERTISE_PERMISSION)
            if (!bluetoothConnectGranted) add(NearbyBlockingReason.BLUETOOTH_CONNECT_PERMISSION)
            if (!bluetoothEnabled) add(NearbyBlockingReason.BLUETOOTH_DISABLED)
            if (locationRequired && !locationPermissionGranted) add(NearbyBlockingReason.LOCATION_PERMISSION)
            if (locationRequired && !locationServicesEnabled) add(NearbyBlockingReason.LOCATION_SERVICES)
        }.distinct()

        return NearbyReadinessState(
            bluetoothPermissionGranted = bluetoothPermissionGranted,
            bluetoothEnabled = bluetoothEnabled,
            bluetoothScanGranted = bluetoothScanGranted,
            bluetoothAdvertiseGranted = bluetoothAdvertiseGranted,
            bluetoothConnectGranted = bluetoothConnectGranted,
            locationRequired = locationRequired,
            locationPermissionGranted = locationPermissionGranted,
            locationServicesEnabled = locationServicesEnabled,
            canDiscover = canDiscover,
            canCreateRoom = canCreateRoom,
            canUseRoom = canUseRoom,
            blockingReasons = blockingReasons,
        )
    }

    fun missingPermissionsForDiscovery(context: Context): Array<String> {
        return missingPermissions(
            context = context,
            includeScan = true,
            includeAdvertise = false,
            includeConnect = true,
            includeLocation = true,
        )
    }

    fun missingPermissionsForCreate(context: Context): Array<String> {
        return missingPermissions(
            context = context,
            includeScan = true,
            includeAdvertise = true,
            includeConnect = true,
            includeLocation = true,
        )
    }

    fun missingPermissionsForRoom(context: Context): Array<String> {
        return missingPermissions(
            context = context,
            includeScan = true,
            includeAdvertise = false,
            includeConnect = true,
            includeLocation = true,
        )
    }

    private fun missingPermissions(
        context: Context,
        includeScan: Boolean,
        includeAdvertise: Boolean,
        includeConnect: Boolean,
        includeLocation: Boolean,
    ): Array<String> {
        val missing = linkedSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (includeScan && !hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)) {
                missing += Manifest.permission.BLUETOOTH_SCAN
            }
            if (includeAdvertise && !hasPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)) {
                missing += Manifest.permission.BLUETOOTH_ADVERTISE
            }
            if (includeConnect && !hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
                missing += Manifest.permission.BLUETOOTH_CONNECT
            }
        } else if (includeLocation && !hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return missing.toTypedArray()
    }

    private fun hasPermission(
        context: Context,
        permission: String,
        grantedOnPreS: Boolean = false,
    ): Boolean {
        if (grantedOnPreS && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return false
        return adapter.isEnabled
    }
}
