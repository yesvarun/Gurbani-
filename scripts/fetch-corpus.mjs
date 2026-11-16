// Downloads all 1430 angs once at build time and writes src/main/assets/corpus.json,
// in exactly the shape the app's own "Save scripture offline" download produces.
import { writeFileSync, mkdirSync } from "node:fs";

const ANG = "https://api.gurbaninow.com/v2/ang/";
const TOTAL = 1430;
const OUT = "src/main/assets/corpus.json";

const isGur = s => typeof s === "string" && /[਀-੿]/.test(s);
function gurOf(n){
  if(!n || typeof n!=="object") return null;
  const c=[n?.gurmukhi?.unicode, n?.verse?.unicode];
  for(const x of c) if(isGur(x)) return x;
  return null;
}
function engOf(n){
  const t=n?.translation?.english ?? n?.translation?.en;
  if(typeof t==="string") return t;
  if(t?.default) return t.default;
  if(typeof n?.translation?.english?.text==="string") return n.translation.english.text;
  return "";
}
function tranOf(n){
  const t=n?.transliteration?.english ?? n?.transliteration?.en;
  if(typeof t==="string") return t;
  if(t?.text) return t.text;
  return "";
}
function punOf(n){
  const t=n?.translation?.punjabi?.default ?? n?.translation?.punjabi;
  if(typeof t==="string") return t;
  if(t?.unicode) return t.unicode;
  if(t?.default?.unicode) return t.default.unicode;
  return "";
}
function angOf(n){ return n?.pageno ?? n?.pageNo ?? n?.ang ?? n?.page ?? null; }
function collectLines(root){
  const out=[];
  (function walk(n){
    if(!n || typeof n!=="object") return;
    if(Array.isArray(n)){ n.forEach(walk); return; }
    const g=gurOf(n);
    if(g){ out.push({g, e:engOf(n), pa:punOf(n), t:tranOf(n), ang:angOf(n)}); return; }
    for(const k in n){ if(n[k] && typeof n[k]==="object") walk(n[k]); }
  })(root);
  return out;
}

const sleep = ms => new Promise(r=>setTimeout(r,ms));
const byAng = new Array(TOTAL+1);
const failed = [];
let next = 1, done = 0;

async function fetchAng(n){
  for(let a=0;a<8;a++){
    try{
      const r = await fetch(ANG+n, {signal: AbortSignal.timeout(30000)});
      if(!r.ok) throw new Error("HTTP "+r.status);
      const lines = collectLines(await r.json()).map(l=>({g:l.g, e:l.e||"", pa:l.pa||"", t:l.t||"", ang:l.ang||n}));
      if(!lines.length) throw new Error("no lines");
      byAng[n]=lines; return;
    }catch(e){ await sleep(1000*(a+1)); }
  }
  failed.push(n);
}
async function worker(){
  while(next<=TOTAL){
    const n=next++; await fetchAng(n); done++;
    if(done%100===0) console.log(`${done}/${TOTAL} angs`);
  }
}
await Promise.all(Array.from({length:6}, worker));

if(failed.length){
  console.error("FAILED angs: "+failed.sort((a,b)=>a-b).join(", "));
  process.exit(1);
}
const corpus=[];
for(let n=1;n<=TOTAL;n++) for(const ln of byAng[n]) corpus.push(ln);
if(corpus.length<10000){ console.error("Too few lines: "+corpus.length); process.exit(1); }
mkdirSync("src/main/assets",{recursive:true});
writeFileSync(OUT, JSON.stringify(corpus));
console.log(`Wrote ${corpus.length} lines to ${OUT}`);
