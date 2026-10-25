/**
 * Minimal Google Drive v3 REST client for the card manager.
 *
 * Uses only the drive.file scope: the page can see and change just the files and folders it
 * created itself, which is all it needs.
 */
const Drive = (() => {
  const API = "https://www.googleapis.com/drive/v3";
  const UPLOAD = "https://www.googleapis.com/upload/drive/v3";
  const FOLDER = "application/vnd.google-apps.folder";

  let accessToken = null;

  function setToken(token) {
    accessToken = token;
  }

  function token() {
    return accessToken;
  }

  async function request(url, options = {}) {
    const response = await fetch(url, {
      ...options,
      headers: { Authorization: `Bearer ${accessToken}`, ...(options.headers || {}) },
    });
    if (!response.ok) {
      const body = await response.text();
      throw new Error(`Drive ${response.status}: ${body.slice(0, 300)}`);
    }
    return response;
  }

  async function json(url, options) {
    return (await request(url, options)).json();
  }

  function quote(value) {
    return `'${value.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
  }

  /** First non-trashed file with this name (and parent / mime type), or null. */
  async function find(name, { parent, mimeType } = {}) {
    const q = [`name = ${quote(name)}`, "trashed = false"];
    if (parent) q.push(`${quote(parent)} in parents`);
    if (mimeType) q.push(`mimeType = ${quote(mimeType)}`);
    const params = new URLSearchParams({ q: q.join(" and "), fields: "files(id,name)", pageSize: "1" });
    const result = await json(`${API}/files?${params}`);
    return result.files[0] || null;
  }

  async function createFolder(name, parent) {
    const metadata = { name, mimeType: FOLDER };
    if (parent) metadata.parents = [parent];
    return json(`${API}/files?fields=id,name`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(metadata),
    });
  }

  async function findOrCreateFolder(name) {
    return (await find(name, { mimeType: FOLDER })) || createFolder(name);
  }

  /**
   * Uploads a File/Blob with a resumable upload (works for large videos) and reports progress
   * as a 0..1 fraction. Returns { id, name }.
   */
  async function upload(blob, name, parent, onProgress = () => {}) {
    const type = blob.type || "application/octet-stream";
    const start = await request(`${UPLOAD}/files?uploadType=resumable&fields=id,name`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json; charset=UTF-8",
        "X-Upload-Content-Type": type,
        "X-Upload-Content-Length": String(blob.size),
      },
      body: JSON.stringify({ name, parents: [parent] }),
    });
    const sessionUrl = start.headers.get("Location");
    if (!sessionUrl) throw new Error("Drive did not return an upload URL");

    // XMLHttpRequest rather than fetch, for upload progress events.
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("PUT", sessionUrl);
      xhr.setRequestHeader("Content-Type", type);
      xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          onProgress(1);
          resolve(JSON.parse(xhr.responseText));
        } else {
          reject(new Error(`Upload of ${name} failed: ${xhr.status} ${xhr.responseText.slice(0, 300)}`));
        }
      };
      xhr.onerror = () => reject(new Error(`Upload of ${name} failed (network error)`));
      xhr.send(blob);
    });
  }

  /** "Anyone with the link: Viewer". The player downloads files without signing in. */
  async function makePublic(fileId) {
    await request(`${API}/files/${fileId}/permissions?fields=id`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ role: "reader", type: "anyone" }),
    });
  }

  async function readText(fileId) {
    return (await request(`${API}/files/${fileId}?alt=media`)).text();
  }

  async function overwriteText(fileId, text, type = "application/json") {
    return json(`${UPLOAD}/files/${fileId}?uploadType=media&fields=id`, {
      method: "PATCH",
      headers: { "Content-Type": type },
      body: text,
    });
  }

  function shareLink(fileId) {
    return `https://drive.google.com/file/d/${fileId}/view?usp=sharing`;
  }

  function folderLink(folderId) {
    return `https://drive.google.com/drive/folders/${folderId}`;
  }

  return {
    setToken, token, find, createFolder, findOrCreateFolder, upload, makePublic, readText, overwriteText,
    shareLink, folderLink,
  };
})();
