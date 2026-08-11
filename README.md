# Hand Mouse Android v5

Low-latency system-wide Android hand mouse.

## v5 changes
- Cursor anchor = exact midpoint between thumb tip and index tip.
- Much less latency: 640x480 analysis, KEEP_ONLY_LATEST, only one light tracking filter.
- Higher sensitivity: central camera region maps to the whole display, so screen edges are reachable without leaving frame.
- Left pinch is stricter and requires two confident frames.
- Short left pinch -> one tap on release.
- Hold left pinch ~430 ms -> drag. Movement alone never starts drag.
- Thumb + middle finger with index separated -> long press / hold-drag.
- Accessibility button aim assist magnetically attracts nearby clickable controls.
- If a clickable accessibility node is near the cursor, left click uses ACTION_CLICK for reliability, otherwise it falls back to a physical accessibility tap.
- Fist freezes cursor and cancels active input.
