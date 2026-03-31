import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { QueryPanelComponent } from './query-panel.component';

describe('QueryPanelComponent', () => {
  let component: QueryPanelComponent;
  let fixture: ComponentFixture<QueryPanelComponent>;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [QueryPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(QueryPanelComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('analyze button disabled when no polygon', () => {
    component.polygon = null;
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.analyze-btn');
    expect(button.disabled).toBeTrue();
  });

  it('analyze button enabled when polygon is set', () => {
    component.polygon = 'POLYGON((14.5 46.0,14.6 46.0,14.6 46.1,14.5 46.1,14.5 46.0))';
    component.loading = false;
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.analyze-btn');
    expect(button.disabled).toBeFalse();
  });

  it('analyze button disabled when loading', () => {
    component.polygon = 'POLYGON((14.5 46.0,14.6 46.0,14.6 46.1,14.5 46.1,14.5 46.0))';
    component.loading = true;
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.analyze-btn');
    expect(button.disabled).toBeTrue();
  });

  it('load radar button is always enabled', () => {
    component.polygon = null;
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.load-radar-btn');
    expect(button.disabled).toBeFalse();
  });

  it('load radar button disabled when loading scans', () => {
    component.loadingScans = true;
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.load-radar-btn');
    expect(button.disabled).toBeTrue();
  });

  it('loadRadar emits scanTimesLoaded', () => {
    const spy = spyOn(component.scanTimesLoaded, 'emit');
    component.fromDate = '2024-06-15T12:00';
    component.toDate = '2024-06-15T14:00';

    component.loadRadar();

    const req = httpTesting.expectOne((r) => r.url === '/api/rainfall/scans');
    req.flush({ scanTimes: ['2024-06-15T12:00:00Z', '2024-06-15T12:05:00Z'] });

    expect(spy).toHaveBeenCalledWith(['2024-06-15T12:00:00Z', '2024-06-15T12:05:00Z']);
    expect(component.loadingScans).toBeFalse();
  });
});
