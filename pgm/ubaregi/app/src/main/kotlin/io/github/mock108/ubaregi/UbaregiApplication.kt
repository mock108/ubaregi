package io.github.mock108.ubaregi

import android.app.Application
import io.github.mock108.ubaregi.data.UbaregiDatabase
import io.github.mock108.ubaregi.data.UbaregiDatabaseFactory
import io.github.mock108.ubaregi.data.RoomRegisterRepository

class UbaregiApplication : Application() {
    val database: UbaregiDatabase by lazy { UbaregiDatabaseFactory.create(this) }

    val registerRepository: RoomRegisterRepository by lazy {
        RoomRegisterRepository(database)
    }
}
