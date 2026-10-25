/**
 * Card manager: makes the files a card needs, in the signed-in user's Google Drive.
 *
 *   Tap and Play/
 *     cards.json            index of every card made here (for this page's list)
 *     <card name>/
 *       <song or video>     public
 *       <picture>           public, optional
 *       content.json        public; its share link is what goes on the NFC sticker
 *
 * content.json is the format the Android player reads (see README "Making a card").
 */
const config = window.MANAGER_CONFIG;
const SCOPE = "https://www.googleapis.com/auth/drive.file";
const INDEX_NAME = "cards.json";
// The access token is kept across page reloads until it expires (about an hour). After that the
// next action asks Google again, which is one click without the consent screen.
const SESSION_KEY = "cardManagerSession";

const $ = (id) => document.getElementById(id);

let tokenClient = null;
let tokenExpiresAt = 0;
let pendingToken = null; // { resolve, reject } while the Google popup is open
let rootFolder = null;
const selected = new Map(); // contentUrl -> card, in the order they were ticked (= print order)

// Google Identity Services script loaded.
function onGoogleLoaded() {
  tokenClient = google.accounts.oauth2.initTokenClient({
    client_id: config.clientId,
    scope: SCOPE,
    callback: (response) => {
      const pending = pendingToken;
      pendingToken = null;
      if (response.error) return pending && pending.reject(new Error(response.error));
      Drive.setToken(response.access_token);
      tokenExpiresAt = Date.now() + (Number(response.expires_in) - 60) * 1000;
      saveSession();
      pending && pending.resolve();
    },
    error_callback: (err) => {
      const pending = pendingToken;
      pendingToken = null;
      pending && pending.reject(new Error(err.message || err.type || "Sign-in cancelled"));
    },
  });
}

/** Must be called synchronously from a click, because it may open Google's popup. */
function ensureToken() {
  if (Drive.token() && Date.now() < tokenExpiresAt) return Promise.resolve();
  if (!tokenClient) return Promise.reject(new Error("Google sign-in is still loading, try again"));
  return new Promise((resolve, reject) => {
    pendingToken = { resolve, reject };
    tokenClient.requestAccessToken({ prompt: tokenExpiresAt ? "" : "consent" });
  });
}

function saveSession() {
  try {
    localStorage.setItem(SESSION_KEY, JSON.stringify({ token: Drive.token(), expiresAt: tokenExpiresAt }));
  } catch (e) {
    // Private mode or storage blocked: sign-in just won't survive a reload.
  }
}

function clearSession() {
  try {
    localStorage.removeItem(SESSION_KEY);
  } catch (e) {}
}

/** Signs back in from the previous visit's token, if it is still valid. */
function restoreSession() {
  let saved = null;
  try {
    saved = JSON.parse(localStorage.getItem(SESSION_KEY));
  } catch (e) {}
  if (!saved || !saved.token) return;
  // Remembering that we signed in before skips the consent screen next time, even once expired.
  tokenExpiresAt = saved.expiresAt || 1;
  if (Date.now() >= saved.expiresAt) {
    Drive.setToken(null);
    return;
  }
  Drive.setToken(saved.token);
  showSignedIn(true);
  refreshCards().catch((e) => {
    if (/Drive 401/.test(e.message)) {
      // Revoked or expired early: back to the sign-in button.
      Drive.setToken(null);
      clearSession();
      showSignedIn(false);
    } else {
      showError(e);
    }
  });
}

function showSignedIn(signedIn) {
  $("signin").hidden = signedIn;
  $("signout").hidden = !signedIn;
  $("new-card").hidden = !signedIn;
  $("cards-section").hidden = !signedIn;
  $("signed-out").hidden = signedIn;
}

$("signin").addEventListener("click", () => {
  ensureToken()
    .then(async () => {
      showSignedIn(true);
      await refreshCards();
    })
    .catch((e) => showError(e));
});

$("signout").addEventListener("click", () => {
  if (Drive.token()) google.accounts.oauth2.revoke(Drive.token(), () => {});
  Drive.setToken(null);
  tokenExpiresAt = 0;
  clearSession();
  rootFolder = null;
  selected.clear();
  $("cards").replaceChildren();
  showSignedIn(false);
});

// Show the file inputs for the chosen type.
document.querySelectorAll('input[name="type"]').forEach((radio) =>
  radio.addEventListener("change", () => {
    const type = selectedType();
    document.querySelectorAll("[data-for]").forEach((el) => (el.hidden = el.dataset.for !== type));
  }),
);

function selectedType() {
  return document.querySelector('input[name="type"]:checked').value;
}

$("card-form").addEventListener("submit", (event) => {
  event.preventDefault();
  hideError();

  const name = $("name").value.trim();
  const type = selectedType();
  const media = type === "audio" ? $("audio-file").files[0] : $("video-file").files[0];
  const image = type === "audio" ? $("image-file").files[0] : undefined;
  if (!name) return showError("Give the card a name.");
  if (!media) return showError(type === "audio" ? "Choose a song." : "Choose a video.");

  setBusy(true);
  ensureToken()
    .then(() => createCard({ name, type, media, image }))
    .then(async (card) => {
      $("card-form").reset();
      document.querySelectorAll("[data-for]").forEach((el) => (el.hidden = el.dataset.for !== "audio"));
      setProgress(`"${card.name}" is ready. Write it to a sticker below.`, 1);
      await refreshCards();
    })
    .catch((e) => {
      $("progress").hidden = true;
      showError(e);
    })
    .finally(() => setBusy(false));
});

async function getRoot() {
  if (!rootFolder) rootFolder = await Drive.findOrCreateFolder(config.rootFolderName);
  return rootFolder;
}

async function createCard({ name, type, media, image }) {
  setProgress("Creating folder…", 0);
  const root = await getRoot();
  const folder = await Drive.createFolder(name, root.id);

  // Upload progress is weighted by size; the small steps after that are not counted.
  const total = media.size + (image ? image.size : 0);
  let done = 0;
  const uploadOne = async (file, label) => {
    const uploaded = await Drive.upload(file, file.name, folder.id, (fraction) =>
      setProgress(`Uploading ${label}…`, (done + fraction * file.size) / total),
    );
    done += file.size;
    await Drive.makePublic(uploaded.id);
    return uploaded;
  };

  const mediaFile = await uploadOne(media, type === "audio" ? "song" : "video");
  const imageFile = image ? await uploadOne(image, "picture") : null;

  setProgress("Writing content.json…", 1);
  const content = { type, media: Drive.shareLink(mediaFile.id) };
  if (imageFile) content.image = Drive.shareLink(imageFile.id);
  // Extra fields are ignored by the player.
  content.name = name;
  content.createdAt = new Date().toISOString();

  const contentBlob = new Blob([JSON.stringify(content, null, 2)], { type: "application/json" });
  const contentFile = await Drive.upload(contentBlob, "content.json", folder.id);
  await Drive.makePublic(contentFile.id);

  const card = {
    name,
    type,
    contentUrl: Drive.shareLink(contentFile.id),
    folderId: folder.id,
    createdAt: content.createdAt,
  };
  setProgress("Adding to the card list…", 1);
  await addToIndex(card);
  return card;
}

async function readIndex() {
  const root = await getRoot();
  const file = await Drive.find(INDEX_NAME, { parent: root.id });
  if (!file) return { file: null, cards: [] };
  try {
    const parsed = JSON.parse(await Drive.readText(file.id));
    return { file, cards: Array.isArray(parsed.cards) ? parsed.cards : [] };
  } catch (e) {
    console.warn("cards.json is unreadable, starting a new list", e);
    return { file, cards: [] };
  }
}

async function addToIndex(card) {
  const { file, cards } = await readIndex();
  cards.push(card);
  const text = JSON.stringify({ cards }, null, 2);
  if (file) {
    await Drive.overwriteText(file.id, text);
  } else {
    const root = await getRoot();
    await Drive.upload(new Blob([text], { type: "application/json" }), INDEX_NAME, root.id);
  }
}

async function refreshCards() {
  const { cards } = await readIndex();
  const list = $("cards");
  const template = $("card-row");
  list.replaceChildren(
    ...cards
      .slice()
      .reverse()
      .map((card) => {
        const row = template.content.firstElementChild.cloneNode(true);
        row.querySelector(".row-name").textContent = card.name;
        row.querySelector(".row-type").textContent = card.type === "video" ? "video" : "song";
        const select = row.querySelector(".row-select");
        select.checked = selected.has(card.contentUrl);
        select.addEventListener("change", () => {
          if (select.checked) selected.set(card.contentUrl, card);
          else selected.delete(card.contentUrl);
          updatePrintStatus();
        });
        row.querySelector(".open-folder").href = Drive.folderLink(card.folderId);
        row.querySelector(".copy-link").addEventListener("click", (e) => copyLink(card, e.currentTarget));
        row.querySelector(".show-qr").addEventListener("click", () => showQr(card));
        const write = row.querySelector(".write-nfc");
        if (nfcSupported()) {
          write.addEventListener("click", (e) => writeSticker(card, e.currentTarget));
        } else {
          write.hidden = true;
        }
        return row;
      }),
  );
  const urls = new Set(cards.map((card) => card.contentUrl));
  for (const url of selected.keys()) if (!urls.has(url)) selected.delete(url);
  updatePrintStatus();
  $("print-panel").hidden = cards.length === 0;
  $("no-cards").hidden = cards.length > 0;
  $("nfc-hint").hidden = nfcSupported() || cards.length === 0;
}

function nfcSupported() {
  return "NDEFReader" in window;
}

async function writeSticker(card, button) {
  const label = button.textContent;
  button.disabled = true;
  button.textContent = "Hold sticker to phone…";
  try {
    const writer = new NDEFReader();
    await writer.write({ records: [{ recordType: "url", data: card.contentUrl }] });
    button.textContent = "Written ✓";
  } catch (e) {
    button.textContent = "Failed";
    showError(`Could not write the sticker: ${e.message || e}`);
  }
  setTimeout(() => {
    button.textContent = label;
    button.disabled = false;
  }, 2500);
}

/** Shows the card's link as a QR code, for testing with the player's camera instead of a sticker. */
function showQr(card) {
  $("qr-name").textContent = card.name;
  $("qr-link").textContent = card.contentUrl;
  $("qr-link").href = card.contentUrl;
  $("qr-image").replaceChildren();
  if (typeof qrcode !== "function") {
    $("qr-image").textContent = "The QR code library did not load. Check your connection and reload.";
  } else {
    $("qr-image").append(qrSvg(card.contentUrl));
  }
  $("qr-dialog").showModal();
}

function qrSvg(text) {
  const qr = qrcode(0, "M");
  qr.addData(text);
  qr.make();
  const count = qr.getModuleCount();
  const margin = 4; // quiet zone, in modules
  const size = count + margin * 2;
  let path = "";
  for (let row = 0; row < count; row++) {
    for (let col = 0; col < count; col++) {
      if (qr.isDark(row, col)) path += `M${col + margin} ${row + margin}h1v1h-1z`;
    }
  }
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", `0 0 ${size} ${size}`);
  svg.setAttribute("shape-rendering", "crispEdges");
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", "QR code for the card link");
  const background = document.createElementNS(ns, "rect");
  background.setAttribute("width", size);
  background.setAttribute("height", size);
  background.setAttribute("fill", "#fff");
  const modules = document.createElementNS(ns, "path");
  modules.setAttribute("d", path);
  modules.setAttribute("fill", "#000");
  svg.append(background, modules);
  return svg;
}

$("qr-close").addEventListener("click", () => $("qr-dialog").close());
// Click on the backdrop closes it too.
$("qr-dialog").addEventListener("click", (e) => e.target === e.currentTarget && $("qr-dialog").close());

async function copyLink(card, button) {
  const label = button.textContent;
  try {
    await navigator.clipboard.writeText(card.contentUrl);
    button.textContent = "Copied ✓";
  } catch (e) {
    window.prompt("Card link:", card.contentUrl);
  }
  setTimeout(() => (button.textContent = label), 2000);
}

// ---- Printing ----

const OFFSET_KEY = "printBackOffset";

function updatePrintStatus() {
  const count = selected.size;
  const sheets = Math.ceil(count / CardPrint.PER_SHEET);
  $("print").disabled = count === 0;
  $("print-status").textContent =
    count === 0
      ? "No cards selected."
      : `${count} card${count === 1 ? "" : "s"}: ${sheets} sheet${sheets === 1 ? "" : "s"}, ${sheets * 2} pages.`;
}

function backOffset() {
  const read = (id) => Number($(id).value) || 0;
  return { x: read("offset-x"), y: read("offset-y") };
}

try {
  const saved = JSON.parse(localStorage.getItem(OFFSET_KEY));
  if (saved) {
    $("offset-x").value = saved.x || 0;
    $("offset-y").value = saved.y || 0;
  }
} catch (e) {
  // No storage: start from no offset.
}
["offset-x", "offset-y"].forEach((id) =>
  $(id).addEventListener("change", () => {
    try {
      localStorage.setItem(OFFSET_KEY, JSON.stringify(backOffset()));
    } catch (e) {
      // Not remembered, still used for this PDF.
    }
  }),
);

$("print").addEventListener("click", () => {
  hideError();
  const cards = [...selected.values()];
  const button = $("print");
  button.disabled = true;
  ensureToken()
    .then(() => printCards(cards))
    .catch((e) => {
      $("print-status").textContent = "";
      showError(e);
    })
    .finally(() => (button.disabled = selected.size === 0));
});

async function printCards(cards) {
  const status = $("print-status");
  const missing = [];
  const prepared = [];
  for (const [i, card] of cards.entries()) {
    status.textContent = `Loading pictures… ${i + 1}/${cards.length}`;
    let image = null;
    try {
      image = await loadCardImage(card);
    } catch (e) {
      // Still printable: the front gets the coloured placeholder instead.
      console.warn(e);
      missing.push(card.name);
    }
    prepared.push({ ...card, image });
  }
  const pdf = await CardPrint.makePdf(prepared, backOffset(), (fraction) => {
    status.textContent = `Making PDF… ${Math.round(fraction * 100)}%`;
  });

  const url = URL.createObjectURL(pdf);
  const link = document.createElement("a");
  link.href = url;
  link.download = "tap-and-play-cards.pdf";
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);

  status.textContent =
    "PDF downloaded." + (missing.length ? ` Couldn't load the picture for: ${missing.join(", ")}.` : "");
}

/** The card's picture from its content.json, or null if it has none (videos). */
async function loadCardImage(card) {
  const contentId = Drive.idFromLink(card.contentUrl);
  if (!contentId) throw new Error(`Bad card link: ${card.contentUrl}`);
  const content = JSON.parse(await Drive.readText(contentId));
  const imageId = content.image && Drive.idFromLink(content.image);
  if (!imageId) return null;
  const blob = await Drive.readBlob(imageId);
  const url = URL.createObjectURL(blob);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    return img;
  } finally {
    URL.revokeObjectURL(url);
  }
}

function setBusy(busy) {
  $("create").disabled = busy;
  $("create").textContent = busy ? "Working…" : "Create card";
}

function setProgress(text, fraction) {
  $("progress").hidden = false;
  $("progress-text").textContent = text;
  $("progress-bar").value = Math.max(0, Math.min(1, fraction));
}

function showError(error) {
  console.error(error);
  $("error").textContent = typeof error === "string" ? error : error.message || String(error);
  $("error").hidden = false;
}

function hideError() {
  $("error").hidden = true;
}

restoreSession();
