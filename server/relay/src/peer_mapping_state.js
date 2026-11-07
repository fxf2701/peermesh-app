const ACTIVE_PEER_STATE = "active";
const QUARANTINED_PEER_STATE = "quarantined";
const ALLOWED_PEER_STATES = new Set([ACTIVE_PEER_STATE, QUARANTINED_PEER_STATE]);

function normalizePeerState(value) {
  if (typeof value !== "string") return null;
  const normalized = value.trim().toLowerCase();
  return ALLOWED_PEER_STATES.has(normalized) ? normalized : null;
}

function effectivePeerState(mapping, nowMs = Date.now()) {
  if (!mapping || mapping.enabled !== true) return "disabled";
  const state = normalizePeerState(mapping.peer_state) || ACTIVE_PEER_STATE;
  if (state !== QUARANTINED_PEER_STATE) return ACTIVE_PEER_STATE;
  const untilMs = mapping.quarantined_until ? Date.parse(mapping.quarantined_until) : NaN;
  if (Number.isFinite(untilMs) && untilMs <= nowMs) {
    return ACTIVE_PEER_STATE;
  }
  return QUARANTINED_PEER_STATE;
}

function isPeerMappingActive(mapping, nowMs = Date.now()) {
  return effectivePeerState(mapping, nowMs) === ACTIVE_PEER_STATE;
}

function isPeerMappingQuarantined(mapping, nowMs = Date.now()) {
  return effectivePeerState(mapping, nowMs) === QUARANTINED_PEER_STATE;
}

module.exports = {
  ACTIVE_PEER_STATE,
  QUARANTINED_PEER_STATE,
  normalizePeerState,
  effectivePeerState,
  isPeerMappingActive,
  isPeerMappingQuarantined,
};
