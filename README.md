# TanscriptorRVA

Aplicación Android para transcribir reuniones ETCP, analizar acuerdos/propuestas y generar resúmenes PDF.

## Flujo
Seleccionar audio → segmentar → transcribir → analizar → revisar → generar PDF.

La compilación del APK se realiza automáticamente mediante GitHub Actions.

## Versión 1.2.3 · Fragmentos similares de hasta 45 minutos

1. Graba una reunión o pulsa **Cargar audio**.
2. Pulsa **Dividir ≤45 min**. El original se conserva.
3. Revisa la duración de la parte seleccionada. Usa **Anterior** y **Siguiente**.
4. Pulsa **Enviar parte a ChatGPT** o **Compartir parte** para adjuntarla.

**Preparar y enviar** también divide primero el audio y envía el primer fragmento.
Los fragmentos se guardan en `Music/TanscriptorRVA` en Android 10 o posterior;
en Android 9 se guardan dentro de la aplicación y se comparten mediante el botón.
Cambiar de audio no borra los fragmentos terminados.

Se admite división sin recodificar de AAC (M4A/MP4), AMR y AMR-WB.
MP3, WAV y Opus necesitan conversión previa a M4A/AAC. Las grabaciones de esta
aplicación ya se realizan en M4A/AAC. No se requiere subir el audio para dividirlo.

Se calcula el menor número de fragmentos viable sin partir paquetes de audio,
y se distribuye la duración entre ellos de forma equilibrada. Por ejemplo,
una hora se divide en dos partes de aproximadamente 30 minutos y dos horas en
tres partes de aproximadamente 40 minutos. Ninguna parte excede 45 minutos.
Las duraciones pueden variar unos milisegundos por los límites de los paquetes.
En un múltiplo exacto de 45 minutos, si no hay una frontera de paquete adecuada,
puede ser necesario un fragmento adicional para mantener el máximo estricto.
Se planifican las fronteras y se copian después los paquetes una sola vez,
en orden, sin pérdidas, repeticiones ni recodificación. Cada duración se verifica
después de cerrar el M4A y antes de publicar los resultados. Si falla la división,
se eliminan únicamente los archivos de esa operación.

La integración continua comprueba los límites con pruebas unitarias y con audios
reales de 10 segundos, 45 minutos, 45 minutos y 1 segundo, 1 hora y 2 horas en Android.
Compara los paquetes antes y después mediante SHA-256 para detectar pérdidas,
duplicaciones o cambios de orden.

### Compilación

Java 17, Gradle 9.1.0, Android SDK 36 y Build Tools 36.0.0.
`gradle :app:testDebugUnitTest :app:assembleDebug`.
El APK de depuración conserva el identificador de la aplicación, pero instalarlo
como actualización requiere firmarlo con la misma clave que el APK anterior.
La clave privada de la versión 1.2.2 no se incluye en los archivos disponibles.
