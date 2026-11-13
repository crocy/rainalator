/** Describes a span in whichever unit reads most naturally; null when it is not a positive duration. */
export function describeMinutes(minutes: number): string | null {
  if (!Number.isFinite(minutes) || minutes <= 0) return null;

  const rounded = Math.round(minutes);
  if (rounded < 60) return plural(rounded, 'minute');
  if (rounded % 1440 !== 0) return plural(Math.round(rounded / 60), 'hour');
  return plural(rounded / 1440, 'day');
}

export function formatClockTime(date: Date): string {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function formatDayMonth(date: Date): string {
  return `${pad(date.getDate())}/${pad(date.getMonth() + 1)}`;
}

/** Spells out a frame's span, dating both ends only when it straddles more than one day. */
export function formatFramePeriod(startIso: string, endIso: string): string {
  const start = new Date(startIso);
  const end = new Date(endIso);

  if (start.toDateString() === end.toDateString()) {
    return `${formatClockTime(start)}–${formatClockTime(end)} ${formatDayMonth(start)}`;
  }
  return (
    `${formatDayMonth(start)} ${formatClockTime(start)} – ` +
    `${formatDayMonth(end)} ${formatClockTime(end)}`
  );
}

function plural(count: number, unit: string): string {
  return `${count} ${unit}${count === 1 ? '' : 's'}`;
}

function pad(value: number): string {
  return value.toString().padStart(2, '0');
}
