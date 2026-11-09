package com.agustin.videoboostao

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reactiva Video Boost cada vez que Pixel Camera pasa a primer plano en
 * modo Video o Magic Capture (Pixel 11+), y al cambiar a uno de esos modos
 * dentro de la misma sesión. Pixel Camera lo modela como un control segmentado de dos
 * ImageButton ("Video Boost off" / "Video Boost on"), no como un Switch:
 * no hay isChecked, el estado se lee con isSelected. El botón "on" es
 * siempre el de más a la derecha dentro de la fila (mismo patrón que
 * Flash, Resolución, FPS y HDR en el mismo panel), así que la detección
 * no depende del idioma del sistema.
 *
 * Secuencia: detectar cámara → confirmar modo (Video / Magic Capture) → si el panel de
 * ajustes está colapsado, abrirlo con el botón `options_entry_button` →
 * localizar la fila "Video Boost" → si el botón "on" no está ya
 * seleccionado, tocarlo → verificar → cerrar el panel.
 */
class VideoBoostService : AccessibilityService() {

    companion object {
        private const val TAG = "VideoBoostAO"

        private const val ATTEMPT_DELAY_MS = 150L
        private const val PANEL_SETTLE_MS = 150L
        private const val MAX_ATTEMPTS = 30

        /** Tiempo mínimo entre clicks al botón de entrada del panel. Debe ser
         *  mayor que el peor tiempo de inflado del panel (~1 s medido en el
         *  Pixel 10 Pro XL): re-clickear antes lo cierra en plena animación. */
        private const val ENTRY_RECLICK_COOLDOWN_MS = 1500L

        /** Máximo de aperturas de panel por sesión antes de desistir. */
        private const val MAX_ENTRY_CLICKS = 3

        /** Espera antes de confirmar que la cámara dejó el primer plano. Los
         *  gestos del sistema (p. ej. nuestro BACK) emiten eventos de ventana
         *  de SystemUI con la cámara aún visible; resetear ahí re-dispara la
         *  activación en bucle. */
        private const val RESET_CONFIRM_MS = 600L

        /** Cadencia del chequeo de modo una vez resuelta la sesión: detecta
         *  que el usuario cambió a otro modo con Video Boost (p. ej. Video →
         *  Magic Capture) sin leer el árbol en cada evento del visor. */
        private const val MODE_CHECK_MS = 500L

        private const val MODE_VIDEO = "video"
        private const val MODE_MAGIC_CAPTURE = "magic_capture"

        /** Instancia viva del servicio, para que la UI pueda deshabilitarlo
         *  (una app solo puede apagar su propio servicio con `disableSelf`). */
        @Volatile
        var instance: VideoBoostService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // En modo bajo demanda, quien nos encendió fue el vigilante y ya está
        // vivo; salvo que el teléfono se haya reiniciado con la cámara abierta,
        // que nos revive a nosotros sin él. Arrancarlo desde acá cubre ese caso
        // y aprovecha la exención de background que da el servicio de
        // accesibilidad activo (mismo motivo que [BankWatchService.start]).
        CameraWatchService.sync(this)
        // Si volvió a habilitarse, ya no está "apagado por el banco".
        Prefs.setDisabledByBank(this, false)
        Notifications.cancel(this)
        Notifications.cancelPaused(this)
        // Reactivación con la cámara ya en primer plano (p. ej. full-auto tras
        // cerrar una app sensible): no llegará ningún evento que dispare el
        // chequeo, así que se lanza uno acá; si la cámara no está, no hace nada.
        // Respeta el interruptor maestro, igual que onAccessibilityEvent.
        if (Prefs.featureEnabled(this)) handler.postDelayed(attemptRunnable, ATTEMPT_DELAY_MS)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val attemptRunnable = Runnable { attemptActivation() }
    private val resetCheckRunnable = Runnable { confirmCameraGone() }
    private val modeCheckRunnable = Runnable { checkModeChange() }

    /** Ya se activó (o verificó activo, o se desistió) para el modo actual. */
    private var handledThisSession = false

    /** Modos ya resueltos en esta sesión de cámara. Video Boost puede ser un
     *  ajuste independiente por modo, así que cada uno se verifica una vez. */
    private val handledModes = mutableSetOf<String>()

    /** Modo sobre el que se está trabajando en la secuencia actual. */
    private var currentMode: String? = null

    /** El panel de Video Settings lo abrimos nosotros (para cerrarlo después). */
    private var panelOpenedByUs = false

    /** uptime del último click al botón de entrada (guarda anti-doble-click). */
    private var lastEntryClickMs = 0L

    private var entryClicks = 0
    private var attempts = 0

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // Auto-apagado para apps sensibles (bancos, fintech, wallets,
        // autenticadores...), antes que todo (incluso en pausa): estas apps
        // bloquean cualquier servicio de accesibilidad activo, así que nos
        // sacamos de la lista con disableSelf() al detectar una.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            Prefs.autoDisableForBanks(this) &&
            SensitiveApps.isSensitive(this, pkg)
        ) {
            Log.i(TAG, "App sensible en primer plano ($pkg); deshabilitando el servicio")
            val label = appLabel(pkg)
            if (Capabilities.onDemandMode(this)) {
                // Bajo demanda no hay nada que recordar ni que monitorear: el
                // servicio solo vive dentro de la cámara y [CameraWatchService]
                // lo volverá a encender la próxima vez que se abra. Ni marca de
                // "apagado por el banco" (la UI no debe pedir nada), ni
                // notificación, ni foreground service de vigilancia.
                disableSelf()
                return
            }
            Prefs.setDisabledByBank(this, true)
            if (Capabilities.canMonitorClose(this)) {
                // Notificación silenciosa + monitor que detecta el cierre (y, con
                // Shizuku, re-activa solo). Arrancar el FGS ANTES de disableSelf()
                // mientras el a11y service sigue activo (exención de background).
                BankWatchService.start(this, pkg, label)
            } else {
                // Sin monitoreo: aviso normal al abrir para no perder el
                // recordatorio de re-activar a mano.
                Notifications.showReenableReminder(this, label)
            }
            disableSelf()
            return
        }

        // Interruptor maestro de la app: pausado = no hacer nada, sin
        // necesidad de deshabilitar el servicio de accesibilidad.
        if (!Prefs.featureEnabled(this)) return

        // Sin filtro de package en el manifest: el filtrado es acá. Los
        // eventos de otras apps solo sirven para detectar que la cámara
        // dejó el primer plano y resetear la sesión; nunca se inspecciona
        // ni toca nada fuera de Pixel Camera.
        if (pkg != Selectors.CAMERA_PACKAGE) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                (handledThisSession || attempts > 0) &&
                !handler.hasCallbacks(resetCheckRunnable)
            ) {
                handler.postDelayed(resetCheckRunnable, RESET_CONFIRM_MS)
            }
            return
        }

        // Actividad de la cámara: cancelar cualquier reset pendiente.
        handler.removeCallbacks(resetCheckRunnable)

        if (handledThisSession) {
            // Sesión resuelta para el modo actual: solo vigilar (con throttle)
            // si el usuario pasa a otro modo con Video Boost.
            if (!handler.hasCallbacks(modeCheckRunnable)) {
                handler.postDelayed(modeCheckRunnable, MODE_CHECK_MS)
            }
            return
        }

        // Throttle (no debounce): la ráfaga de eventos durante la carga del
        // visor no pospone el intento: el primero corre a los 150 ms del
        // primer evento y de ahí se sondea a ritmo fijo.
        if (!handler.hasCallbacks(attemptRunnable)) {
            handler.postDelayed(attemptRunnable, ATTEMPT_DELAY_MS)
        }
    }

    /** Solo resetea si la cámara realmente dejó el primer plano. */
    private fun confirmCameraGone() {
        val fg = rootInActiveWindow?.packageName?.toString()
        if (fg == Selectors.CAMERA_PACKAGE) return
        Log.d(TAG, "Cámara en segundo plano; reseteo sesión")
        resetSession()
    }

    /** Re-arma la secuencia si la cámara pasó a un modo con Video Boost que
     *  todavía no se verificó en esta sesión. */
    private fun checkModeChange() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != Selectors.CAMERA_PACKAGE) return
        val mode = activeBoostMode(root) ?: return
        if (mode in handledModes) return
        Log.d(TAG, "Cambio a modo $mode; verificando Video Boost")
        handledThisSession = false
        panelOpenedByUs = false
        lastEntryClickMs = 0L
        entryClicks = 0
        attempts = 0
        handler.removeCallbacks(attemptRunnable)
        handler.postDelayed(attemptRunnable, ATTEMPT_DELAY_MS)
    }

    private fun resetSession() {
        handledThisSession = false
        handledModes.clear()
        currentMode = null
        handler.removeCallbacks(modeCheckRunnable)
        panelOpenedByUs = false
        lastEntryClickMs = 0L
        entryClicks = 0
        attempts = 0
        handler.removeCallbacks(attemptRunnable)
    }

    private fun attemptActivation() {
        if (handledThisSession) return
        if (attempts >= MAX_ATTEMPTS) {
            Log.w(TAG, "Agotados $MAX_ATTEMPTS intentos sin encontrar los nodos; desisto en este modo")
            markModeHandled()
            return
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != Selectors.CAMERA_PACKAGE) {
            return
        }

        val mode = activeBoostMode(root)
        if (mode == null) {
            // Modo foto/retrato: no actuar ni reintentar. El próximo evento
            // de la cámara (p. ej. cambio a modo video) re-dispara el chequeo.
            return
        }
        if (mode in handledModes) {
            // Volvió a un modo ya verificado en esta sesión: nada que hacer.
            handledThisSession = true
            return
        }
        currentMode = mode
        attempts++

        // Caso 1: la fila "Video Boost" ya está a la vista (panel abierto).
        val onButton = findVideoBoostOnButton(root)
        if (onButton != null) {
            ensureSelected(onButton)
            return
        }

        // Caso 2: panel colapsado, abrirlo con el botón de entrada.
        val entry = root.findAccessibilityNodeInfosByViewId(Selectors.VIDEO_SETTINGS_ENTRY_ID)
            ?.firstOrNull()
        if (entry != null) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastEntryClickMs < ENTRY_RECLICK_COOLDOWN_MS) {
                // Panel inflándose tras nuestro click reciente: solo esperar.
                retry()
                return
            }
            if (entryClicks >= MAX_ENTRY_CLICKS) {
                Log.w(TAG, "El panel no expone Video Boost tras $entryClicks aperturas; desisto en este modo")
                markModeHandled()
                return
            }
            Log.d(TAG, "Abriendo panel Video Settings (intento $attempts)")
            if (entry.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastEntryClickMs = now
                entryClicks++
                panelOpenedByUs = true
                handler.postDelayed(attemptRunnable, PANEL_SETTLE_MS)
            } else {
                retry()
            }
            return
        }

        retry()
    }

    private fun retry() {
        handler.removeCallbacks(attemptRunnable)
        handler.postDelayed(attemptRunnable, ATTEMPT_DELAY_MS)
    }

    private fun ensureSelected(onButton: AccessibilityNodeInfo) {
        if (onButton.isSelected) {
            Log.i(TAG, "Video Boost ya estaba activo")
            finishSession()
            return
        }

        Log.i(TAG, "Activando Video Boost")
        if (onButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            handler.postDelayed({ verifyAndClose() }, PANEL_SETTLE_MS)
        } else {
            retry()
        }
    }

    private fun verifyAndClose() {
        val root = rootInActiveWindow ?: run { finishSession(); return }
        val onButton = findVideoBoostOnButton(root)
        if (onButton != null && !onButton.isSelected) {
            Log.w(TAG, "El botón 'on' sigue sin seleccionar tras el click; reintento")
            retry()
            return
        }
        Log.i(TAG, "Video Boost activado y verificado")
        // Confirmación opcional (apagada por defecto). Va acá y no en el camino
        // de "ya estaba activo": avisar de algo que no hicimos sería ruido.
        Notifications.showActivated(this)
        finishSession()
    }

    /** Da por resuelto el modo actual (activado, ya activo o desistido). */
    private fun markModeHandled() {
        handledThisSession = true
        currentMode?.let { handledModes.add(it) }
    }

    private fun finishSession() {
        markModeHandled()
        handler.removeCallbacks(attemptRunnable)
        if (panelOpenedByUs) {
            // Dejar el visor limpio como lo encontró el usuario.
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 100L)
            panelOpenedByUs = false
        }
    }

    // ---------- Búsqueda de nodos ----------

    /** Idioma actual del sistema (el mismo con el que Pixel Camera se renderiza). */
    private fun systemLanguage(): String =
        resources.configuration.locales[0]?.language ?: "en"

    /**
     * Modo activo del carrusel si es uno donde aplica Video Boost (Video o
     * Magic Capture), o null en cualquier otro (Foto, Retrato, Noche...).
     */
    private fun activeBoostMode(root: AccessibilityNodeInfo): String? {
        val chips = root.findAccessibilityNodeInfosByViewId(Selectors.MODE_CHIP_TEXT_ID) ?: return null
        val selected = chips.filter { it.isSelected }
        if (selected.isEmpty()) return null
        val lang = systemLanguage()
        return when {
            selected.any { chipMatches(it, CameraLabels.videoModeLabels(lang)) } -> MODE_VIDEO
            selected.any { chipMatches(it, CameraLabels.magicCaptureModeLabels(lang)) } -> MODE_MAGIC_CAPTURE
            else -> null
        }
    }

    private fun chipMatches(chip: AccessibilityNodeInfo, labels: List<String>): Boolean =
        labels.any { label ->
            chip.text?.toString()?.equals(label, ignoreCase = true) == true ||
                chip.contentDescription?.toString()?.equals(label, ignoreCase = true) == true
        }

    /**
     * Encuentra el ImageButton "on" de la fila Video Boost.
     *
     * Estrategia primaria: identificarlo por su `contentDescription`
     * localizada (sapphire_on_desc, p. ej. "Video Boost on"). Es inmune a la
     * posición, así que funciona igual en layouts RTL (árabe, hebreo, farsi,
     * urdu) donde el orden visual de los botones se invierte.
     *
     * Respaldo: si la descripción cambiara en una versión futura, ubicar la
     * fila por su etiqueta y elegir el botón del extremo según la dirección
     * del layout (LTR → derecha, RTL → izquierda).
     */
    private fun findVideoBoostOnButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val lang = systemLanguage()

        // Primaria: match exacto de contentDescription contra los candidatos "on".
        for (desc in CameraLabels.videoBoostOnDescs(lang)) {
            val matches = root.findAccessibilityNodeInfosByText(desc) ?: continue
            val button = matches.firstOrNull {
                it.className == "android.widget.ImageButton" &&
                    it.isClickable &&
                    it.contentDescription?.toString()?.equals(desc, ignoreCase = true) == true
            }
            if (button != null) return button
        }

        // Respaldo: fila por etiqueta + extremo según dirección del layout.
        var label: AccessibilityNodeInfo? = null
        for (candidate in CameraLabels.videoBoostLabels(lang)) {
            val matches = root.findAccessibilityNodeInfosByText(candidate)
            label = matches?.firstOrNull { it.isVisibleToUser } ?: matches?.firstOrNull()
            if (label != null) break
        }
        if (label == null) return null

        val row = label.parent ?: label
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        collectClickableImageButtons(row, buttons, depth = 0)
        if (buttons.isEmpty()) return null

        val rtl = resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        return if (rtl) buttons.minByOrNull { boundsOf(it).left }
        else buttons.maxByOrNull { boundsOf(it).left }
    }

    private fun collectClickableImageButtons(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > 4) return
        if (node.className == "android.widget.ImageButton" && node.isClickable) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableImageButtons(child, out, depth + 1)
        }
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    override fun onInterrupt() {
        handler.removeCallbacks(attemptRunnable)
        handler.removeCallbacks(modeCheckRunnable)
    }

    /** Nombre visible de una app instalada, o null si no se puede resolver. */
    private fun appLabel(pkg: String): String? = SensitiveApps.label(this, pkg)

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacks(attemptRunnable)
        handler.removeCallbacks(modeCheckRunnable)
        handler.removeCallbacks(resetCheckRunnable)
        super.onDestroy()
    }
}
