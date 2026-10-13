# Fuoconero TV Bridge 0.2.0
App Android TV standalone per una riga **Fuoconero · Storico Trakt** in Projectivy/Android TV.
La versione 0.1.x aveva già dimostrato la pubblicazione del canale e l'apertura della sola home di Veezie.

## Novità
- Autorizzazione Trakt Device Code: codice in TV, autorizzazione sul telefono. Richiede un Client ID della propria app Trakt, configurabile localmente. Un client secret opzionale è richiesto solo se necessario dall'endpoint della propria app (non condividere).
- Legge un massimo di 20 elementi recenti da /sync/history?extended=full; non è una ripresa al secondo esatto e non garantisce «prossimo episodio».
- Memorizza localmente le immagini WebP Trakt come PNG in un ContentProvider; no hotlink.
- Crea/aggiorna una riga e i singoli programmi basandosi sui loro ID. Query senza filtro SQL per PreviewPrograms.
- Aggiornamento manuale e JobScheduler ogni 6 ore. Evita duplicati.
- Tocco sulla locandina apre la home Veezie, **non l'episodio**.

## Build
Usare Android Studio/Gradle con JDK 17, Gradle 8.9, Android SDK 35:
`gradle -p tv-bridge-020 :app:assembleDebug`
Il debug APK necessita la disinstallazione della precedente versione firmata con una chiave differente.

## Trakt
Registrare una propria app: https://developer.trakt.tv/apps ; non inserire i token in chat.
Le API Trakt possono rifiutare endpoint o l'accesso: rispettare i limiti, i termini e le condizioni del servizio.
La app conserva token e Client ID nelle preferenze PRIVATE della TV, non su server Fuoconero.
Token OAuth da Trakt scadono; l'app prova a rinnovarli con il refresh token se permesso dal provider; altrimenti richiede nuova autorizzazione.
La storia importata da Veezie con lo stesso orario potrebbe comparire in un ordine non accurato.

## Licenze
Codice dimostrativo per uso personale; la app non fornisce video e non facilita l'accesso a flussi non autorizzati. Testato con contenuti di cui si dispone del diritto d'accesso.
