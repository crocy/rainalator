import {
  describeMinutes,
  formatClockTime,
  formatDayMonth,
  formatFramePeriod,
} from './time-format';

describe('describeMinutes', () => {
  it('reports sub-hour spans in minutes', () => {
    expect(describeMinutes(45)).toBe('45 minutes');
  });

  it('uses the singular for exactly one minute', () => {
    expect(describeMinutes(1)).toBe('1 minute');
  });

  it('uses the singular for exactly one hour', () => {
    expect(describeMinutes(60)).toBe('1 hour');
  });

  it('reports an exact day as a day', () => {
    expect(describeMinutes(1440)).toBe('1 day');
  });

  it('reports a part-day span in hours rather than fractional days', () => {
    expect(describeMinutes(2160)).toBe('36 hours');
  });

  it('reports multi-day spans in days', () => {
    expect(describeMinutes(4320)).toBe('3 days');
  });

  it('has no description for a non-positive span', () => {
    expect(describeMinutes(0)).toBeNull();
    expect(describeMinutes(-30)).toBeNull();
  });

  it('has no description for an unparseable span', () => {
    expect(describeMinutes(NaN)).toBeNull();
  });
});

describe('formatClockTime / formatDayMonth', () => {
  it('zero-pads the wall clock time', () => {
    expect(formatClockTime(new Date(2024, 5, 15, 4, 5))).toBe('04:05');
  });

  it('zero-pads the day and month', () => {
    expect(formatDayMonth(new Date(2024, 5, 3))).toBe('03/06');
  });
});

describe('formatFramePeriod', () => {
  const iso = (d: Date) => d.toISOString();

  it('shows a single date when both ends fall on the same day', () => {
    const start = new Date(2024, 5, 15, 8, 0);
    const end = new Date(2024, 5, 15, 14, 0);
    expect(formatFramePeriod(iso(start), iso(end))).toBe('08:00–14:00 15/06');
  });

  it('dates both ends when the frame crosses midnight', () => {
    const start = new Date(2024, 5, 15, 18, 0);
    const end = new Date(2024, 5, 16, 6, 0);
    expect(formatFramePeriod(iso(start), iso(end))).toBe('15/06 18:00 – 16/06 06:00');
  });

  it('keeps a whole-day frame unambiguous by dating both ends', () => {
    const start = new Date(2024, 5, 15, 0, 0);
    const end = new Date(2024, 5, 16, 0, 0);
    expect(formatFramePeriod(iso(start), iso(end))).toBe('15/06 00:00 – 16/06 00:00');
  });
});
