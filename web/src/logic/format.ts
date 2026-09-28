const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const weekdays = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
const two = (value: number): string => String(value).padStart(2, "0");

function dayStart(timestamp: number): number {
  const date = new Date(timestamp);
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}

export function clock(timestamp: number): string {
  const date = new Date(timestamp);
  return `${two(date.getHours())}:${two(date.getMinutes())}`;
}

export function isSameDay(left: number, right: number): boolean {
  return dayStart(left) === dayStart(right);
}

export function listTime(timestamp: number, now = Date.now()): string {
  const today = dayStart(now);
  const day = dayStart(timestamp);
  const date = new Date(timestamp);
  if (day === today) return clock(timestamp);
  if (today - day <= 86_400_000) return "Yesterday";
  if (today - day < 6 * 86_400_000) return (weekdays[date.getDay()] ?? "").slice(0, 3);
  const dated = `${date.getDate()} ${months[date.getMonth()] ?? ""}`;
  return date.getFullYear() === new Date(now).getFullYear() ? dated : `${dated} ${date.getFullYear()}`;
}

export function stamp(timestamp: number, now = Date.now()): string {
  const day = listTime(timestamp, now);
  const time = clock(timestamp);
  return day === time ? time : `${day} ${time}`;
}

export function dayLabel(timestamp: number, now = Date.now()): string {
  const today = dayStart(now);
  const day = dayStart(timestamp);
  const date = new Date(timestamp);
  if (day === today) return "Today";
  if (today - day <= 86_400_000) return "Yesterday";
  const year = date.getFullYear() !== new Date(now).getFullYear() ? ` ${date.getFullYear()}` : "";
  return `${weekdays[date.getDay()] ?? ""}, ${date.getDate()} ${months[date.getMonth()] ?? ""}${year}`;
}

export function lastSeen(timestamp: number | undefined, now = Date.now()): string {
  if (timestamp === undefined) return "Offline";
  const elapsed = now - timestamp;
  if (elapsed < 60_000) return "Last seen just now";
  if (elapsed < 3_600_000) return `Last seen ${Math.floor(elapsed / 60_000)} min ago`;
  if (isSameDay(timestamp, now)) return `Last seen at ${clock(timestamp)}`;
  return `Last seen ${listTime(timestamp, now).toLocaleLowerCase()} at ${clock(timestamp)}`;
}

export function isMuted(until: number, now: number): boolean {
  return until < 0 || until > now;
}

export function muteLabel(until: number, now: number): string {
  if (until < 0) return "Muted";
  return until <= now ? "Not muted" : `Muted until ${stamp(until, now)}`;
}

export function bytes(value: number): string {
  if (value < 1024) return `${value} B`;
  if (value < 1024 ** 2) return `${(Math.round(value / 102.4) / 10).toFixed(1)} KB`;
  if (value < 1024 ** 3) return `${(Math.round(value / (1024 * 102.4)) / 10).toFixed(1)} MB`;
  return `${(Math.round(value / (1024 ** 2 * 102.4)) / 10).toFixed(1)} GB`;
}

export function duration(milliseconds: number): string {
  const total = Math.max(0, Math.floor(milliseconds / 1_000));
  const hours = Math.floor(total / 3_600);
  const minutes = Math.floor(total % 3_600 / 60);
  const seconds = total % 60;
  return hours > 0 ? `${hours}:${two(minutes)}:${two(seconds)}` : `${two(minutes)}:${two(seconds)}`;
}

export function remaining(until: number, now = Date.now()): string {
  const left = Math.max(0, until - now);
  if (left < 60_000) return `${Math.floor(left / 1_000)}s`;
  if (left < 3_600_000) return `${Math.floor(left / 60_000)}m`;
  if (left < 86_400_000) return `${Math.floor(left / 3_600_000)}h`;
  return `${Math.floor(left / 86_400_000)}d`;
}
