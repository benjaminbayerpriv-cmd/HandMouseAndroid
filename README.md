# Hand Mouse v2
Native CameraX + MediaPipe Hand Landmarker background tracking with Accessibility overlay cursor and gestures.

## v3 input + smoothing
- 60 Hz system cursor interpolation independent of MediaPipe frame rate
- small movement deadzone to suppress landmark jitter
- short index-thumb pinch produces one tap on release
- holding/moving pinch transitions into a continued Accessibility drag
- thumb-middle pinch maps to Android long-press / long-press drag
- short tracking dropouts get a release grace period to avoid accidental clicks/releases
