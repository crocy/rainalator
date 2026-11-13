import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TimelineComponent } from './timeline.component';
import { Frame } from '../../models/rainfall.models';

describe('TimelineComponent', () => {
  let component: TimelineComponent;
  let fixture: ComponentFixture<TimelineComponent>;

  const frame = (start: Date, end: Date, partial = false): Frame => ({
    start: start.toISOString(),
    end: end.toISOString(),
    partial,
  });

  const scanFrames = (): Frame[] => [
    frame(new Date(2024, 5, 15, 12, 30), new Date(2024, 5, 15, 12, 35)),
    frame(new Date(2024, 5, 15, 12, 35), new Date(2024, 5, 15, 12, 40)),
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TimelineComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(TimelineComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should be hidden when there are no frames', () => {
    component.frames = [];
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.timeline-bar')).toBeNull();
  });

  it('should show the timeline bar when frames are provided', () => {
    component.frames = scanFrames();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.timeline-bar')).not.toBeNull();
  });

  it('should emit frameSelected when slider changes', () => {
    const frames = scanFrames();
    component.frames = frames;
    fixture.detectChanges();

    const spy = spyOn(component.frameSelected, 'emit');
    component.onSliderChange({ target: { value: '1' } } as unknown as Event);

    expect(component.currentIndex).toBe(1);
    expect(spy).toHaveBeenCalledWith(frames[1]);
  });

  it('should restart at the first frame when a new sequence arrives', () => {
    component.frames = scanFrames();
    component.currentIndex = 1;

    component.frames = scanFrames();

    expect(component.currentIndex).toBe(0);
  });

  describe('frame labels', () => {
    it('shows the scan time for a per-scan frame', () => {
      const label = component.formatFrame(
        frame(new Date(2024, 5, 15, 14, 5), new Date(2024, 5, 15, 14, 10))
      );
      expect(label).toBe('14:05 15/06');
    });

    it('shows the span for a bucket', () => {
      const label = component.formatFrame(
        frame(new Date(2024, 5, 15, 8, 0), new Date(2024, 5, 15, 14, 0))
      );
      expect(label).toBe('08:00–14:00 15/06');
    });

    it('dates both ends for a bucket that crosses midnight', () => {
      const label = component.formatFrame(
        frame(new Date(2024, 5, 15, 20, 0), new Date(2024, 5, 16, 2, 0))
      );
      expect(label).toBe('15/06 20:00 – 16/06 02:00');
    });

    it('renders the current frame label in the bar', () => {
      component.frames = [frame(new Date(2024, 5, 15, 8, 0), new Date(2024, 5, 15, 14, 0))];
      fixture.detectChanges();

      const el: HTMLElement = fixture.nativeElement.querySelector('.timestamp');
      expect(el.textContent!.trim()).toBe('08:00–14:00 15/06');
    });
  });

  it('should wrap around on next at end', () => {
    const frames = scanFrames();
    component.frames = frames;
    component.currentIndex = 1;

    const spy = spyOn(component.frameSelected, 'emit');
    component.next();

    expect(component.currentIndex).toBe(0);
    expect(spy).toHaveBeenCalledWith(frames[0]);
  });

  it('should wrap around on previous at start', () => {
    const frames = scanFrames();
    component.frames = frames;
    component.currentIndex = 0;

    const spy = spyOn(component.frameSelected, 'emit');
    component.previous();

    expect(component.currentIndex).toBe(1);
    expect(spy).toHaveBeenCalledWith(frames[1]);
  });

  it('should clear interval on destroy', () => {
    component.frames = scanFrames();
    component.togglePlay();
    expect(component.playing).toBeTrue();
    expect(component.playInterval).not.toBeNull();

    component.ngOnDestroy();
    expect(component.playing).toBeFalse();
    expect(component.playInterval).toBeNull();
  });

  it('should have speed presets', () => {
    expect(component.speedPresets).toEqual([0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3, 5]);
  });

  it('should default to 1x speed', () => {
    expect(component.playbackSpeed).toBe(1);
  });

  it('should change playback speed', () => {
    component.onSpeedChange({ target: { value: '2' } } as unknown as Event);
    expect(component.playbackSpeed).toBe(2);
  });

  it('should restart playback when speed changes during play', () => {
    component.frames = scanFrames();
    component.togglePlay();
    expect(component.playing).toBeTrue();
    const oldInterval = component.playInterval;

    component.onSpeedChange({ target: { value: '2' } } as unknown as Event);

    expect(component.playing).toBeTrue();
    expect(component.playInterval).not.toBe(oldInterval);
  });

  it('should show speed selector when frames are provided', () => {
    component.frames = scanFrames();
    fixture.detectChanges();
    const select = fixture.nativeElement.querySelector('.speed-select') as HTMLSelectElement;
    expect(select).not.toBeNull();
    expect(select.options.length).toBe(11);
  });

  it('counts frames rather than scans', () => {
    component.frames = scanFrames();
    fixture.detectChanges();
    const counter: HTMLElement = fixture.nativeElement.querySelector('.frame-count');
    expect(counter.textContent!.replace(/\s+/g, '')).toBe('1/2');
  });
});
