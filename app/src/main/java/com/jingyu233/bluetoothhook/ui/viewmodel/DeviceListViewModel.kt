package com.jingyu233.bluetoothhook.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jingyu233.bluetoothhook.data.bridge.ConfigBridge
import com.jingyu233.bluetoothhook.data.local.VirtualDeviceDatabase
import com.jingyu233.bluetoothhook.data.model.VirtualDevice
import com.jingyu233.bluetoothhook.data.repository.VirtualDeviceRepository
import com.jingyu233.bluetoothhook.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设备列表ViewModel
 */
class DeviceListViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private val TAG = Logger.Tags.UI_VM_DEVICE_LIST
    }

    private val database = VirtualDeviceDatabase.getInstance(application)
    private val repository = VirtualDeviceRepository(database.virtualDeviceDao(), application)
    private val configBridge = ConfigBridge(application)

    val devices = repository.allDevices

    private val _globalEnabled = MutableStateFlow(true)
    val globalEnabled: StateFlow<Boolean> = _globalEnabled.asStateFlow()

    private val _hookStatus = MutableStateFlow("Unknown")
    val hookStatus: StateFlow<String> = _hookStatus.asStateFlow()

    init {
        // 加载全局开关状态
        _globalEnabled.value = configBridge.getGlobalEnabled()

        // 监听设备列表变化，自动同步到SharedPreferences
        viewModelScope.launch {
            devices.collect { deviceList ->
                syncToConfigBridge(deviceList)
            }
        }

        // Hook 状态：框架 binder 是异步送进来的，这里等它落地再查，
        // 否则冷启动第一帧必然显示「Hook未激活」，点刷新才变「已激活」。
        viewModelScope.launch {
            _hookStatus.value = configBridge.awaitHookStatus()
            // 之后框架连上/断开都自动跟随，不用再手点刷新
            ConfigBridge.connected.collect { connected ->
                if (connected != null) {
                    _hookStatus.value = configBridge.getHookStatus()
                }
            }
        }
    }

    /**
     * 设置全局开关
     */
    fun setGlobalEnabled(enabled: Boolean) {
        _globalEnabled.value = enabled
        configBridge.setGlobalEnabled(enabled)
        Logger.App.i(TAG, "Global enabled set to: $enabled")
    }

    /**
     * 切换设备启用状态
     */
    fun toggleDevice(device: VirtualDevice) {
        viewModelScope.launch {
            repository.toggleDevice(device)
            Logger.App.d(TAG, "Toggled device: ${device.name}")
        }
    }

    /**
     * 删除设备
     */
    fun deleteDevice(device: VirtualDevice) {
        viewModelScope.launch {
            repository.deleteDevice(device)
            Logger.App.i(TAG, "Deleted device: ${device.name}")
        }
    }

    /**
     * 同步��备列表到ConfigBridge
     */
    private fun syncToConfigBridge(devices: List<VirtualDevice>) {
        configBridge.writeDeviceConfig(devices)
        Logger.App.d(TAG, "Synced ${devices.size} devices to ConfigBridge")
    }

    /**
     * 刷新Hook状态
     *
     * 注意：正常情况下不需要点刷新 —— 框架连接状态变化会通过
     * [ConfigBridge.connected] 自动推过来。这个函数保留给「框架刚重启、
     * binder 需要重新拉」的兜底场景。
     */
    fun refreshHookStatus() {
        viewModelScope.launch {
            _hookStatus.value = configBridge.awaitHookStatus()
            Logger.App.d(TAG, "Refreshed hook status: ${_hookStatus.value}")
        }
    }
}
