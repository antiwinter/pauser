/**
 * parser.ts — parses Telegram message text for movie metadata (pure function).
 * No host calls — regex-based extraction from user-submitted text posts.
 */
export interface ParsedMovieInfo {
  title: string;
  year: number | null;
  rating: number | null;
  genres: string[];
  imdbId: string | null;
  tmdbId: string | null;
  overview: string | null;
}

/** Known genre hashtags and emoji markers. */
const GENRE_EMOJI: Record<string, string> = {
  '🎬': 'Drama',
  '💥': 'Action',
  '😂': 'Comedy',
  '👻': 'Horror',
  '🔫': 'Thriller',
  '❤️': 'Romance',
  '🚀': 'Sci-Fi',
  '🎭': 'Drama',
  '🗡️': 'Action',
  '🧟': 'Horror',
  '🤖': 'Sci-Fi',
  '👽': 'Sci-Fi',
  '🕵️': 'Mystery',
  '🔍': 'Mystery',
  '🎵': 'Musical',
  '📺': 'TV Series',
  '🎞️': 'Film',
  '🏆': 'Award',
};

export function parseMessageText(text: string): ParsedMovieInfo {
  // Title + year: "Movie Name (2023)" or "#Movie Name (2023)"
  const titleYearMatch = text.match(/^[\W_]*(.+?)\s*\((\d{4})\)\s*$/m);
  const title = titleYearMatch ? titleYearMatch[1].trim() : extractFirstLine(text);
  const year = titleYearMatch ? parseInt(titleYearMatch[2], 10) : null;

  // Rating: IMDB 8.5, ⭐ 7.5, IMDb: 8/10, etc.
  const ratingMatch = text.match(/(?:IMDB|IMDb|⭐|★|Rating|评分)\s*[:\s]?\s*([\d.]+)\s*(?:\/\s*10)?/i);
  const rating = ratingMatch ? clampRating(parseFloat(ratingMatch[1])) : null;

  // Genres from hashtags (#action #drama)
  const hashtagGenres = (text.match(/#(\w+)/g) ?? [])
    .map((t) => t.slice(1).toLowerCase())
    .map(normalizeGenre);

  // Genres from emoji markers
  const emojiGenres: string[] = [];
  for (const [emoji, genre] of Object.entries(GENRE_EMOJI)) {
    if (text.includes(emoji)) emojiGenres.push(genre);
  }

  const genres = [...new Set([...hashtagGenres, ...emojiGenres])];

  // External provider IDs
  const imdbMatch = text.match(/imdb\.com\/title\/(tt\d+)/i);
  const tmdbMatch = text.match(/themoviedb\.org\/(?:movie|tv)\/(\d+)/i);

  return {
    title: title || '',
    year,
    rating,
    genres,
    imdbId: imdbMatch?.[1] ?? null,
    tmdbId: tmdbMatch?.[1] ?? null,
    overview: buildOverview(text),
  };
}

/** Extract the first non-empty line as a fallback title. */
function extractFirstLine(text: string): string {
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    if (trimmed.length > 0 && !trimmed.startsWith('#')) return trimmed;
  }
  return '';
}

/** Normalize a hashtag genre string to a proper genre name. */
function normalizeGenre(tag: string): string {
  const map: Record<string, string> = {
    action: 'Action',
    adventure: 'Adventure',
    comedy: 'Comedy',
    crime: 'Crime',
    drama: 'Drama',
    fantasy: 'Fantasy',
    horror: 'Horror',
    mystery: 'Mystery',
    romance: 'Romance',
    scifi: 'Sci-Fi',
    'sci-fi': 'Sci-Fi',
    thriller: 'Thriller',
    war: 'War',
    western: 'Western',
    animation: 'Animation',
    documentary: 'Documentary',
    family: 'Family',
    history: 'History',
    music: 'Music',
    tvseries: 'TV Series',
  };
  return map[tag] ?? capitalize(tag);
}

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Clamp rating to 0-10 range. */
function clampRating(r: number): number {
  if (isNaN(r) || r <= 0) return null as unknown as number;
  return r > 10 ? 10 : r;
}

/** Build a short overview from the message text. */
function buildOverview(text: string): string | null {
  // Strip common prefixes and URLs for a cleaner overview
  let cleaned = text
    .replace(/^[\W_]*.+?\s*\(\d{4}\)\s*/m, '')  // remove title line
    .replace(/https?:\/\/\S+/g, '')                // remove URLs
    .replace(/#\w+/g, '')                          // remove hashtags
    .replace(/\s+/g, ' ')
    .trim();

  if (cleaned.length === 0) return null;
  if (cleaned.length > 500) cleaned = cleaned.slice(0, 500) + '...';
  return cleaned;
}
