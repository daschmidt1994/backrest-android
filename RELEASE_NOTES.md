**Korrektur: Remote-Instanzen zeigen keinen veralteten Status mehr**
- Konnte die App den Status der Remote-Instanzen nicht abrufen, zeigte sie bisher kommentarlos den letzten gespeicherten Stand – inklusive „verbunden“ und einem tagealten „Kontakt vor …“.
- Jetzt steht dort „Status nicht abrufbar (Fehler) · Stand <Datum>“, das Widget zeigt „Status unbekannt“.
- Ein alter Stand löst keine „getrennt“-Benachrichtigung aus, weil er über die Remote-Instanz selbst nichts aussagt.
