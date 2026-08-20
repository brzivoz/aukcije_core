// eaukcija-scraper.js
// Pokretanje: node eaukcija-scraper.js
// Potrebno: npm install axios fs-extra

const axios = require("axios");
const fs = require("fs-extra");

const BASE_URL = "https://eaukcija.sud.rs/WebApi.Proxy/api/EAukcija";
const DB_FILE = "aukcije.json";       // lokalni "keš" svih aukcija
const DELAY_MS = 500;                 // pauza između poziva (ne preoptereti server)

// ─── KATEGORIJE ──────────────────────────────────────────────────────────────
// 7  = Непокретности (parent)
// 47 = Парцела
// 48 = Објекат
// 49 = Посебан део објекта
// 8  = Заједничка продаја непокретности и покретних ствари
const CATEGORY_IDS = [7, 47, 48, 49];  // ← promenite prema potrebi

// ─── CUSTOM FILTERI ──────────────────────────────────────────────────────────
// Svaki filter je funkcija koja prima objekat aukcije i vraća true/false
// Sve što je null = filter je isključen
const FILTERS = {
  // Lokacija (opština) - može biti string ili niz stringova
  opstina: null,                      // npr. "Beograd" ili ["Beograd", "Novi Sad"]

  // Mesto (grad/selo)
  mesto: null,                        // npr. "Zemun"

  // Katastarska opština
  katastarskaOpstina: null,          // npr. "ZEMUN"

  // Cena u RSD
  minCena: null,                      // npr. 500000
  maxCena: null,                      // npr. 5000000

  // Procijenjena vrednost
  minProcena: null,
  maxProcena: null,

  // Prva ili druga prodaja
  samoPrva: null,                     // true = samo prve prodaje, false = samo druge

  // Status: "Verified" | "InProgress" | "Completed" | "Pending"
  status: null,                       // npr. "Verified"

  // Izvršitelj (ime sadrži ovaj string, case-insensitive)
  izvrsitelj: null,                   // npr. "Vučković"

  // Kategorija (ime kategorije sadrži string)
  kategorijaIme: null,               // npr. "Пољопривредно"

  // Površina - parsira se iz ShortDescription (npr. "3.560,00 м2")
  minPovrsina: null,                  // u m2, npr. 1000
  maxPovrsina: null,                  // u m2, npr. 50000

  // Ključne reči u opisu
  opisSadrzi: null,                  // npr. "Kнежевац"
};

// ─── HELPER FUNKCIJE ─────────────────────────────────────────────────────────

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function parsePovrsina(opis) {
  if (!opis) return null;
  // Traži pattern poput "3.560,00 м2" ili "12500 m2"
  const match = opis.match(/([\d.,]+)\s*м2/i);
  if (!match) return null;
  const numStr = match[1].replace(/\./g, "").replace(",", ".");
  return parseFloat(numStr);
}

function normalize(str) {
  return (str || "").toLowerCase().trim();
}

function matchesString(value, filter) {
  if (!filter) return true;
  if (Array.isArray(filter)) {
    return filter.some((f) => normalize(value).includes(normalize(f)));
  }
  return normalize(value).includes(normalize(filter));
}

// ─── API POZIVI ──────────────────────────────────────────────────────────────

async function getAuctionsList(categoryId, pageCount = 1, itemCount = 50) {
  const resp = await axios.post(`${BASE_URL}/GetAuctionsByCategoryId`, {
    CategoryId: String(categoryId),
    ItemCount: itemCount,
    PageCount: String(pageCount),
  });
  return resp.data.Data || { Auctions: [], TotalCount: 0 };
}

async function getImmovableDetails(auctionId) {
  const resp = await axios.post(`${BASE_URL}/GetImmovablePropertyDetails`, {
    AuctionId: auctionId,
  });
  const data = resp.data.Data;
  if (data) delete data.Images; // uklonimo base64 slike
  return data;
}

// ─── PRIKUPLJANJE SVIH AUKCIJA ───────────────────────────────────────────────

async function fetchAllAuctions(categoryIds, itemsPerPage = 50) {
  const allAuctions = [];

  for (const catId of categoryIds) {
    console.log(`\n📂 Kategorija ${catId}...`);

    // Prva strana - da dobijemo ukupan broj
    const firstPage = await getAuctionsList(catId, 1, itemsPerPage);
    const total = firstPage.TotalCount;
    const maxPage = Math.ceil(total / itemsPerPage);
    console.log(`   Ukupno: ${total} aukcija, ${maxPage} strana`);

    // Sve strane
    for (let page = 1; page <= maxPage; page++) {
      const data = page === 1 ? firstPage : await getAuctionsList(catId, page, itemsPerPage);
      const auctions = data.Auctions || [];
      console.log(`   Strana ${page}/${maxPage}: ${auctions.length} aukcija`);

      // Za svaku aukciju dohvatimo detalje
      for (const auction of auctions) {
        // Preskočimo ako već imamo detalje (keš)
        if (auction._detalji) {
          allAuctions.push(auction);
          continue;
        }

        try {
          await sleep(DELAY_MS);
          const detalji = await getImmovableDetails(auction.Id);
          auction._detalji = detalji;
          allAuctions.push(auction);
          process.stdout.write(".");
        } catch (e) {
          console.warn(`\n   ⚠ Greška za aukciju ${auction.Id}: ${e.message}`);
          auction._detalji = null;
          allAuctions.push(auction);
        }
      }

      console.log();
      await sleep(DELAY_MS);
    }
  }

  return allAuctions;
}

// ─── FILTRIRANJE ─────────────────────────────────────────────────────────────

function applyFilters(auctions, filters) {
  return auctions.filter((a) => {
    const d = a._detalji;
    if (!d) return false;

    const place = d.Place || {};
    const povrsina = parsePovrsina(a.ShortDescription || d.ShortDescription);

    // Lokacija
    if (filters.opstina && !matchesString(place.Municipality, filters.opstina)) return false;
    if (filters.mesto && !matchesString(place.Name, filters.mesto)) return false;
    if (filters.katastarskaOpstina && !matchesString(place.Cadastral, filters.katastarskaOpstina)) return false;

    // Cena
    const cena = d.StartingPrice || d.CurrentPrice || 0;
    if (filters.minCena != null && cena < filters.minCena) return false;
    if (filters.maxCena != null && cena > filters.maxCena) return false;

    // Procijenjena vrednost
    const procena = d.EstimatedPrice || 0;
    if (filters.minProcena != null && procena < filters.minProcena) return false;
    if (filters.maxProcena != null && procena > filters.maxProcena) return false;

    // Prodaja
    if (filters.samoPrva != null && d.IsFirstSale !== filters.samoPrva) return false;

    // Status
    if (filters.status && normalize(d.Status) !== normalize(filters.status)) return false;

    // Izvršitelj
    if (filters.izvrsitelj && !matchesString(d.ExecutorName, filters.izvrsitelj)) return false;

    // Kategorija
    if (filters.kategorijaIme && !matchesString(d.Category?.Name, filters.kategorijaIme)) return false;

    // Površina
    if (filters.minPovrsina != null && (povrsina == null || povrsina < filters.minPovrsina)) return false;
    if (filters.maxPovrsina != null && (povrsina == null || povrsina > filters.maxPovrsina)) return false;

    // Opis
    if (filters.opisSadrzi && !matchesString(d.ShortDescription, filters.opisSadrzi)) return false;

    return true;
  });
}

// ─── PRIKAZ REZULTATA ────────────────────────────────────────────────────────

function printResults(auctions) {
  if (auctions.length === 0) {
    console.log("❌ Nema rezultata koji odgovaraju filterima.");
    return;
  }

  console.log(`\n✅ Pronađeno ${auctions.length} aukcija:\n`);
  console.log("─".repeat(80));

  for (const a of auctions) {
    const d = a._detalji;
    const place = d?.Place || {};
    const povrsina = parsePovrsina(a.ShortDescription || d?.ShortDescription);

    console.log(`📌 ${d?.AuctionNumber || a.AuctionNumber}`);
    console.log(`   Opis:       ${d?.ShortDescription || a.ShortDescription}`);
    console.log(`   Lokacija:   ${place.Municipality || "?"}, ${place.Name || "?"} (KO: ${place.Cadastral || "?"})`);
    console.log(`   Kategorija: ${d?.Category?.Name || "?"}`);
    if (povrsina) console.log(`   Površina:   ${povrsina.toLocaleString()} m²`);
    console.log(`   Poč. cena:  ${(d?.StartingPrice || 0).toLocaleString()} RSD`);
    console.log(`   Proc. vred: ${(d?.EstimatedPrice || 0).toLocaleString()} RSD`);
    console.log(`   Prodaja:    ${d?.IsFirstSale ? "Prva" : "Druga"}`);
    console.log(`   Status:     ${d?.Status || "?"}`);
    console.log(`   Izvršitelj: ${d?.ExecutorName || "?"}`);
    console.log(`   Link:       https://eaukcija.sud.rs/#/aukcije/${a.Id}`);
    console.log("─".repeat(80));
  }
}

// ─── MAIN ────────────────────────────────────────────────────────────────────

async function main() {
  console.log("🏛️  e-Aukcija Scraper\n");

  // Učitaj keš ako postoji
  let allAuctions = [];
  if (await fs.pathExists(DB_FILE)) {
    allAuctions = await fs.readJson(DB_FILE);
    console.log(`📦 Učitano ${allAuctions.length} aukcija iz keša (${DB_FILE})`);
  } else {
    // Prikupljamo sve aukcije
    allAuctions = await fetchAllAuctions(CATEGORY_IDS, 50);
    await fs.writeJson(DB_FILE, allAuctions, { spaces: 2 });
    console.log(`\n💾 Sačuvano ${allAuctions.length} aukcija u ${DB_FILE}`);
  }

  // Primeni filtere
  const rezultati = applyFilters(allAuctions, FILTERS);
  printResults(rezultati);

  // Sačuvaj filtrirane rezultate
  await fs.writeJson("rezultati.json", rezultati, { spaces: 2 });
  console.log(`\n📄 Rezultati sačuvani u rezultati.json`);
}

main().catch(console.error);