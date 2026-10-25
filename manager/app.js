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

const $ = (id) => document.getElementById(id);

let tokenClient = null;
let tokenExpiresAt = 0;
let pendingToken = null; // { resolve, reject } while the Google popup is open
let rootFolder = null;

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
  if (Date.now() < tokenExpiresAt) return Promise.resolve();
  if (!tokenClient) return Promise.reject(new Error("Google sign-in is still loading, try again"));
  return new Promise((resolve, reject) => {
    pendingToken = { resolve, reject };
    tokenClient.requestAccessToken({ prompt: tokenExpiresAt ? "" : "consent" });
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
  rootFolder = null;
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
        row.querySelector(".open-folder").href = Drive.folderLink(card.folderId);
        row.querySelector(".copy-link").addEventListener("click", (e) => copyLink(card, e.currentTarget));
        const write = row.querySelector(".write-nfc");
        if (nfcSupported()) {
          write.addEventListener("click", (e) => writeSticker(card, e.currentTarget));
        } else {
          write.hidden = true;
        }
        return row;
      }),
  );
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
