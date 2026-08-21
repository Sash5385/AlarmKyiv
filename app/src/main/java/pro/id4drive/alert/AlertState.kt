package pro.id4drive.alert

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class UiState(
    val connectionState: ConnectionState = ConnectionState.CONNECTING,
    val kyivActive: Boolean = false,
    val areas: List<AlertArea> = emptyList(),
    val lastUpdatedAtMs: Long = 0L,
)

/** Єдиний міст між AlertService і Compose-екраном. */
object AlertState {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun updateConnection(connectionState: ConnectionState) {
        _state.update { it.copy(connectionState = connectionState) }
    }

    fun updateAreas(areas: List<AlertArea>) {
        val kyivActive = areas.any { it.active && Config.isKyiv(it.key, it.title) }
        _state.update {
            it.copy(
                areas = areas,
                kyivActive = kyivActive,
                lastUpdatedAtMs = System.currentTimeMillis(),
            )
        }
    }
}
