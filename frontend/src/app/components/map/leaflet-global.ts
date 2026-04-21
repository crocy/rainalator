// Expose Leaflet's L on window so leaflet-draw (a browser IIFE that
// expects a global `L`) can attach L.Draw and L.GeometryUtil to it.
// Must be evaluated BEFORE `import 'leaflet-draw'`. ES module imports
// evaluate dependency-first, so importing this file before
// 'leaflet-draw' guarantees the correct order even in minified builds
// where hoisting obscures top-level statement order.
import * as L from 'leaflet';

(window as unknown as { L: typeof L }).L = L;
