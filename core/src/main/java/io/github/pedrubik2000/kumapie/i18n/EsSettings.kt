package io.github.pedrubik2000.kumapie.i18n

internal val esSettings: Map<String, String> = mapOf(
    // Settings screen
    "Welcome to kumapie" to "Bienvenido a kumapie",
    "Settings" to "Ajustes",
    "Back" to "Volver",
    "Menu language" to "Idioma del menú",
    "Phone's language" to "Idioma del teléfono",
    "Server" to "Servidor",
    "Checking…" to "Comprobando…",
    "Connected: %1\$s episodes in %2\$s." to "Conectado: %1\$s episodios en %2\$s.",
    "No answer from %1\$s: %2\$s" to "Sin respuesta de %1\$s: %2\$s",
    "Save" to "Guardar",
    "On this device" to "En este dispositivo",
    "Downloads: %.1f GB" to "Descargas: %.1f GB",
    "Remove all downloads" to "Borrar todas las descargas",
    "Nothing waiting to be sent to the PC." to "Nada pendiente de enviar a la PC.",
    "%d reports waiting for the PC (sent when it answers)." to "%d informes esperando a la PC (se envían cuando responda).",
    "Send now" to "Enviar ahora",
    "Looking for updates…" to "Buscando actualizaciones…",
    "This is the newest version." to "Esta es la versión más reciente.",
    "Couldn't check: %s" to "No se pudo comprobar: %s",
    "Check for updates" to "Buscar actualizaciones",
    "Version %s" to "Versión %s",

    // Word colours (German)
    "Word colours from Anki" to "Colores de palabras desde Anki",
    "German model: ready (de_core_news_lg)." to "Modelo de alemán: listo (de_core_news_lg).",
    "German model: %s" to "Modelo de alemán: %s",
    "The German model (about 550 MB, once) finds each word's form like morphs on the PC." to
        "El modelo de alemán (unos 550 MB, una vez) encuentra la forma de cada palabra como morphs en la PC.",
    "Download the German model" to "Descargar el modelo de alemán",
    "No kuma3 Anki or AnkiDroid on this device." to "No hay kuma3 Anki ni AnkiDroid en este dispositivo.",
    "Allow reading Anki" to "Permitir leer Anki",
    "Anki: %s." to "Anki: %s.",
    "kuma3 test build" to "kuma3 de prueba",
    "Known from stability (days)" to "Conocida desde estabilidad (días)",
    "Reading Anki…" to "Leyendo Anki…",
    "Read Anki now" to "Leer Anki ahora",
    "Working out the order…" to "Calculando el orden…",
    "Order new cards (morphs)" to "Ordenar tarjetas nuevas (morphs)",
    "Apply" to "Aplicar",
    "Like morphs recalc on the PC: after reviewing, new cards with one unknown word come first. Run it on " +
        "one device only (the PC's morphs and this would undo each other's marked-known words)." to
        "Como morphs recalc en la PC: después de repasar, primero salen las tarjetas nuevas con una sola palabra " +
        "desconocida. Úsalo en un solo dispositivo (morphs en la PC y esto desharían las palabras marcadas como conocidas del otro).",

    // Japanese / English words
    "Japanese words" to "Palabras en japonés",
    "Japanese dictionary: ready (Sudachi core)." to "Diccionario de japonés: listo (Sudachi core).",
    "Japanese dictionary: %s" to "Diccionario de japonés: %s",
    "The Japanese dictionary (about 80 MB to download, 200 MB on the device, once) splits Japanese into words." to
        "El diccionario de japonés (unos 80 MB de descarga, 200 MB en el dispositivo, una vez) divide el japonés en palabras.",
    "Download the Japanese dictionary" to "Descargar el diccionario de japonés",
    "Read Japanese cards now" to "Leer tarjetas de japonés ahora",
    "English words" to "Palabras en inglés",
    "English model: ready (%s)." to "Modelo de inglés: listo (%s).",
    "English model: %s" to "Modelo de inglés: %s",
    "Download the English model (about 40 MB)" to "Descargar el modelo de inglés (unos 40 MB)",
    "Read English cards now" to "Leer tarjetas de inglés ahora",

    // Yomitan dictionaries (%s = the language name, lowercase in Spanish)
    "German" to "Alemán",
    "Japanese" to "Japonés",
    "English" to "Inglés",
    "Dictionaries (Yomitan)" to "Diccionarios (Yomitan)",
    "No %s dictionaries yet. Import Yomitan .zip files (e.g. kty-de-en for German, Jitendex for Japanese)." to
        "Aún no hay diccionarios de %s. Importa archivos .zip de Yomitan (p. ej. kty-de-en para alemán, Jitendex para japonés).",
    "updates itself" to "se actualiza solo",
    "Up" to "Subir",
    "Down" to "Bajar",
    "Delete" to "Borrar",
    "Working…" to "Trabajando…",
    "Import %s dictionaries (.zip)" to "Importar diccionarios de %s (.zip)",
    "%1\$d of %2\$d imported." to "%1\$d de %2\$d importados.",
    "Import a folder of %s dictionaries" to "Importar una carpeta de diccionarios de %s",
    "Download the recommended %s dictionaries" to "Descargar los diccionarios de %s recomendados",
    "Every dictionary is up to date." to "Todos los diccionarios están al día.",
    "Check for updates now" to "Buscar actualizaciones ahora",
    "Dictionaries that can update themselves are checked weekly on Wi-Fi." to
        "Los diccionarios que se actualizan solos se revisan cada semana con Wi-Fi.",
    "Delete %s?" to "¿Borrar %s?",
    "You can import it again later." to "Puedes importarlo de nuevo más tarde.",
    "Cancel" to "Cancelar",

    // Word audio
    "Word audio" to "Audio de palabras",
    "Recordings list: %s" to "Lista de grabaciones: %s",
    "Recordings list: offline, built %s (which German words have a person's recording)." to
        "Lista de grabaciones: sin conexión, creada %s (qué palabras en alemán tienen la grabación de una persona).",
    "Which German words have a person's recording on Wikimedia Commons: a small list (about 3 MB), once." to
        "Qué palabras en alemán tienen la grabación de una persona en Wikimedia Commons: una lista pequeña (unos 3 MB), una vez.",
    "Download the recordings list" to "Descargar la lista de grabaciones",
    "Update the recordings list" to "Actualizar la lista de grabaciones",
    "Speech settings" to "Ajustes de voz",
    "Words are read by a person's recording when Wikimedia Commons has one, else by this voice." to
        "Las palabras se leen con la grabación de una persona si Wikimedia Commons la tiene; si no, con esta voz.",

    // Unlock
    "Unlock" to "Desbloqueo",
    "Show an i+1 scene every time I unlock" to "Mostrar una escena i+1 cada vez que desbloqueo",
    "Allow \"Display over other apps\" so the scene can open when you unlock." to
        "Permite \"Mostrar sobre otras apps\" para que la escena se abra al desbloquear.",
    "Allow display over other apps" to "Permitir mostrar sobre otras apps",
    "%d i+1 scenes to pick from (refreshed when kumapie opens)." to "%d escenas i+1 para elegir (se actualizan al abrir kumapie).",
    "Reading the episodes for i+1 scenes…" to "Leyendo los episodios para escenas i+1…",
    "If the separate Unlock Cards app is still on, turn it off so only one opens." to
        "Si la app Unlock Cards sigue activada, desactívala para que solo se abra una.",

    // New episodes
    "New episodes" to "Episodios nuevos",
    "Transcription" to "Transcripción",
    "Soniox (best, paid)" to "Soniox (el mejor, de pago)",
    "Parakeet on the tablet (free, offline)" to "Parakeet en la tableta (gratis, sin conexión)",
    "Parakeet: ready. More mistakes than Soniox (about 1 word in 9 differed in a test)." to
        "Parakeet: listo. Más errores que Soniox (alrededor de 1 palabra de cada 9 cambió en una prueba).",
    "Parakeet: %s" to "Parakeet: %s",
    "Download Parakeet (about 640 MB, once)" to "Descargar Parakeet (unos 640 MB, una vez)",
    "Soniox API key" to "Clave API de Soniox",
    "Real-Debrid token (real-debrid.com/apitoken)" to "Token de Real-Debrid (real-debrid.com/apitoken)",
    "Jimaku API key (jimaku.cc > Account), Japanese subtitles" to "Clave API de Jimaku (jimaku.cc > Account), subtítulos en japonés",
    "Soniox (best, no extra cost)" to "Soniox (el mejor, sin costo extra)",
    "Gemma on the tablet (free, offline, good)" to "Gemma en la tableta (gratis, sin conexión, buena)",
    "Google's translator (instant, rough)" to "Traductor de Google (al instante, aproximado)",
    "None (no translation, fastest)" to "Ninguno (sin traducción, lo más rápido)",
    "With Parakeet there is no Soniox: the English then comes from Google's translator." to
        "Con Parakeet no hay Soniox: el inglés sale entonces del traductor de Google.",
    "Gemma: ready. It takes about 3 seconds a line (some 15 minutes for a 10-minute video) in the background." to
        "Gemma: lista. Tarda unos 3 segundos por línea (unos 15 minutos para un video de 10 minutos) en segundo plano.",
    "Gemma: %s" to "Gemma: %s",
    "Download Gemma (about 2.8 GB, once)" to "Descargar Gemma (unos 2.8 GB, una vez)",
    "Add episodes with + on the home screen (YouTube, magnets, Real-Debrid links, video files), or share a link to kumapie." to
        "Agrega episodios con + en la pantalla de inicio (YouTube, magnets, enlaces de Real-Debrid, archivos de video) o comparte un enlace con kumapie.",

    // Followed channels
    "Followed channels" to "Canales seguidos",
    "None yet. In Add an episode, paste a channel's video, pick its shorts or videos and turn on Follow." to
        "Ninguno todavía. En Agregar un episodio, pega un video del canal, elige sus shorts o videos y activa Seguir.",
    "shorts" to "shorts",
    "videos" to "videos",
    "%d a night" to "%d por noche",
    "checked %s" to "revisado %s",
    "not checked yet" to "aún sin revisar",
    "Remove" to "Quitar",
    "Download between" to "Descargar entre las",
    "and" to "y las",
    "o'clock, on Wi-Fi" to "horas, con Wi-Fi",
    "Check now" to "Revisar ahora",
    "I speak" to "Hablo",
    "Meanings and the second subtitle line come in this language." to "Los significados y la segunda línea de subtítulos salen en este idioma.",
    "Order mined English cards and unlock definitions" to "Ordenar las tarjetas de inglés y desbloquear definiciones",
    "This device belongs to" to "Este dispositivo es de",
    "Episodes made here go to the PC, and the PC's new episodes come here (on Wi-Fi)." to "Los episodios hechos aquí van a la PC, y los nuevos de la PC llegan aquí (con Wi-Fi).",
    "Nobody (no sync)" to "Nadie (sin sincronizar)",

    // Copy between devices (Wi-Fi)
    "Another device on this Wi-Fi" to "Otro dispositivo en este Wi-Fi",
    "Copy episodes, dictionaries, models and keys from one kumapie to another, without the PC." to "Copia episodios, diccionarios, modelos y claves de un kumapie a otro, sin la PC.",
    "Sending. On the other device: Receive, then code %1\$s (address %2\$s)." to "Enviando. En el otro dispositivo: Recibir, y el código %1\$s (dirección %2\$s).",
    "Stop sending" to "Dejar de enviar",
    "Send to another device" to "Enviar a otro dispositivo",
    "Copying: %1\$s (%2\$d%%)" to "Copiando: %1\$s (%2\$d%%)",
    "Stop copying" to "Dejar de copiar",
    "Copied: %s." to "Copiado: %s.",
    "Copy failed: %s" to "No se pudo copiar: %s",
    "Receive from another device" to "Recibir de otro dispositivo",
    "Looking for a device that is sending… (or type its address)" to "Buscando un dispositivo que esté enviando… (o escribe su dirección)",
    "Found:" to "Encontrado:",
    "Address" to "Dirección",
    "Code" to "Código",
    "Episodes" to "Episodios",
    "Dictionaries" to "Diccionarios",
    "Models (speech, translation, words)" to "Modelos (voz, traducción, palabras)",
    "Keys and the PC's address" to "Claves y la dirección de la PC",
    "Only what this device doesn't have yet is copied; keys only where this device has none." to "Solo se copia lo que este dispositivo aún no tiene; las claves solo donde no haya ninguna.",
    "Copy" to "Copiar",
    "Wrong code." to "Código incorrecto.",
    "%d dictionaries" to "%d diccionarios",
    "%d models" to "%d modelos",
    "%d keys" to "%d claves",
    "nothing new" to "nada nuevo",
    "Copy between devices" to "Copiar entre dispositivos",
    "Sending to another device" to "Enviando a otro dispositivo",
    "Code %s" to "Código %s",
    "Copying from another device" to "Copiando de otro dispositivo",
)
