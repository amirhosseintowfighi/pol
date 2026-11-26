package studio.virgule.pol.android.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import studio.virgule.pol.android.common.BleSpec
import studio.virgule.pol.android.common.SafeLog
import studio.virgule.pol.core.link.LinkTransport

/**
 * BLE central for the Android client. Scans only for the Pol service UUID (BLUETOOTH_SCAN is declared
 * neverForLocation), connects, and exposes the link as a byte stream. Every callback is re-posted to [handler].
 */
@SuppressLint("MissingPermission") // Used only after BLUETOOTH_SCAN / BLUETOOTH_CONNECT are granted.
class BleCentral(private val ctx: Context, private val handler: Handler) {

    data class Found(val device: BluetoothDevice, val pairing: Boolean, val rssi: Int)

    interface ConnectionListener {
        fun onReady(transport: LinkTransport)
        fun onBytes(bytes: ByteArray)
        fun onDisconnected()
    }

    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    val bluetoothOn get() = manager.adapter?.isEnabled == true

    private var scanCallback: ScanCallback? = null

    fun startScan(lowPower: Boolean, onFound: (Found) -> Unit): Boolean {
        stopScan()
        val scanner = manager.adapter?.bluetoothLeScanner ?: return false
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val flags = result.scanRecord?.getServiceData(ParcelUuid(BleSpec.SERVICE))?.firstOrNull()?.toInt() ?: 0
                handler.post { onFound(Found(result.device, flags and BleSpec.FLAG_PAIRING != 0, result.rssi)) }
            }
            override fun onScanFailed(errorCode: Int) { SafeLog.w("scan", "scan failed $errorCode") }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleSpec.SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return runCatching { scanner.startScan(listOf(filter), settings, cb); scanCallback = cb; true }.getOrDefault(false)
    }

    fun stopScan() {
        scanCallback?.let { cb -> runCatching { manager.adapter?.bluetoothLeScanner?.stopScan(cb) } }
        scanCallback = null
    }

    /** One GATT connection to a gateway. */
    inner class Connection(private val device: BluetoothDevice, private val listener: ConnectionListener) : LinkTransport {
        private var gatt: BluetoothGatt? = null
        private var rx: BluetoothGattCharacteristic? = null
        private var mtu = 23
        private val queue = ArrayDeque<ByteArray>()
        private var writing = false
        private var closed = false
        private var queuedBytes = 0

        fun connect() {
            gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            // A connect attempt to a device that went away can hang; give up after 12 s.
            handler.postDelayed({ if (rx == null && !closed) close() }, 12_000)
        }

        override fun write(bytes: ByteArray) {
            handler.post {
                if (closed) return@post
                val n = (mtu - 3).coerceAtLeast(20)
                var i = 0
                while (i < bytes.size) { val k = minOf(n, bytes.size - i); queue.addLast(bytes.copyOfRange(i, i + k)); i += k }
                queuedBytes += bytes.size
                if (queuedBytes > 512 * 1024) { close(); return@post }
                pump()
            }
        }

        private fun pump() {
            if (writing || closed) return
            val g = gatt ?: return
            val c = rx ?: return
            val next = queue.removeFirstOrNull() ?: return
            queuedBytes -= next.size
            writing = true
            val ok = if (android.os.Build.VERSION.SDK_INT >= 33) {
                // Without response: call audio needs ~50 writes/s. The link layer still acknowledges each packet, and
                // onCharacteristicWrite still paces the queue, so nothing is dropped silently.
                g.writeCharacteristic(c, next, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION") run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; c.value = next; g.writeCharacteristic(c) }
            }
            if (!ok) { writing = false; queue.addFirst(next); queuedBytes += next.size; handler.postDelayed({ pump() }, 30) }
        }

        override fun close() {
            handler.post {
                if (closed) return@post
                closed = true
                runCatching { gatt?.disconnect(); gatt?.close() }
                gatt = null
                listener.onDisconnected()
            }
        }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                handler.post {
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        if (!g.requestMtu(BleSpec.DESIRED_MTU)) g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                        close()
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                handler.post { if (status == BluetoothGatt.GATT_SUCCESS) this@Connection.mtu = mtu; g.discoverServices() }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                handler.post {
                    val s = g.getService(BleSpec.SERVICE)
                    val r = s?.getCharacteristic(BleSpec.RX)
                    val t = s?.getCharacteristic(BleSpec.TX)
                    val d = t?.getDescriptor(BleSpec.CCCD)
                    if (r == null || t == null || d == null) { close(); return@post }
                    g.setCharacteristicNotification(t, true)
                    val enable = byteArrayOf(1, 0)
                    val ok = if (android.os.Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, enable) == BluetoothGatt.GATT_SUCCESS
                    else @Suppress("DEPRECATION") run { d.value = enable; g.writeDescriptor(d) }
                    if (!ok) { close(); return@post }
                    rx = r
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: android.bluetooth.BluetoothGattDescriptor, status: Int) {
                handler.post { if (status == BluetoothGatt.GATT_SUCCESS) listener.onReady(this@Connection) else close() }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                handler.post {
                    writing = false
                    if (status != BluetoothGatt.GATT_SUCCESS) close() else pump()
                }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid != BleSpec.TX) return
                val copy = value.copyOf()
                handler.post { if (!closed) listener.onBytes(copy) }
            }

            @Deprecated("Android 12 and lower")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (android.os.Build.VERSION.SDK_INT >= 33) return
                onCharacteristicChanged(g, characteristic, characteristic.value ?: return)
            }
        }
    }
}
