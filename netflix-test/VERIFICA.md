# Esito della prova Netflix — 16 ottobre 2026

APK indipendente: `com.fuoconero.netflixbridge.test`, versione `0.1.0-test`, codice 1.
Sorgenti compilati: commit `46f6e93ff157e46114f5571626db4687d2fbdbb5`.
[Compilazione GitHub Actions riuscita](https://github.com/todinigiovanni-maker/fuoconero-reel-renderer/actions/runs/38922062727).

## Confermato sulla Sony Bravia

- Installazione di un nuovo pacchetto, senza aggiornare o disinstallare le versioni Fuoconero preesistenti, Veezie, IPTV o Netflix. Versioni e date di aggiornamento dei pacchetti controllati invariate prima/dopo.
- Otto test strumentali Android superati. Compilazione e lint riusciti: zero errori, 14 avvisi (tra cui localizzazione, dipendenze/API, provider immagini intenzionalmente esportato e analisi del ciclo di vita dello stream). Il provider è di sola lettura, limitato ai PNG della propria cartella, con test contro l'attraversamento dei percorsi; lo stream immagini viene chiuso con try-with-resources.
- Il sistema presenta il normale dialogo per READ_TV_LISTINGS; il consenso permette al nuovo pacchetto di leggere due voci Netflix pubblicate in Guarda successivo. Nessun permesso privilegiato o accesso ai dati privati Netflix.
- Provenienza verificata tramite package_name esatto, non tramite il nome del titolo.
- Canale separato abilitato e osservato in Projectivy. Due locandine reali, card 960x540, avanzamento proveniente dai dati condivisi e card Apri Netflix.
- Due intent originali risolti verso Netflix. Il clic su una card apre l'attività Netflix; anche il percorso di ripiego Apri Netflix apre l'app.

## Non certificato / ancora da provare

- Arrivo al titolo esatto e riproduzione automatica: l'interfaccia interna Netflix non era leggibile dagli strumenti di controllo. L'attività Netflix in primo piano, da sola, non dimostra riproduzione o scheda corretta. Serve conferma visiva dell'utente sulla TV.
- Funzionamento per tutti i profili, titoli e dispositivi. Solo i contenuti effettivamente condivisi e leggibili possono essere riproposti; questa non è una lettura dell'intera cronologia Netflix.
- Aggiornamento periodico e ritorno dal reboot/standby di questo nuovo pacchetto: pianificati nel codice, ma non ancora collaudati per un ciclo completo. Il precedente test standby della 0.4.3 non certifica il nuovo pacchetto.
- Catalogo aggiuntivo TMDB/JustWatch: fattibilità e requisiti verificati, integrazione non attivata. Richiede credenziali autorizzate e attribuzioni; tali fonti non garantiscono ID o link di riproduzione Netflix.

## Uso

Aprire Fuoconero Netflix TEST, concedere lettura programmi TV, premere Aggiorna riga Netflix e abilitare il canale in Projectivy > Modifica canali. Se non vengono condivisi titoli, la riga conserva Apri Netflix. Le card non inventano episodi, avanzamento o destinazioni mancanti. Se Netflix accetta il collegamento ma mostra solo la home, usare Apri Netflix; non viene tentata navigazione automatica dentro Netflix.

Fonti tecniche e dettagli di sicurezza: [README](README.md).
