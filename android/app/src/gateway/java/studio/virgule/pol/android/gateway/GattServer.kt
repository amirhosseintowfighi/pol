package studio.virgule.pol.android.gateway

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import studio.virgule.pol.android.common.BleSpec
import studio.virgule.pol.android.common.SafeLog
import studio.virgule.pol.core.link.LinkTransport

/**
 * BLE peripheral side of the link: one GATT service with an RX (write) and a TX (notify) characteristic carrying
 * the record stream. No characteristic requires Bluetooth-level encryption or bonding: confidentiality and
 * authentication come from the Pol handshake, so an unbonded attacker can connect but only reach a
 * handshake that it cannot complete without the pairing code (new devices) or a paired identity key (resume).
 *
 * All callbacks are re-posted to [handler] (one thread), which is also where the link engines run.
 */
@SuppressLint("MissingPermission") // Callers start this only after BLUETOOTH_ADVERTISE/CONNECT are granted.
class GattServer(
    private val ctx: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(device: BluetoothDevice, transport: Connection)
        fun onBytes(device: BluetoothDevice, bytes: ByteArray)
        fun onDisconnected(device: BluetoothDevice)
        fun onAdvertisingFailed(code: Int)
    }

    /** One connected central. Writes are queued and sent one notification at a time. */
    inner class Connection(val device: BluetoothDevice) : LinkTransport {
        var mtu = 23
        var subscribed = false
        private val queue = ArrayDeque<ByteArray>()
        private var inFlight = false
        private var queuedBytes = 0
        /** Bytes waiting to be notified; used to drop late audio before it is sealed. */
        val backlog: Int get() = queuedBytes

        override fun write(bytes: ByteArray) {
            handler.post {
                val chunk = (mtu - 3).coerceAtLeast(20)
                var i = 0
                while (i < bytes.size) { val n = minOf(chunk, bytes.size - i); queue.addLast(bytes.copyOfRange(i, i + n)); i += n }
                queuedBytes += bytes.size
                if (queuedBytes > MAX_QUEUED_BYTES) {
                    SafeLog.w("gatt", "client not reading; disconnecting")
                    close()
                    return@post
                }
                pump()
            }
        }

        override fun close() { handler.post { server?.cancelConnection(device) } }

        fun pump() {
            if (inFlight || !subscribed) return
            val s = server ?: return
            val next = queue.removeFirstOrNull() ?: return
            queuedBytes -= next.size
            inFlight = true
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(device, tx, false, next) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                tx.value = next
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, tx, false)
            }
            if (!ok) { inFlight = false; queue.addFirst(next); queuedBytes += next.size; handler.postDelayed({ pump() }, 20) }
        }

        fun onSent() { inFlight = false; pump() }
    }

    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private val connections = HashMap<String, Connection>()
    private var advertisingFlags = -1
    private lateinit var tx: BluetoothGattCharacteristic

    val bluetoothOn: Boolean get() = manager.adapter?.isEnabled == true

    fun start(): Boolean {
        if (server != null) return true
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        val s = manager.openGattServer(ctx, callback) ?: return false
        val service = BluetoothGattService(BleSpec.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        // Write-without-response as well: call audio needs ~50 writes/s each way, which acknowledged writes can't sustain.
        val rx = BluetoothGattCharacteristic(BleSpec.RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        tx = BluetoothGattCharacteristic(BleSpec.TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        tx.addDescriptor(BluetoothGattDescriptor(BleSpec.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        s.addService(service)
        server = s
        return true
    }

    /** (Re)advertises the service UUID, with the pairing flag when the owner opened pairing. No device name. */
    fun advertise(pairingOpen: Boolean) {
        val flags = if (pairingOpen) BleSpec.FLAG_PAIRING else 0
        if (flags == advertisingFlags) return
        val adv = manager.adapter?.bluetoothLeAdvertiser ?: run { listener.onAdvertisingFailed(-1); return }
        if (advertisingFlags >= 0) runCatching { adv.stopAdvertising(advertiseCallback) }
        advertisingFlags = flags
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (pairingOpen) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleSpec.SERVICE))
            .addServiceData(ParcelUuid(BleSpec.SERVICE), byteArrayOf(flags.toByte()))
            .build()
        runCatching { adv.startAdvertising(settings, data, advertiseCallback) }.onFailure { listener.onAdvertisingFailed(-2) }
    }

    fun stop() {
        runCatching { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertisingFlags = -1
        connections.values.forEach { runCatching { server?.cancelConnection(it.device) } }
        connections.clear()
        runCatching { server?.close() }
        server = null
    }

    fun disconnect(device: BluetoothDevice) { runCatching { server?.cancelConnection(device) } }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return
            handler.post { advertisingFlags = -1; listener.onAdvertisingFailed(errorCode) }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (connections.size >= MAX_CONNECTIONS) { server?.cancelConnection(device); return@post }
                    val c = Connection(device)
                    connections[device.address] = c
                    listener.onConnected(device, c)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (connections.remove(device.address) != null) listener.onDisconnected(device)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post { connections[device.address]?.mtu = mtu.coerceIn(23, BleSpec.DESIRED_MTU) }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val ok = characteristic.uuid == BleSpec.RX && !preparedWrite && offset == 0 && value != null && value.size <= BleSpec.DESIRED_MTU
            if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            if (ok) { val copy = value!!.copyOf(); handler.post { if (connections.containsKey(device.address)) listener.onBytes(device, copy) } }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val ok = descriptor.uuid == BleSpec.CCCD && value != null && value.size == 2
            if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            if (ok) handler.post {
                connections[device.address]?.let { it.subscribed = value!![0].toInt() and 0x01 != 0; it.pump() }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val on = connections[device.address]?.subscribed == true
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, if (on) byteArrayOf(1, 0) else byteArrayOf(0, 0))
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post { connections[device.address]?.onSent() }
        }
    }

    private companion object {
        const val MAX_CONNECTIONS = 3
        /** A peer that stops reading must not make the gateway buffer without bound. */
        const val MAX_QUEUED_BYTES = 512 * 1024
    }
}
