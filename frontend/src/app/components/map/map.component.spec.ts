import { NgZone } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MapComponent } from './map.component';
import * as L from 'leaflet';

describe('MapComponent', () => {
  let component: MapComponent;
  let fixture: ComponentFixture<MapComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MapComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(MapComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should apply the leaflet-draw rectangle monkey-patch', () => {
    // The original Rectangle._onMouseUp has two-click logic with _hasAncestor.
    // Our patch delegates to SimpleShape._onMouseUp instead.
    const rectProto = (L.Draw as any).Rectangle.prototype;
    const simpleProto = (L.Draw as any).SimpleShape.prototype;
    // Verify the patched function calls SimpleShape's _onMouseUp
    expect(rectProto._onMouseUp).not.toBe(simpleProto._onMouseUp);
    // Call it with a mock context and verify it delegates
    const callSpy = spyOn(simpleProto, '_onMouseUp');
    const ctx = {};
    rectProto._onMouseUp.call(ctx);
    expect(callSpy).toHaveBeenCalled();
  });

  describe('draw tools', () => {
    let map: L.Map;

    beforeEach(() => {
      map = (component as any).map;
      if (!map) {
        pending('Map did not initialize in test environment');
      }
    });

    it('should emit WKT polygon when a polygon is drawn', () => {
      const spy = spyOn(component.polygonDrawn, 'emit');
      const polygon = L.polygon([
        [46.0, 14.5],
        [46.0, 14.6],
        [46.1, 14.6],
        [46.1, 14.5],
      ]);

      map.fire('draw:created', { layer: polygon, layerType: 'polygon' });

      expect(spy).toHaveBeenCalledTimes(1);
      const wkt = spy.calls.first().args[0] as string;
      expect(wkt).toMatch(/^POLYGON\(\(/);
      expect(wkt).toMatch(/\)\)$/);
      // Should contain all 4 corners
      expect(wkt).toContain('14.5 46');
      expect(wkt).toContain('14.6 46');
      expect(wkt).toContain('14.6 46.1');
      expect(wkt).toContain('14.5 46.1');
      // Ring must be closed (first coord == last coord)
      const coords = wkt.replace('POLYGON((', '').replace('))', '').split(', ');
      expect(coords[0]).toBe(coords[coords.length - 1]);
    });

    it('should emit WKT polygon when a rectangle is drawn', () => {
      const spy = spyOn(component.polygonDrawn, 'emit');
      const rectangle = L.rectangle([
        [46.0, 14.5],
        [46.1, 14.6],
      ]);

      map.fire('draw:created', { layer: rectangle, layerType: 'rectangle' });

      expect(spy).toHaveBeenCalledTimes(1);
      const wkt = spy.calls.first().args[0] as string;
      expect(wkt).toMatch(/^POLYGON\(\(/);
      const coords = wkt.replace('POLYGON((', '').replace('))', '').split(', ');
      // Rectangle: 4 corners + closing point
      expect(coords.length).toBe(5);
      expect(coords[0]).toBe(coords[4]);
    });

    it('should emit WKT polygon approximation when a circle is drawn', () => {
      const spy = spyOn(component.polygonDrawn, 'emit');
      const circle = L.circle([46.05, 14.55], { radius: 5000 });

      map.fire('draw:created', { layer: circle, layerType: 'circle' });

      expect(spy).toHaveBeenCalledTimes(1);
      const wkt = spy.calls.first().args[0] as string;
      expect(wkt).toMatch(/^POLYGON\(\(/);
      const coords = wkt.replace('POLYGON((', '').replace('))', '').split(', ');
      // 64-point approximation + closing point
      expect(coords.length).toBe(65);
      expect(coords[0]).toBe(coords[64]);
    });

    it('circle approximation points should be roughly equidistant from center', () => {
      const spy = spyOn(component.polygonDrawn, 'emit');
      const centerLat = 46.05;
      const centerLng = 14.55;
      const radiusMeters = 5000;
      const circle = L.circle([centerLat, centerLng], { radius: radiusMeters });

      map.fire('draw:created', { layer: circle, layerType: 'circle' });

      const wkt = spy.calls.first().args[0] as string;
      const coords = wkt.replace('POLYGON((', '').replace('))', '').split(', ');
      // Check a few points are approximately 5km from center using haversine
      for (const i of [0, 16, 32, 48]) {
        const [lng, lat] = coords[i].split(' ').map(Number);
        const dist = haversineDistance(centerLat, centerLng, lat, lng);
        expect(dist).toBeCloseTo(radiusMeters, -2); // within ~100m
      }
    });

    it('should clear previous shape when a new one is drawn', () => {
      const clearSpy = spyOn((component as any).drawnItems, 'clearLayers');
      const polygon = L.polygon([[46, 14.5], [46, 14.6], [46.1, 14.5]]);

      map.fire('draw:created', { layer: polygon, layerType: 'polygon' });

      expect(clearSpy).toHaveBeenCalledTimes(1);
    });

    it('should emit polygonCleared on draw:deleted', () => {
      const spy = spyOn(component.polygonCleared, 'emit');

      map.fire('draw:deleted');

      expect(spy).toHaveBeenCalledTimes(1);
    });

    it('should emit updated WKT when a shape is edited', () => {
      const spy = spyOn(component.polygonDrawn, 'emit');
      const rectangle = L.rectangle([
        [46.0, 14.5],
        [46.1, 14.6],
      ]);
      map.fire('draw:created', { layer: rectangle, layerType: 'rectangle' });
      spy.calls.reset();

      rectangle.setBounds(L.latLngBounds([45.0, 13.0], [45.5, 13.5]));
      map.fire('draw:edited', { layers: L.layerGroup([rectangle]) });

      expect(spy).toHaveBeenCalledTimes(1);
      const wkt = spy.calls.first().args[0] as string;
      expect(wkt).toContain('13 45');
      expect(wkt).toContain('13.5 45.5');
    });

    it('should emit updated WKT when an edited circle is saved', () => {
      const spy = spyOn(component.polygonDrawn, 'emit');
      const circle = L.circle([46.05, 14.55], { radius: 5000 });
      map.fire('draw:created', { layer: circle, layerType: 'circle' });
      spy.calls.reset();

      circle.setRadius(8000);
      map.fire('draw:edited', { layers: L.layerGroup([circle]) });

      expect(spy).toHaveBeenCalledTimes(1);
      const wkt = spy.calls.first().args[0] as string;
      // 64-point circle approximation + closing point
      expect(wkt.replace('POLYGON((', '').replace('))', '').split(', ').length).toBe(65);
    });

    // Leaflet handlers are registered outside the Angular zone (afterNextRender),
    // so emits must re-enter the zone or bound inputs go stale until an unrelated
    // change-detection cycle — analyses would run against the previously drawn polygon.
    describe('zone re-entry', () => {
      let zone: NgZone;

      beforeEach(() => {
        zone = TestBed.inject(NgZone);
      });

      it('polygonDrawn emission runs inside the Angular zone when draw:created fires outside it', () => {
        let emittedInZone: boolean | null = null;
        component.polygonDrawn.subscribe(() => {
          emittedInZone = NgZone.isInAngularZone();
        });
        const polygon = L.polygon([[46, 14.5], [46, 14.6], [46.1, 14.5]]);

        zone.runOutsideAngular(() => {
          map.fire('draw:created', { layer: polygon, layerType: 'polygon' });
        });

        expect(emittedInZone).toBeTrue();
      });

      it('polygonDrawn emission runs inside the Angular zone when draw:edited fires outside it', () => {
        const rectangle = L.rectangle([[46.0, 14.5], [46.1, 14.6]]);
        map.fire('draw:created', { layer: rectangle, layerType: 'rectangle' });

        let emittedInZone: boolean | null = null;
        component.polygonDrawn.subscribe(() => {
          emittedInZone = NgZone.isInAngularZone();
        });

        zone.runOutsideAngular(() => {
          map.fire('draw:edited', { layers: L.layerGroup([rectangle]) });
        });

        expect(emittedInZone).toBeTrue();
      });

      it('polygonCleared emission runs inside the Angular zone when draw:deleted fires outside it', () => {
        let emittedInZone: boolean | null = null;
        component.polygonCleared.subscribe(() => {
          emittedInZone = NgZone.isInAngularZone();
        });

        zone.runOutsideAngular(() => {
          map.fire('draw:deleted');
        });

        expect(emittedInZone).toBeTrue();
      });
    });
  });
});

function haversineDistance(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 6371000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}
