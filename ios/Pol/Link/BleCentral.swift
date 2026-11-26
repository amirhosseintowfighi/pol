@preconcurrency import CoreBluetooth
import Foundation

enum BleSpec {
    static let service = CBUUID(string: "F103B6C9-F328-48B7-9D59-0306C22CE898")
    static let rx = CBUUID(string: "844270AB-FE95-4890-9F8B-47EA1D3D77AA")
    static let tx = CBUUID(string: "1E99158F-F772-4FD5-9449-4CFF9EE55C59")
    static let flagPairing: UInt8 = 0x01
    static let restoreId = "studio.virgule.pol.central"
}

@MainActor
protocol BleCentralDelegate: AnyObject {
    func bleStateChanged(poweredOn: Bool, unauthorized: Bool)
    func bleFound(_ peripheral: CBPeripheral, pairing: Bool)
    func bleReady(_ connection: BleConnection)
    func bleBytes(_ data: Data)
    func bleDisconnected(_ peripheral: CBPeripheral)
}

/// One GATT connection carrying the record stream; writes are queued and sent with response, one at a time.
@MainActor
final class BleConnection: LinkTransport {
    let peripheral: CBPeripheral
    fileprivate var rx: CBCharacteristic?
    private var queue: [Data] = []
    private var writing = false
    private var queued = 0
    private weak var central: BleCentral?

    fileprivate init(_ p: CBPeripheral, central: BleCentral) { peripheral = p; self.central = central }

    func write(_ data: Data) {
        let n = max(20, peripheral.maximumWriteValueLength(for: .withResponse))
        var i = data.startIndex
        while i < data.endIndex { let e = data.index(i, offsetBy: n, limitedBy: data.endIndex) ?? data.endIndex; queue.append(Data(data[i..<e])); i = e }
        queued += data.count
        if queued > 512 * 1024 { close(); return }
        pump()
    }

    fileprivate func pump() {
        guard !writing, let c = rx, !queue.isEmpty else { return }
        let next = queue.removeFirst()
        queued -= next.count
        writing = true
        peripheral.writeValue(next, for: c, type: .withResponse)
    }

    fileprivate func didWrite(error: Error?) {
        writing = false
        if error != nil { close() } else { pump() }
    }

    func close() { central?.disconnect(peripheral) }
}

/// CoreBluetooth central: scans only for the Pol service, supports state restoration so iOS can relaunch
/// the app in the background when the gateway sends data.
@MainActor
final class BleCentral: NSObject {
    weak var delegate: BleCentralDelegate?
    private var manager: CBCentralManager!
    private var connections: [UUID: BleConnection] = [:]
    private(set) var poweredOn = false

    override init() {
        super.init()
        // Creating the manager is what shows the Bluetooth permission prompt, so this object is only created
        // when the user starts pairing (or after they already paired).
        manager = CBCentralManager(delegate: self, queue: .main, options: [CBCentralManagerOptionRestoreIdentifierKey: BleSpec.restoreId])
    }

    var authorizationDenied: Bool { CBManager.authorization == .denied || CBManager.authorization == .restricted }

    func scan() {
        guard poweredOn else { return }
        manager.scanForPeripherals(withServices: [BleSpec.service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func stopScan() { if manager.isScanning { manager.stopScan() } }

    func connect(_ p: CBPeripheral) {
        let c = BleConnection(p, central: self)
        connections[p.identifier] = c
        p.delegate = self
        manager.connect(p, options: nil)
    }

    func disconnect(_ p: CBPeripheral) { manager.cancelPeripheralConnection(p) }
}

extension BleCentral: @preconcurrency CBCentralManagerDelegate, @preconcurrency CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        poweredOn = central.state == .poweredOn
        delegate?.bleStateChanged(poweredOn: poweredOn, unauthorized: central.state == .unauthorized)
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        // Restored connections are dropped and re-established cleanly: a session cannot be resumed mid-stream.
        for p in (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral]) ?? [] { central.cancelPeripheralConnection(p) }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let data = (advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data])?[BleSpec.service]
        delegate?.bleFound(peripheral, pairing: (data?.first ?? 0) & BleSpec.flagPairing != 0)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([BleSpec.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connections[peripheral.identifier] = nil
        delegate?.bleDisconnected(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connections[peripheral.identifier] = nil
        delegate?.bleDisconnected(peripheral)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = peripheral.services?.first(where: { $0.uuid == BleSpec.service }) else { disconnect(peripheral); return }
        peripheral.discoverCharacteristics([BleSpec.rx, BleSpec.tx], for: s)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let chars = service.characteristics ?? []
        guard let rx = chars.first(where: { $0.uuid == BleSpec.rx }), let tx = chars.first(where: { $0.uuid == BleSpec.tx }) else {
            disconnect(peripheral); return
        }
        connections[peripheral.identifier]?.rx = rx
        peripheral.setNotifyValue(true, for: tx)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.isNotifying, let c = connections[peripheral.identifier] else { disconnect(peripheral); return }
        delegate?.bleReady(c)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == BleSpec.tx, let v = characteristic.value else { return }
        delegate?.bleBytes(v)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        connections[peripheral.identifier]?.didWrite(error: error)
    }
}
