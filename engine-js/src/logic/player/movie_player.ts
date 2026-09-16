let cached: Record<string, unknown> | null = null;

export function moviePlayer(): Record<string, unknown> | null {
  if (!cached) {
    const el = document.getElementById("movie_player");
    if (el) cached = el as unknown as Record<string, unknown>;
  }
  return cached;
}
