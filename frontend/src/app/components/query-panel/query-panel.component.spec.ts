import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { QueryPanelComponent } from './query-panel.component';

describe('QueryPanelComponent', () => {
  let component: QueryPanelComponent;
  let fixture: ComponentFixture<QueryPanelComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [QueryPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(QueryPanelComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
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
});
