export const overlayExample = "fd00:1234:5678::3";

export function normalizeOverlay(raw: string): string {
  let value = raw.trim().toLocaleLowerCase();
  if (value.startsWith("::ffff:")) value = value.slice("::ffff:".length);
  if (value.startsWith("[")) value = value.slice(1);
  if (value.endsWith("]")) value = value.slice(0, -1);
  const zone = value.indexOf("%");
  return zone >= 0 ? value.slice(0, zone) : value;
}

export function isOverlayAddress(value: string): boolean {
  if (!value || (value.match(/:/g)?.length ?? 0) < 2 || value.includes(":::")) return false;
  const halves = value.split("::");
  if (halves.length > 2) return false;
  const groups = halves.flatMap((half) => half ? half.split(":") : []);
  if (groups.some((group) => !/^[0-9a-f]{1,4}$/.test(group))) return false;
  return (halves.length === 2 ? groups.length <= 7 : groups.length === 8)
    && groups.some((group) => Number.parseInt(group, 16) !== 0);
}

export function newContactProblem(
  raw: string, ownIp: string | null, savedLabel: (ip: string) => string | null,
): { problem: string | null; ip: string } {
  const ip = normalizeOverlay(raw);
  let problem: string | null;
  if (!ip) problem = "Enter their Nebula IPv6 number.";
  else if (!isOverlayAddress(ip)) problem = `That is not a Nebula IPv6 number. It looks like ${overlayExample}.`;
  else if (ip === ownIp) problem = "That is this phone's own address.";
  else {
    const label = savedLabel(ip);
    problem = label ? `${label} is already saved at ${ip}.` : null;
  }
  return { problem, ip };
}

export function addressFromCode(text: string): string | null {
  let raw = text.trim();
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value === "object" && value !== null && "ip" in value && typeof value.ip === "string") {
      raw = value.ip;
    }
  } catch {
    raw = text;
  }
  const ip = normalizeOverlay(raw);
  return isOverlayAddress(ip) ? ip : null;
}
