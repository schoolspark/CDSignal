package `in`.chinmoydas.signal.viewmodel

sealed class UiState {
    object Ready : UiState()
    data class Connected(val target: String) : UiState()
    data class Transmitting(val target: String, val isBroadcasting: Boolean) : UiState()
    data class Receiving(val from: String) : UiState()
    data class Error(val message: String) : UiState()
}