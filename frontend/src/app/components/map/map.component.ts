import {
  Component,
  EventEmitter,
  Output,
  afterNextRender,
  ElementRef,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
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
        polyline: false,
        circle: false,
        rectangle: false,
        marker: false,
        circlemarker: false,
      } as L.Control.DrawConstructorOptions['draw'],
    });
    this.map.addControl(drawControl);

    this.map.on(L.Draw.Event.CREATED, (event: L.LeafletEvent) => {
      const drawEvent = event as L.DrawEvents.Created;
      // Only allow one polygon at a time
      this.drawnItems.clearLayers();
      const layer = drawEvent.layer as L.Polygon;
      this.drawnItems.addLayer(layer);
      const wkt = this.polygonToWkt(layer);
      this.polygonDrawn.emit(wkt);
    });

    this.map.on(L.Draw.Event.DELETED, () => {
      this.polygonCleared.emit();
    });
  }

  private polygonToWkt(layer: L.Polygon): string {
    const latlngs = layer.getLatLngs()[0] as L.LatLng[];
    const coords = latlngs.map((ll) => `${ll.lng} ${ll.lat}`);
    coords.push(coords[0]); // close the ring
    return `POLYGON((${coords.join(', ')}))`;
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
