package com.davidadrianrg.atril.ui.input

import com.davidadrianrg.atril.core.input.PadAction
import kotlinx.coroutines.flow.MutableSharedFlow

/** Gamepad intents that screens subscribe to (Start/Menu opens the launcher menu). */
object PadEvents {
    val menu = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Start: abre el buscador de juegos descargados (Play). */
    val search = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Y: abre el detalle del juego seleccionado (Play). */
    val details = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** L1 / R1: filtro de plataforma anterior / siguiente (-1 / +1) en Play; R1 abre las opciones de vista en la Biblioteca. */
    val page = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** X: marca o desmarca como favorito el juego actual (Play). */
    val favorite = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Botón Home del sistema con Atril como launcher: Play cierra menús y diálogos. */
    val home = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * true mientras el menú del launcher está abierto: el D-pad y la A del
     * mando se enrutan a [menuNav] en lugar del foco de Compose, que no
     * siempre entra en el overlay.
     */
    @Volatile
    var menuOpen: Boolean = false

    /** Up/Down/Select dentro del menú abierto. */
    val menuNav = MutableSharedFlow<PadAction>(extraBufferCapacity = 4)
}
