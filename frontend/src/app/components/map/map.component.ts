import {
  Component,
  EventEmitter,
  Output,
  afterNextRender,
  ElementRef,
  viewChild,
} from '@angular/core';
// Default import (not namespace) so we reference the live CJS module —
// the same object leaflet-draw mutates via window.L. See leaflet-global.ts.
import L from 'leaflet';
import './leaflet-global'; // sets window.L — must evaluate before leaflet-draw
import 'leaflet-draw';

// Fix Leaflet default icon paths broken by Angular bundling
const iconRetinaUrl = 'node_modules/leaflet/dist/images/marker-icon-2x.png';
const iconUrl = 'node_modules/leaflet/dist/images/marker-icon.png';
const shadowUrl = 'node_modules/leaflet/dist/images/marker-shadow.png';

L.Icon.Default.mergeOptions({
  iconRetinaUrl,
  iconUrl,
  shadowUrl,
});

// Fix leaflet-draw 1.0.4 bugs when bundled with Vite (ES strict mode):
// 1) GeometryUtil.readableArea uses bare `type = ...` (implicit global → ReferenceError)
// 2) Rectangle._onMouseUp has a broken _hasAncestor check in Leaflet 1.9.x
const origReadableArea = L.GeometryUtil.readableArea;
L.GeometryUtil.readableArea = function (area: number, isMetric: any, precision?: any) {
  try {
    return origReadableArea.call(this, area, isMetric, precision);
  } catch {
    // Fallback: return a simple metric string
    if (area >= 1e6) return (area * 1e-6).toFixed(2) + ' km\u00B2';
    if (area >= 1e4) return (area * 1e-4).toFixed(2) + ' ha';
    return area.toFixed(0) + ' m\u00B2';
  }
};

(L.Draw as any).Rectangle.prototype._onMouseUp = function (this: any) {
  (L.Draw as any).SimpleShape.prototype._onMouseUp.call(this);
};

@Component({
  selector: 'app-map',
  standalone: true,
  imports: [],
  template: `<div #mapEl id="map"></div>`,
  styleUrl: './map.component.scss',
})
export class MapComponent {
  @Output() polygonDrawn = new EventEmitter<string>();
  @Output() polygonCleared = new EventEmitter<void>();

  private mapEl = viewChild.required<ElementRef<HTMLDivElement>>('mapEl');
  private map!: L.Map;
  private drawnItems = new L.FeatureGroup();
  private overlay: L.ImageOverlay | null = null;

  constructor() {
    afterNextRender(() => {
      this.initMap();
    });
  }

  private initMap(): void {
    this.map = L.map(this.mapEl().nativeElement, {
      center: [46.12, 14.82],
      zoom: 8,
    });

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution:
        '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(this.map);

    this.map.addLayer(this.drawnItems);

    const drawControl = new L.Control.Draw({
      edit: {
        featureGroup: this.drawnItems,
      },
      draw: {
        polygon: {},
        rectangle: {},
        circle: {},
        polyline: false,
        marker: false,
        circlemarker: false,
      } as L.Control.DrawConstructorOptions['draw'],
    });
    this.map.addControl(drawControl);

    this.map.on(L.Draw.Event.CREATED, (event: L.LeafletEvent) => {
      const drawEvent = event as L.DrawEvents.Created;
      // Only allow one shape at a time
      this.drawnItems.clearLayers();
      this.drawnItems.addLayer(drawEvent.layer);
      const wkt = this.layerToWkt(drawEvent.layer, drawEvent.layerType);
      this.polygonDrawn.emit(wkt);
    });

    this.map.on(L.Draw.Event.DELETED, () => {
      this.polygonCleared.emit();
    });
  }

  private layerToWkt(layer: L.Layer, layerType: string): string {
    if (layerType === 'circle') {
      return this.circleToWkt(layer as L.Circle);
    }
    // rectangle and polygon both expose getLatLngs()
    const polygon = layer as L.Polygon;
    const latlngs = polygon.getLatLngs()[0] as L.LatLng[];
    const coords = latlngs.map((ll) => `${ll.lng} ${ll.lat}`);
    coords.push(coords[0]); // close the ring
    return `POLYGON((${coords.join(', ')}))`;
  }

  private circleToWkt(circle: L.Circle): string {
    const center = circle.getLatLng();
    const radius = circle.getRadius(); // meters
    const points = 64;
    const coords: string[] = [];

    for (let i = 0; i <= points; i++) {
      const angle = (i * 360) / points;
      const point = this.destinationPoint(center, radius, angle);
      coords.push(`${point.lng} ${point.lat}`);
    }

    return `POLYGON((${coords.join(', ')}))`;
  }

  /** Calculate a destination point given a start, distance (m), and bearing (degrees). */
  private destinationPoint(start: L.LatLng, distance: number, bearing: number): L.LatLng {
    const R = 6371000; // Earth radius in meters
    const toRad = (deg: number) => (deg * Math.PI) / 180;
    const toDeg = (rad: number) => (rad * 180) / Math.PI;

    const lat1 = toRad(start.lat);
    const lng1 = toRad(start.lng);
    const brng = toRad(bearing);
    const d = distance / R;

    const lat2 = Math.asin(
      Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(brng)
    );
    const lng2 =
      lng1 +
      Math.atan2(
        Math.sin(brng) * Math.sin(d) * Math.cos(lat1),
        Math.cos(d) - Math.sin(lat1) * Math.sin(lat2)
      );

    return L.latLng(toDeg(lat2), toDeg(lng2));
  }

  setOverlay(
    imageUrl: string,
    bounds: { south: number; west: number; north: number; east: number }
  ): void {
    this.clearOverlay();
    const latLngBounds = L.latLngBounds(
      [bounds.south, bounds.west],
      [bounds.north, bounds.east]
    );
    this.overlay = L.imageOverlay(imageUrl, latLngBounds).addTo(this.map);
  }

  clearOverlay(): void {
    if (this.overlay) {
      this.map.removeLayer(this.overlay);
      this.overlay = null;
    }
  }
}
