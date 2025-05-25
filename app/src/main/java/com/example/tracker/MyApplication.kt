package com.example.tracker

import android.app.Application
import androidx.lifecycle.ViewModelProvider // Ensure this is the correct import
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

// MyApplication.kt
class MyApplication : Application(), ViewModelStoreOwner {
    // Override the viewModelStore property from ViewModelStoreOwner
    override val viewModelStore: ViewModelStore by lazy {
        ViewModelStore()
    }


    // Initialize sharedViewModel directly
     val sharedViewModel: SharedViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))
            .get(SharedViewModel::class.java)
    }


    // Other Application initialization code...
}