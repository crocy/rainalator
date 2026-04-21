// Expose Leaflet's L on window so leaflet-draw (a browser IIFE that
// expects a global `L`) can attach L.Draw and L.GeometryUtil to it.
//
// Must use `import L from 'leaflet'` (default import), not
// `import * as L`. With esbuild's CJS interop (esModuleInterop: true),
// the default import returns the live CJS module.exports — the same
// mutable object on every import site. A namespace import (`import *`)
// instead returns a per-call snapshot whose properties are copies, so
// mutations by leaflet-draw never surface to other callers.
//
// Must also be evaluated before `import 'leaflet-draw'`. ES module
// imports evaluate dependency-first, so importing this file before
// 'leaflet-draw' in the consumer guarantees the correct order.
import L from 'leaflet';

(window as unknown as { L: typeof L }).L = L;
