function parseHopChain(loopHeader) {
  if (typeof loopHeader !== "string") return [];
  return loopHeader
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean);
}

function validateHopChain(loopHeader, selfId) {
  const hops = parseHopChain(loopHeader);
  const seen = new Set();
  for (const hop of hops) {
    if (seen.has(hop)) {
      return { ok: false, code: "loop_detected", reason: "hop_repeat", hop, hops };
    }
    seen.add(hop);
  }
  if (selfId && seen.has(selfId)) {
    return { ok: false, code: "loop_detected", reason: "self_in_chain", hop: selfId, hops };
  }
  return { ok: true, hops };
}

module.exports = { parseHopChain, validateHopChain };
