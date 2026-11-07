function ipToInt(ip) {
  const parts = ip.split(".");
  if (parts.length !== 4) return null;
  const nums = parts.map((part) => Number(part));
  if (nums.some((num) => !Number.isFinite(num) || num < 0 || num > 255)) {
    return null;
  }
  return (
    (nums[0] << 24) +
    (nums[1] << 16) +
    (nums[2] << 8) +
    nums[3]
  ) >>> 0;
}

function matchesIpList(ip, list) {
  if (!ip || list.length === 0) return false;
  const normalized = ip.startsWith("::ffff:") ? ip.slice(7) : ip;
  for (const entry of list) {
    if (entry.includes("/")) {
      const [cidrIp, maskBitsRaw] = entry.split("/");
      const maskBits = Number(maskBitsRaw);
      const ipInt = ipToInt(normalized);
      const cidrInt = ipToInt(cidrIp);
      if (ipInt === null || cidrInt === null) continue;
      const mask = maskBits === 0 ? 0 : (~0 << (32 - maskBits)) >>> 0;
      if ((ipInt & mask) === (cidrInt & mask)) return true;
    } else if (normalized === entry || ip === entry) {
      return true;
    }
  }
  return false;
}

function isPeerBanned(ip, peerId, banlist) {
  if (!banlist || banlist.length === 0) return false;
  if (matchesIpList(ip, banlist)) return true;
  if (peerId && banlist.includes(peerId)) return true;
  return false;
}

module.exports = { isPeerBanned, matchesIpList };
