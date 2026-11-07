const MAX_CHANGELOG_ITEMS = 32;
const MAX_CHANGELOG_ITEM_LENGTH = 240;

function trimOptionalString(value, maxLength = 200) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  return trimmed.slice(0, maxLength);
}

function normalizeChangelog(value) {
  if (!Array.isArray(value)) return [];
  const items = [];
  for (const entry of value) {
    const trimmed = trimOptionalString(entry, MAX_CHANGELOG_ITEM_LENGTH);
    if (!trimmed) continue;
    items.push(trimmed);
    if (items.length >= MAX_CHANGELOG_ITEMS) break;
  }
  return items;
}

function normalizeBetaProgramConfig(input) {
  const source = input && typeof input === "object"
    ? (input.betaProgram && typeof input.betaProgram === "object" ? input.betaProgram : input)
    : {};
  const enabled = source.enabled === true;
  return {
    enabled,
    season: trimOptionalString(source.season),
    endsAt: trimOptionalString(source.endsAt),
    latestVersion: trimOptionalString(source.latestVersion),
    minimumSupportedVersion: trimOptionalString(source.minimumSupportedVersion),
    changelog: normalizeChangelog(source.changelog),
  };
}

function createClientBetaProgramManager({ config, store }) {
  function current() {
    if (store && typeof store.betaProgram === "function") {
      return store.betaProgram(config);
    }
    return normalizeBetaProgramConfig(config.betaProgram || {});
  }

  return {
    current,
  };
}

module.exports = {
  createClientBetaProgramManager,
  normalizeBetaProgramConfig,
};
