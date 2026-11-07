package com.wizwear.control

import android.app.Application
import com.wizwear.control.data.WizRepository

class WizApp : Application() {
    val repository: WizRepository by lazy { WizRepository(this) }
}
