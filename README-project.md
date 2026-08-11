# Hand Mouse Android

Systemweite Hand-Maus für Android. Die Handerkennung ist die gleiche lokale MediaPipe/WASM-Logik wie in der v7-Webdemo.

## Gesten
- Daumen + Zeigefinger: Touch/Linksklick; halten und bewegen = Drag
- Daumen + Mittelfinger, Zeigefinger getrennt: Android-Long-Press; halten und bewegen = Long-Press-Drag
- Faust (alle vier langen Finger gekrümmt): Cursor friert ein
- Cursorposition: Mittelpunkt zwischen Daumen- und Zeigefingerspitze

## Installation / Build
1. Projekt in Android Studio öffnen.
2. Android SDK 37 installieren.
3. `Build > Build APK(s)`.
4. APK installieren.
5. Kamera erlauben.
6. In Android Bedienungshilfen `Hand Mouse Steuerung` aktivieren.
7. Zur App zurückkehren und `Handsteuerung starten` tippen.
8. Home drücken; Cursor bleibt als Accessibility-Overlay systemweit sichtbar.

Die App benötigt keine Internetverbindung. MediaPipe-Modell, JS und WASM liegen komplett in `app/src/main/assets/hand/`.

### Hinweis zum Rechtsklick
Touch-Android hat keine universelle sekundäre Maustaste. Die Rechts-Geste ist deshalb ein Long-Press, der in Android normalerweise die entsprechende Kontextaktion auslöst.
