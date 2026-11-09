package com.agustin.videoboostao

/**
 * Selectores de UI de Pixel Camera. Aislados acá porque cada Feature Drop
 * puede cambiarlos: si el servicio deja de encontrar los nodos, volcar la
 * jerarquía con `adb exec-out uiautomator dump /dev/tty` (cámara en modo
 * video, panel de ajustes abierto) y actualizar estas listas.
 */
object Selectors {

    const val CAMERA_PACKAGE = "com.google.android.GoogleCamera"

    /**
     * Botón que abre/despliega el panel de ajustes de video (abajo a la
     * izquierda del visor). Resource-id estable confirmado por
     * `uiautomator dump` en un Pixel 10 Pro XL (Pixel Camera, jul-2026):
     * no depende del idioma del sistema.
     */
    const val VIDEO_SETTINGS_ENTRY_ID = "$CAMERA_PACKAGE:id/options_entry_button"

    /**
     * Chip del carrusel de modos ("Pan", "Blur", "Video", "Magic Capture",
     * "Night Sight", "Slow Motion"). Se usa para confirmar que el modo
     * activo es Video o Magic Capture antes de tocar nada. Los textos localizados del chip y de la fila
     * "Video Boost" viven en [CameraLabels] (extraídos de la APK real de
     * Pixel Camera, 74 idiomas).
     */
    const val MODE_CHIP_TEXT_ID = "$CAMERA_PACKAGE:id/mode_chip_text"

    /**
     * Selector de "supermodos" (Magic Capture / Photo / Video) de Pixel
     * Camera en Pixel 11+. Es Compose: cada opción es un nodo `checkable`
     * cuyo resource-id es un testTag ("camera_supermode", "video_supermode";
     * el de Magic Capture vino como "Magic Capture"), con un hijo cuya
     * contentDescription es el nombre del modo. El activo se marca con
     * isChecked, no isSelected. En Magic Capture el carrusel de
     * [MODE_CHIP_TEXT_ID] no existe. Confirmado con `uiautomator dump`
     * (Pixel 11, nov-2026).
     */
    const val SUPERMODE_SWITCHER_FRAME_ID = "$CAMERA_PACKAGE:id/supermode_switcher_frame"

    /** testTags conocidos del supermodo Magic Capture (no localizados). */
    val MAGIC_CAPTURE_SUPERMODE_TAGS = setOf("Magic Capture", "magic_capture_supermode")

    /**
     * Barra de indicadores arriba a la izquierda del visor. Con Video Boost
     * activo contiene un ImageView con contentDescription "Video Boost on"
     * (sapphire_on_desc); apagado, la barra está vacía. Permite leer el
     * estado sin abrir el panel. Confirmado en Magic Capture (Pixel 11).
     */
    const val MINIBAR_ID = "$CAMERA_PACKAGE:id/new_minibar"
}
