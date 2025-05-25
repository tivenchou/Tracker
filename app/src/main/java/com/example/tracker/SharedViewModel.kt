package com.example.tracker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel(){
    // 使用 LiveData 來觀察數據變化
    private val _sharedEnableRecording = MutableLiveData<Boolean>()
    private val _sharedIsRecording = MutableLiveData<Boolean>()
    private val _sharedStartDelay = MutableLiveData<Int>()
    private val _sharedStopDelay = MutableLiveData<Int>()
    private val _sharedSavePath = MutableLiveData<String>()
    val sharedEnableRecording: LiveData<Boolean> get() = _sharedEnableRecording
    val sharedIsRecording: LiveData<Boolean> get() = _sharedIsRecording
    val sharedStartDelay: LiveData<Int> get() = _sharedStartDelay
    val sharedStopDelay: LiveData<Int> get() = _sharedStopDelay
    val sharedSavePath: LiveData<String> get() = _sharedSavePath

    // 設置數據的方法
    fun setSharedEnableRecording(data: Boolean) {
        _sharedEnableRecording.value = data
    }

    fun setSharedIsRecording(data: Boolean) {
        _sharedIsRecording.value = data
    }

    fun setSharedStartDelay(data: Int) {
        _sharedStartDelay.value = data
    }

    fun setSharedStopDelay(data: Int) {
        _sharedStopDelay.value = data
    }

    fun setSharedSavePath(data: String) {
        _sharedSavePath.value = data
    }
}

