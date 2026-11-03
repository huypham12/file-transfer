package com.example.file_tranfer.domain.repository

import com.example.file_tranfer.domain.model.DeviceIdentity
import kotlinx.coroutines.flow.Flow

/**
 * Hợp đồng dữ liệu quản lý danh bạ các thiết bị và khóa công khai P2P.
 */
interface IDeviceRepository {
    suspend fun getDeviceById(deviceId: String): DeviceIdentity?
    suspend fun getDeviceByFingerprint(fingerprint: String): DeviceIdentity?
    suspend fun saveOrUpdateDevice(device: DeviceIdentity)
    suspend fun setDeviceTrustStatus(deviceId: String, isTrusted: Boolean)
    fun getTrustedDevicesFlow(): Flow<List<DeviceIdentity>>
}

