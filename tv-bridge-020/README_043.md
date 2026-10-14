# Fuoconero TV Bridge 0.4.3 SAFE TEST

Ramo di partenza: fuoconero-tv-bridge-042-playlist-names-tv-controls.
Applicazione separata: com.fuoconero.tvbridge.test043.
La 0.3.6, la 0.3.7 e Fuoconero IPTV 0.5.3 non sono aggiornate né disinstallate.

## Home e card

La riga **🔥 FUOCONERO — CONTINUA A GUARDARE** raccoglie fino a 20 elementi recenti
IPTV e Veezie/Trakt, ordinati per attività registrata. Gli elementi identificati
come adulti restano esclusi. Un'apertura senza tempi verificati è etichettata
come apertura: non equivale a visione o completamento.

Le anteprime di questa versione dichiarano 16:9 e usano immagini 960×540.
Le immagini verticali originali restano in cache; le nuove card ne conservano
l'immagine e aggiungono un pannello leggibile per titolo, episodio e stato.
Le immagini mancanti producono una scheda con testo, non un'immagine inventata.
La schermata principale nell'app mostra righe orizzontali, card da 310 dp,
bordo arancione e zoom della selezione. OK apre, pressione lunga di OK rimuove
un elemento dai recenti. Le identità conservano fonte e ID del contenuto;
fra Veezie/Trakt e IPTV si uniscono soltanto contenuti con ID TMDB verificato
uguale (e stessa stagione/puntata per le serie). Titoli simili o remake non
vengono uniti arbitrariamente.

Android TV consente di dichiarare la proporzione dell'immagine; non offre
un parametro per imporre la larghezza in pixel delle card di Projectivy.
Dimensione, zoom e impaginazione nella home dipendono dalle impostazioni
del launcher.

- [Attributi delle anteprime Android TV](https://developer.android.com/training/tv/discovery/video-programs)
- [Impostazioni e aggiornamenti Projectivy](https://github.com/spocky/miproja1/releases)

## Veezie e prossima puntata

Si riutilizzano la navigazione Veezie e la lettura del player già sviluppate.
La posizione viene letta soltanto dai campi verificati del player integrato
di Veezie quando i comandi sono visibili e il titolo è associabile senza
ambiguità. Player esterni e titoli sconosciuti non ricevono percentuali inventate.

Trakt determina gli episodi completati e il prossimo episodio. Una conferma
Trakt più recente della vecchia osservazione Veezie impedisce di riproporre
la puntata completata; la cache della prossima puntata viene invalidata.
Quando non si conosce una prossima puntata, si apre la scheda della serie per
la scelta manuale. L'app non invia eventi watched/scrobble in base a un clic.

Un nuovo pacchetto Android non può leggere autonomamente la configurazione
privata delle versioni precedenti. Il trasferimento per collaudo è opzionale
e in sola lettura verso la sorgente. Un access token copiato per il test non
rinnova il refresh token della versione stabile: ciò evita che il rinnovo
della copia interferisca con l'autorizzazione originale. Alla sua scadenza
serve un'autorizzazione indipendente per la 0.4.3. Un'autorizzazione autonoma
si rinnova automaticamente e non viene richiesta ad ogni avvio.

## EPG

**Guida TV** importa XMLTV da URL o dal selettore file Android, anche gzip.
Per playlist con struttura Xtream si tenta la guida xmltv.php della stessa
sorgente già configurata; il tentativo non garantisce che il fornitore la
offra. Un URL personalizzato o file scelto dall'utente ha la precedenza.

La guida viene associata prima a tvg-id e, se manca, a un nome normalizzato
con corrispondenza unica. Nomignoli/alias non vengono indovinati.
I programmi attuale e successivo, gli orari e la barra del programma sono
consultabili nell'app e nelle anteprime dei canali. La barra EPG indica la
posizione nel palinsesto, non quanto l'utente ha guardato.
Una guida errata non cancella la cache precedente. Se i dati non coprono
l'ora attuale, viene mostrato **Guida non disponibile**.

Formato supportato per gli orari: YYYYMMDDhhmmss ±hhmm; senza offset si
usa UTC come previsto da XMLTV. Si conserva una finestra limitata di
programmazione; il parser è streaming, con limiti di dimensione e quantità.

- [Formato XMLTV](https://github.com/XMLTV/xmltv/blob/master/xmltv.dtd)

## Aggiornamenti automatici

BOOT_COMPLETED e MY_PACKAGE_REPLACED programmano lavoro in background.
Il servizio di accessibilità limitato a Veezie/Projectivy rileva il ritorno
al launcher e richiede un aggiornamento differito, senza importazioni nel
thread dell'interfaccia. Esiste anche un controllo periodico locale ogni
15 minuti e l'aggiornamento Trakt periodico.

La sincronizzazione Trakt rispetta almeno 4 minuti tra tentativi e il cooldown
server HTTP 429; i dettagli della prossima puntata sono richiesti in numero
limitato. Playlist e metadati usano intervalli di 12 ore; XMLTV 6 ore.
I tentativi automatici IPTV/EPG falliti attendono almeno 30 minuti.
La pubblicazione locale può aggiornare il programma EPG senza riscaricare
la guida. Non viene aperto automaticamente il flusso di autorizzazione.

Android può posticipare JobScheduler con rete assente, standby o restrizioni
in background. Accendere il pannello dopo standby non equivale sempre a un
riavvio Android: l'accessibilità e il controllo periodico coprono questo caso
nei limiti consentiti. Non è garantito un aggiornamento istantaneo.

## Installazione e Projectivy

1. Scarica Fuoconero-TV-Bridge-0.4.3-safe-test dall'esecuzione GitHub Actions.
2. Estrai app-debug.apk e installa **0.4.3 SAFE TEST** come applicazione separata:

   ~~~powershell
   .\adb.exe connect 192.168.1.10:5555
   .\adb.exe install -r "C:\percorso\app-debug.apk"
   ~~~

3. Apri la nuova app almeno una volta. Importa la playlist già tua oppure usa
   il trasferimento di collaudo; configura Trakt e la guida quando necessario.
4. Per navigazione Veezie/avvio da Projectivy, abilita in Impostazioni TV >
   Accessibilità **Fuoconero 0.4.3 - Veezie e aggiornamento home**.
5. In Projectivy > Modifica canali > **Fuoconero TV Bridge 0.4.3 SAFE TEST**
   abilita **🔥 FUOCONERO — CONTINUA A GUARDARE** e le categorie desiderate.
6. Per una sola riga visibile complessiva, puoi nascondere le vecchie righe
   Continua/Veezie-Trakt nelle impostazioni di Projectivy, senza cancellare
   le applicazioni o i loro dati. Non è obbligatorio per il primo test.
7. Se vuoi card ancora più grandi nella home, regola dimensione e zoom nelle
   impostazioni Aspetto/Card di Projectivy. Questa app non cambia le
   impostazioni grafiche delle altre applicazioni.

## Collaudo

GitHub Actions compila APK principale e APK dei test Android.
La suite Core043Test usa database di test separati e verifica:
orari XMLTV, now/next, corrispondenza univoca, rollback su file errato,
esclusione adulti, deduplicazione/ordine/rimozione dei recenti,
precedenza di Trakt sul vecchio avanzamento e mantenimento ID/preferiti M3U.

~~~powershell
.\adb.exe install -r "C:\percorso\app-debug-androidTest.apk"
.\adb.exe shell am instrument -w com.fuoconero.tvbridge.test043.test/androidx.test.runner.AndroidJUnitRunner
~~~

La compilazione non prova da sola comportamento e grafica su Sony Bravia.
Vanno verificati separatamente: dimensioni effettive Projectivy, navigazione
dei titoli Veezie disponibili, programmi reali del proprio fornitore,
aggiornamenti dopo standby/riavvio e avanzamento della prossima puntata
dopo una nuova conferma effettiva da Trakt.

