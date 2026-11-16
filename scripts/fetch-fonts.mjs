// Downloads the app's web fonts once at build time into src/main/assets/fonts/
// so the APK shows the proper Gurmukhi + English typefaces with no internet.
// If Google Fonts can't be reached, the build still succeeds and the app uses the phone's fonts.
import { writeFileSync, mkdirSync } from "node:fs";

const CSS_URL = "https://fonts.googleapis.com/css2?family=Noto+Serif+Gurmukhi:wght@500;700&family=Noto+Sans+Gurmukhi:wght@400;500&family=Cormorant+Garamond:ital,wght@0,500;1,500&display=swap";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
const DIR = "src/main/assets/fonts";

mkdirSync(DIR, { recursive: true });
try {
  const r = await fetch(CSS_URL, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(30000) });
  if (!r.ok) throw new Error("CSS HTTP " + r.status);
  let css = await r.text();
  const urls = [...new Set([...css.matchAll(/url\((https:\/\/[^)]+)\)/g)].map(m => m[1]))];
  if (!urls.length) throw new Error("no font urls in css");
  let n = 0;
  for (const u of urls) {
    const ext = (u.split("?")[0].match(/\.(woff2|woff|ttf|otf)$/) || [, "woff2"])[1];
    const name = `f${n++}.${ext}`;
    const fr = await fetch(u, { signal: AbortSignal.timeout(30000) });
    if (!fr.ok) throw new Error("font HTTP " + fr.status);
    writeFileSync(`${DIR}/${name}`, Buffer.from(await fr.arrayBuffer()));
    css = css.split(u).join(name);
  }
  writeFileSync(`${DIR}/fonts.css`, css);
  console.log(`Bundled ${urls.length} font files`);
} catch (e) {
  console.warn("Fonts NOT bundled (" + e.message + ") - app will use the phone's own fonts.");
  writeFileSync(`${DIR}/fonts.css`, "/* fonts not bundled */\n");
}
