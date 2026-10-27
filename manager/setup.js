/**
 * Player setup page: the QR code for Android "QR code provisioning" (the reset phone downloads the player APK,
 * makes it device owner and joins the Wi-Fi), and the parent (admin) cards as a PDF. Needs no sign-in.
 *
 * The JSON matches the companion app's ProvisioningQr.kt; keep the two in step. Wi-Fi details stay in this page.
 */
(() => {
  const ADMIN_COMPONENT = "com.omrihak.nfccards/.kiosk.AdminReceiver";
  const APK_URL = "https://github.com/omrihak/nfc-card-game/releases/latest/download/tap-and-play.apk";
  const EXTRA = "android.app.extra.PROVISIONING_";
  const OFFSET_KEY = "printBackOffset"; // the duplex offset saved by the card manager's print panel

  const PARENT_CARDS = [
    {
      name: "Parent card – Settings",
      contentUrl: "tapandplay://settings",
      parent: { title: "Settings", text: "Unlocks it to open Settings" },
    },
    {
      name: "Parent card – Release",
      contentUrl: "tapandplay://release",
      parent: { title: "Release", text: "Unlocks the phone for good" },
    },
  ];

  const $ = (id) => document.getElementById(id);
  const checksum = (window.MANAGER_CONFIG.releaseSignatureChecksum || "").trim();

  // Wi-Fi names and passwords may be any Unicode; the library's default would keep only the low byte.
  if (typeof qrcode === "function") qrcode.stringToBytes = qrcode.stringToBytesFuncs["UTF-8"];

  /** wifi: { ssid, password, security: "WPA" | "WEP" | "NONE", hidden }, left out without a name. */
  function provisioningJson(wifi) {
    const fields = {
      DEVICE_ADMIN_COMPONENT_NAME: ADMIN_COMPONENT,
      DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION: APK_URL,
      DEVICE_ADMIN_SIGNATURE_CHECKSUM: checksum,
      // Keep Settings, the camera app etc.: lock task mode already keeps the child in the player.
      LEAVE_ALL_SYSTEM_APPS_ENABLED: true,
      // Unencrypted Android 7-9 phones would encrypt first (slow, with a reboot). Nothing private is stored.
      SKIP_ENCRYPTION: true,
    };
    if (wifi.ssid) {
      fields.WIFI_SSID = wifi.ssid;
      fields.WIFI_SECURITY_TYPE = wifi.security;
      if (wifi.security !== "NONE" && wifi.password) fields.WIFI_PASSWORD = wifi.password;
      if (wifi.hidden) fields.WIFI_HIDDEN = true;
    }
    const out = {};
    for (const [key, value] of Object.entries(fields)) out[EXTRA + key] = value;
    return JSON.stringify(out);
  }

  function readWifi() {
    return {
      ssid: $("ssid").value.trim(),
      password: $("password").value,
      security: $("security").value,
      hidden: $("hidden").checked,
    };
  }

  function render() {
    $("password-label").hidden = $("security").value === "NONE";
    const box = $("setup-qr");
    if (!checksum) {
      $("not-configured").hidden = false;
      box.replaceChildren();
      return;
    }
    if (typeof qrcode !== "function") {
      box.textContent = "The QR code library did not load. Check your connection and reload.";
      return;
    }
    const json = provisioningJson(readWifi());
    box.replaceChildren(qrSvg(json));
    box.dataset.json = json; // for tests
  }

  /** Black on white with a 4-module quiet zone, whatever the page's colours. */
  function qrSvg(text) {
    const qr = qrcode(0, "L"); // shown on a clean screen: fewer, bigger modules read best
    qr.addData(text, "Byte");
    qr.make();
    const count = qr.getModuleCount();
    const margin = 4;
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
    svg.setAttribute("aria-label", "Setup QR code");
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

  $("wifi-form").addEventListener("input", render);
  $("wifi-form").addEventListener("submit", (e) => e.preventDefault());
  $("show-password").addEventListener("change", (e) => ($("password").type = e.target.checked ? "text" : "password"));
  render();

  // ---- Parent cards ----

  function backOffset() {
    try {
      const saved = JSON.parse(localStorage.getItem(OFFSET_KEY));
      if (saved) return { x: Number(saved.x) || 0, y: Number(saved.y) || 0 };
    } catch (e) {
      // No storage: no offset.
    }
    return { x: 0, y: 0 };
  }

  $("print-parent").addEventListener("click", async () => {
    const button = $("print-parent");
    const status = $("print-status");
    button.disabled = true;
    try {
      const pdf = await CardPrint.makePdf(PARENT_CARDS, backOffset());
      const url = URL.createObjectURL(pdf);
      const link = document.createElement("a");
      link.href = url;
      link.download = "tap-and-play-parent-cards.pdf";
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
      status.textContent = "PDF downloaded.";
    } catch (e) {
      console.error(e);
      status.textContent = `Couldn't make the PDF: ${e.message || e}`;
    } finally {
      button.disabled = false;
    }
  });
})();
