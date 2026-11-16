package com.davidadrianrg.atril.ui.navigation

/** Destino pedido desde fuera de la app (botón Home, notificaciones). */
enum class NavRequest {
    /** Botón Home con Atril como launcher: volver a Play y cerrar overlays. */
    HOME,

    /** Notificación de actualización de Atril: Ajustes › Acerca de. */
    UPDATES,

    /** Notificación de actualizaciones de emuladores. */
    EMULATORS,
}

/**
 * Petición con identificador: dos pulsaciones de Home seguidas son dos
 * eventos distintos aunque pidan el mismo destino.
 */
data class NavRequestEvent(val request: NavRequest, val id: Long)

/** Ajustes debe abrirse en [category]; [id] evita aplicar dos veces la misma petición. */
data class ConfigCategoryRequest(
    val category: com.davidadrianrg.atril.core.settings.SettingsCategory,
    val id: Long,
)

/**
 * Traduce un intent entrante (en `onCreate` o en `onNewIntent`) a un destino.
 * Pura y sin tipos de Android para poder probarla en la JVM.
 *
 * Los extras de notificación mandan sobre la categoría: una notificación
 * abre su pantalla aunque Atril sea el launcher. Un `MAIN/LAUNCHER` (icono de
 * la app) no navega: devuelve la tarea tal como estaba.
 */
object AppIntentRouter {
    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val CATEGORY_HOME = "android.intent.category.HOME"

    fun route(
        action: String?,
        categories: Set<String>?,
        openUpdates: Boolean,
        openEmulators: Boolean,
    ): NavRequest? = when {
        openUpdates -> NavRequest.UPDATES
        openEmulators -> NavRequest.EMULATORS
        action == ACTION_MAIN && categories?.contains(CATEGORY_HOME) == true -> NavRequest.HOME
        else -> null
    }
}
