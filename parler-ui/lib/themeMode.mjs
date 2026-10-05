export const DEFAULT_THEME_MODE = "mashup";
export const THEME_MODES = Object.freeze([DEFAULT_THEME_MODE, "parler-dark"]);

function describeInvalidValue(value) {
  try {
    return typeof value === "string" ? JSON.stringify(value) : String(value);
  } catch {
    return `<${typeof value}>`;
  }
}

/**
 * Create a normalizer with warning de-duplication scoped to its caller.
 *
 * @param {{ warn?: (message: string) => void }} [options]
 */
export function createThemeModeNormalizer({ warn = console.warn } = {}) {
  const warnedValues = new Set();

  return (value) => {
    if (THEME_MODES.includes(value)) {
      return value;
    }

    const description = describeInvalidValue(value);
    const warningKey = `${typeof value}:${description}`;
    if (!warnedValues.has(warningKey)) {
      warnedValues.add(warningKey);
      warn(
        `[parler-ui] Invalid ThemeMode ${description}; using "${DEFAULT_THEME_MODE}".`
      );
    }
    return DEFAULT_THEME_MODE;
  };
}

export const normalizeThemeMode = createThemeModeNormalizer();
