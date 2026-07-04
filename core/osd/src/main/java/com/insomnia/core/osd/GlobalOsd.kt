package com.insomnia.core.osd

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object GlobalOsd {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var hideJob: Job? = null

    @OptIn(DelicateCoroutinesApi::class)
    fun msg(text: String) {
        hideJob?.cancel()
        _message.value = text
        hideJob = GlobalScope.launch {
            delay(5_000)
            _message.value = null
        }
    }
}

val gOSD = GlobalOsd
