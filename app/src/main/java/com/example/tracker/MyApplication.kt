package com.example.tracker

import android.app.Application
import androidx.lifecycle.ViewModelProvider

// MyApplication.kt
class MyApplication : Application() {

    // 在這裡放置 Application 級別的 ViewModel 獲取方法
    fun getSharedViewModel(): SharedViewModel {
        return ViewModelProvider.AndroidViewModelFactory.getInstance(this)
            .create(SharedViewModel::class.java)
    }

    // 其他 Application 初始化代碼...
}