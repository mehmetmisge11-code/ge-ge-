package com.mehmet.gecgec

import android.app.Application
import com.mehmet.gecgec.geo.GeofenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GecGecApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Uygulama her açıldığında geofence'leri tazele.
        // (Kullanıcı konumu kapatıp açtıysa sistem onları düşürmüş olabilir.)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            GeofenceManager(applicationContext).sync()
        }
    }
}
