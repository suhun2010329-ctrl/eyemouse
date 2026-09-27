package com.jel.eyemouse

import android.app.Application

class EyeMouseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
    }
}
