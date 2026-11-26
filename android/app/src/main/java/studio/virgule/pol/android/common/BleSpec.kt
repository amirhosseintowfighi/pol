package studio.virgule.pol.android.common

import java.util.UUID

/** GATT layout of the Pol link. Mirrored in ios/Pol/Link/BleSpec.swift and protocol/PROTOCOL.md §1. */
object BleSpec {
    val SERVICE: UUID = UUID.fromString("f103b6c9-f328-48b7-9d59-0306c22ce898")
    /** Client → gateway, write with response. */
    val RX: UUID = UUID.fromString("844270ab-fe95-4890-9f8b-47ea1d3d77aa")
    /** Gateway → client, notify. */
    val TX: UUID = UUID.fromString("1e99158f-f772-4fd5-9449-4cff9ee55c59")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Service data byte in the advertisement: bit 0 set while the gateway's pairing window is open. */
    const val FLAG_PAIRING = 0x01
    const val DESIRED_MTU = 517
}
