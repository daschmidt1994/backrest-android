package at.daschmidt.backrestwidget

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Refresher.createChannel(this)
    }
}
