package com.wizwear.control.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wizwear.control.data.Bulb
import com.wizwear.control.data.BulbStatus
import com.wizwear.control.data.Outcome
import com.wizwear.control.data.WizRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WizViewModel(private val repo: WizRepository) : ViewModel() {

    val bulbs: StateFlow<List<Bulb>> = repo.bulbs
    val status: StateFlow<Map<String, BulbStatus>> = repo.status

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Transient feedback, shown as an M3 success/failure confirmation dialog. */
    data class UiMessage(val text: String, val success: Boolean)

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    /** Commands go out one at a time so the bulb sees them in order. */
    private val sendLock = Mutex()
    private var sliderJob: Job? = null

    init {
        if (repo.bulbs.value.isEmpty()) discover() else refresh()
    }

    fun refresh() = exec(showBusy = true) { repo.refreshAll() }

    fun discover() = exec(showBusy = true) {
        val (outcome, count) = repo.discover()
        if (outcome == Outcome.OK) _message.value = UiMessage(if (count == 1) "1 light found" else "$count lights found", success = true)
        outcome
    }

    fun toggle(mac: String) = exec { repo.toggle(mac) }
    fun setPower(mac: String, on: Boolean) = exec { repo.setPower(mac, on) }
    fun allPower(on: Boolean) = exec { repo.forAll { setPower(it, on) } }
    fun setColor(mac: String, r: Int, g: Int, b: Int) = exec { repo.setColor(mac, r, g, b) }
    fun setScene(mac: String, id: Int) = exec { repo.setScene(mac, id) }
    fun setTemperature(mac: String, k: Int) = exec { repo.setTemperature(mac, k) }

    /** Crown / slider: only the latest value is sent, ~150 ms after the last change. */
    fun setBrightnessDebounced(mac: String, value: Int) = debounced { repo.setBrightness(mac, value) }
    fun setTemperatureDebounced(mac: String, value: Int) = debounced { repo.setTemperature(mac, value) }

    fun remove(mac: String) = repo.remove(mac)

    fun clearMessage() {
        _message.value = null
    }

    private fun debounced(block: suspend () -> Outcome) {
        sliderJob?.cancel()
        sliderJob = viewModelScope.launch {
            delay(150)
            report(sendLock.withLock { block() })
        }
    }

    private fun exec(showBusy: Boolean = false, block: suspend () -> Outcome) {
        viewModelScope.launch {
            if (showBusy) _busy.value = true
            try {
                report(sendLock.withLock { block() })
            } finally {
                if (showBusy) _busy.value = false
            }
        }
    }

    private fun report(outcome: Outcome) {
        when (outcome) {
            Outcome.OK -> Unit
            Outcome.NO_WIFI -> _message.value = UiMessage("No Wi-Fi", success = false)
            Outcome.OFFLINE -> _message.value = UiMessage("Light offline", success = false)
        }
    }

    companion object {
        fun factory(repo: WizRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WizViewModel(repo) as T
        }
    }
}
