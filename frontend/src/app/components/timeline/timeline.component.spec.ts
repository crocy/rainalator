import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TimelineComponent } from './timeline.component';

describe('TimelineComponent', () => {
  let component: TimelineComponent;
  let fixture: ComponentFixture<TimelineComponent>;

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

  it('should be hidden when no scan times', () => {
    component.scanTimes = [];
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.timeline-bar')).toBeNull();
  });

  it('should show the timeline bar when scan times are provided', () => {
    component.scanTimes = ['2024-06-15T12:30:00Z', '2024-06-15T13:00:00Z'];
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.timeline-bar')).not.toBeNull();
  });

  it('should emit scanSelected when slider changes', () => {
    component.scanTimes = ['2024-06-15T12:30:00Z', '2024-06-15T13:00:00Z'];
    fixture.detectChanges();

    const spy = spyOn(component.scanSelected, 'emit');
    component.onSliderChange({ target: { value: '1' } } as unknown as Event);

    expect(component.currentIndex).toBe(1);
    expect(spy).toHaveBeenCalledWith('2024-06-15T13:00:00Z');
  });

  it('should format timestamp as HH:mm DD/MM', () => {
    const result = component.formatTimestamp('2024-06-15T14:05:00Z');
    // The exact output depends on the local timezone, so just check the format pattern
    expect(result).toMatch(/^\d{2}:\d{2} \d{2}\/\d{2}$/);
  });

  it('should wrap around on next at end', () => {
    component.scanTimes = ['2024-06-15T12:00:00Z', '2024-06-15T12:30:00Z'];
    component.currentIndex = 1;

    const spy = spyOn(component.scanSelected, 'emit');
    component.next();

    expect(component.currentIndex).toBe(0);
    expect(spy).toHaveBeenCalledWith('2024-06-15T12:00:00Z');
  });

  it('should wrap around on previous at start', () => {
    component.scanTimes = ['2024-06-15T12:00:00Z', '2024-06-15T12:30:00Z'];
    component.currentIndex = 0;

    const spy = spyOn(component.scanSelected, 'emit');
    component.previous();

    expect(component.currentIndex).toBe(1);
    expect(spy).toHaveBeenCalledWith('2024-06-15T12:30:00Z');
  });

  it('should clear interval on destroy', () => {
    component.scanTimes = ['2024-06-15T12:00:00Z', '2024-06-15T12:30:00Z'];
    component.togglePlay();
    expect(component.playing).toBeTrue();
    expect(component.playInterval).not.toBeNull();

    component.ngOnDestroy();
    expect(component.playing).toBeFalse();
    expect(component.playInterval).toBeNull();
  });
});
