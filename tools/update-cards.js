// Downloads the Marvel Snap card list from marvelsnapzone.com and saves a slim copy
// (id, name, cost, power) to app/src/main/assets/cards.json. Run: node tools/update-cards.js [downloaded.json]
const fs = require('fs');
const path = require('path');
const URL = 'https://marvelsnapzone.com/getinfo/?searchtype=cards&searchcardstype=true';

function slim(raw) {
  const cards = (raw.success && raw.success.cards) || [];
  const out = [];
  for (const c of cards) {
    if (!c.carddefid || typeof c.cost !== 'number' || typeof c.power !== 'number') continue;
    const name = String(c.name || c.carddefid).replace(/<[^>]*>/g, '');
    out.push({ id: c.carddefid, name, cost: c.cost, power: c.power, released: c.status === 'released' });
  }
  out.sort((a, b) => a.id.localeCompare(b.id));
  return out;
}

async function main() {
  // Optional: pass a file already downloaded from URL instead of fetching it.
  let raw;
  if (process.argv[2]) {
    raw = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  } else {
    const res = await fetch(URL, { headers: { 'User-Agent': 'Mozilla/5.0 (SnapOverlay card list)' } });
    if (!res.ok) throw new Error('Card list download failed: HTTP ' + res.status);
    raw = await res.json();
  }
  const cards = slim(raw);
  if (cards.length < 300) throw new Error('Card list looks too short: ' + cards.length);
  const file = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'cards.json');
  fs.writeFileSync(file, JSON.stringify({ updated: new Date().toISOString().slice(0, 10), cards }) + '\n');
  console.log('Saved ' + cards.length + ' cards to ' + file);
}

module.exports = { slim };
if (require.main === module) main().catch(e => { console.error(e.message); process.exit(1); });
