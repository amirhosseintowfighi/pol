import CryptoKit
import Foundation
import Security

/// Minimal Keychain wrapper. Items are this-device-only (never synced to iCloud Keychain, never in backups that
/// restore to another device) and readable after first unlock so the app can reconnect in the background.
enum Keychain {
    private static let service = "studio.virgule.pol.ios"

    static func set(_ data: Data, for account: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: account]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }

    static func get(_ account: String) -> Data? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: account, kSecReturnData as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        return SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }

    static func remove(_ account: String) {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: account]
        SecItemDelete(query as CFDictionary)
    }

    static func removeAll() {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary)
    }
}

/// This iPhone's long-term identity: a P-256 signing key created in the Secure Enclave. The Keychain holds only
/// the Enclave's wrapped key blob, which is useless on any other device. On hardware without a Secure Enclave
/// (the Simulator) a software key is kept in the Keychain instead.
final class DeviceIdentity: @unchecked Sendable {
    private enum Key { case enclave(SecureEnclave.P256.Signing.PrivateKey), software(P256.Signing.PrivateKey) }
    private let key: Key
    let publicKey: Data

    private static let account = "identity.v1"

    private init(_ key: Key) {
        self.key = key
        switch key {
        case .enclave(let k): publicKey = k.publicKey.x963Representation
        case .software(let k): publicKey = k.publicKey.x963Representation
        }
    }

    func sign(_ data: Data) throws -> Data {
        switch key {
        case .enclave(let k): return try k.signature(for: data).derRepresentation
        case .software(let k): return try k.signature(for: data).derRepresentation
        }
    }

    static func load() throws -> DeviceIdentity {
        if let blob = Keychain.get(account) {
            if blob.first == 0x53, let k = try? P256.Signing.PrivateKey(rawRepresentation: blob.dropFirst()) { return DeviceIdentity(.software(k)) }
            if blob.first == 0x45, let k = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: blob.dropFirst()) { return DeviceIdentity(.enclave(k)) }
        }
        if SecureEnclave.isAvailable {
            let k = try SecureEnclave.P256.Signing.PrivateKey()
            try Keychain.set(Data([0x45]) + k.dataRepresentation, for: account)
            return DeviceIdentity(.enclave(k))
        }
        let k = P256.Signing.PrivateKey()
        try Keychain.set(Data([0x53]) + k.rawRepresentation, for: account)
        return DeviceIdentity(.software(k))
    }

    static func delete() { Keychain.remove(account) }
}
