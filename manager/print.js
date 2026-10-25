/**
 * Printable card sheets as a PDF.
 *
 * A4 portrait, 2 x 3 cards per sheet. Each sheet is two pages: the fronts (the card's picture),
 * then the backs (QR code of the content.json link + the card's name). Print double-sided,
 * flipping on the long edge, at actual size. The back page is mirrored left-right so that every
 * back lands behind its own front, and the grid is centred on the page so that the mirror is exact.
 *
 * Cards are separated by a white gap. Crop marks in the margin (fronts only) point at the middle of
 * each gap and at the same distance outside the outer cards: cut there and every card gets an even
 * white border, which also hides small cutting or duplex errors.
 *
 * The PDF is written by hand (images are the canvas's JPEG bytes, which PDF embeds as is), so
 * sizes are exact millimetres and no PDF library is needed. QR codes come from vendor/qrcode.js.
 */
const CardPrint = (() => {
  const PAGE_W = 210; // A4, mm
  const PAGE_H = 297;
  const COLS = 2;
  const ROWS = 3;
  const PER_SHEET = COLS * ROWS;
  const CARD_W = 85; // mm
  const CARD_H = 85;
  const GAP = 6; // white space between cards; the cut goes through its middle
  const FINGER_MARGIN = 14; // white border around the QR code, so fingers holding the card don't cover it
  const MARK_OFFSET = 1; // crop marks start this far outside the outer cut lines...
  const MARK_LEN = 5; // ...and are this long
  const DPI = 300;
  const PT_PER_MM = 72 / 25.4;

  const GRID_X = (PAGE_W - (COLS * CARD_W + (COLS - 1) * GAP)) / 2;
  const GRID_Y = (PAGE_H - (ROWS * CARD_H + (ROWS - 1) * GAP)) / 2;

  /** Top-left corner (mm) of the card in this slot, as printed on the front page. */
  function slotPosition(slot) {
    const col = slot % COLS;
    const row = Math.floor(slot / COLS);
    return { x: GRID_X + col * (CARD_W + GAP), y: GRID_Y + row * (CARD_H + GAP) };
  }

  /**
   * Same card, back page: mirrored left-right (long-edge flip), plus the printer's own duplex
   * offset if one was set. An offset is given as seen from the back, so "right" is +x there.
   */
  function backPosition(slot, offset) {
    const front = slotPosition(slot);
    return { x: PAGE_W - front.x - CARD_W + offset.x, y: front.y + offset.y };
  }

  /**
   * cards: [{ name, type, contentUrl, image (HTMLImageElement or null) }], in print order.
   * offset: { x, y } in mm, shifts the back pages. Returns a PDF Blob.
   */
  async function makePdf(cards, offset = { x: 0, y: 0 }, onProgress = () => {}) {
    const pages = [];
    let done = 0;
    for (let first = 0; first < cards.length; first += PER_SHEET) {
      const sheet = cards.slice(first, first + PER_SHEET);
      const front = { images: [], lines: cropMarks() };
      const back = { images: [], lines: [] };
      for (let slot = 0; slot < sheet.length; slot++) {
        const card = sheet[slot];
        const f = slotPosition(slot);
        front.images.push({
          jpeg: await canvasToJpeg(drawFront(card)),
          x: f.x, y: f.y, w: CARD_W, h: CARD_H,
        });
        const b = backPosition(slot, offset);
        back.images.push({ jpeg: await canvasToJpeg(drawBack(card)), x: b.x, y: b.y, w: CARD_W, h: CARD_H });
        onProgress(++done / cards.length);
      }
      pages.push(front, back);
    }
    return new Blob([writePdf(pages)], { type: "application/pdf" });
  }

  function cropMarks() {
    // Cut lines: half a gap before each card, and half a gap after the last one.
    const xs = [];
    const ys = [];
    for (let c = 0; c <= COLS; c++) xs.push(GRID_X - GAP / 2 + c * (CARD_W + GAP));
    for (let r = 0; r <= ROWS; r++) ys.push(GRID_Y - GAP / 2 + r * (CARD_H + GAP));
    const top = ys[0];
    const bottom = ys[ROWS];
    const left = xs[0];
    const right = xs[COLS];
    const lines = [];
    for (const x of xs) {
      lines.push([x, top - MARK_OFFSET - MARK_LEN, x, top - MARK_OFFSET]);
      lines.push([x, bottom + MARK_OFFSET, x, bottom + MARK_OFFSET + MARK_LEN]);
    }
    for (const y of ys) {
      lines.push([left - MARK_OFFSET - MARK_LEN, y, left - MARK_OFFSET, y]);
      lines.push([right + MARK_OFFSET, y, right + MARK_OFFSET + MARK_LEN, y]);
    }
    return lines;
  }

  // ---- Card faces, drawn at 300 dpi ----

  const px = (mm) => Math.round((mm * DPI) / 25.4);

  function newCanvas(wMm, hMm) {
    const canvas = document.createElement("canvas");
    canvas.width = px(wMm);
    canvas.height = px(hMm);
    return canvas;
  }

  /**
   * Picture, scaled to fit inside the card without cropping and centred, on white (so a picture that
   * isn't square gets white bands). Without one, a coloured card with the name.
   */
  function drawFront(card) {
    const canvas = newCanvas(CARD_W, CARD_H);
    const ctx = canvas.getContext("2d");
    const { width: w, height: h } = canvas;
    if (card.image) {
      const img = card.image;
      ctx.fillStyle = "#fff"; // JPEG has no transparency: unpainted pixels would come out black
      ctx.fillRect(0, 0, w, h);
      const scale = Math.min(w / img.naturalWidth, h / img.naturalHeight);
      const dw = img.naturalWidth * scale;
      const dh = img.naturalHeight * scale;
      ctx.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh);
      return canvas;
    }

    ctx.fillStyle = `hsl(${hue(card.name)} 55% 52%)`;
    ctx.fillRect(0, 0, w, h);
    ctx.fillStyle = "#fff";
    const cx = w / 2;
    const symbol = px(28);
    if (card.type === "video") {
      const top = h * 0.42 - symbol / 2;
      ctx.beginPath();
      ctx.moveTo(cx - symbol * 0.4, top);
      ctx.lineTo(cx + symbol * 0.5, top + symbol / 2);
      ctx.lineTo(cx - symbol * 0.4, top + symbol);
      ctx.closePath();
      ctx.fill();
    } else {
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.font = `${symbol * 1.2}px system-ui, sans-serif`;
      ctx.fillText("♪", cx, h * 0.42);
    }
    drawName(ctx, card.name, cx, h * 0.75, w - px(10), px(9));
    return canvas;
  }

  /**
   * White, with the QR code of the content.json link centred, inside a FINGER_MARGIN border on every
   * side. The card's name goes in the bottom border.
   */
  function drawBack(card) {
    const canvas = newCanvas(CARD_W, CARD_H);
    const ctx = canvas.getContext("2d");
    const { width: w, height: h } = canvas;
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, w, h);

    const qr = qrcode(0, "M");
    qr.addData(card.contentUrl);
    qr.make();
    const modules = qr.getModuleCount();
    const space = px(Math.min(CARD_W, CARD_H) - 2 * FINGER_MARGIN);
    const module = Math.floor(space / modules); // whole pixels: crisp
    const size = module * modules;
    const left = Math.round((w - size) / 2);
    const top = Math.round((h - size) / 2);
    ctx.fillStyle = "#000";
    for (let r = 0; r < modules; r++) {
      for (let c = 0; c < modules; c++) {
        if (qr.isDark(r, c)) ctx.fillRect(left + c * module, top + r * module, module, module);
      }
    }

    ctx.fillStyle = "#222";
    drawName(ctx, card.name, w / 2, (top + size + h) / 2, w - px(10), px(6));
    return canvas;
  }

  /** One line, centred, shrunk to fit (and cut with an ellipsis if even the smallest size doesn't). */
  function drawName(ctx, text, x, y, maxWidth, size) {
    const min = size * 0.65;
    const font = (s) => `600 ${s}px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`;
    ctx.font = font(size);
    while (ctx.measureText(text).width > maxWidth && size > min) {
      size *= 0.92;
      ctx.font = font(size);
    }
    let shown = text;
    while (ctx.measureText(shown).width > maxWidth && shown.length > 1) shown = shown.slice(0, -2) + "…";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(shown, x, y);
  }

  function hue(text) {
    let hash = 0;
    for (const ch of text) hash = (hash * 31 + ch.codePointAt(0)) >>> 0;
    return hash % 360;
  }

  async function canvasToJpeg(canvas) {
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.92));
    return { bytes: new Uint8Array(await blob.arrayBuffer()), width: canvas.width, height: canvas.height };
  }

  // ---- Minimal PDF writer ----

  /** pages: [{ images: [{ jpeg: { bytes, width, height }, x, y, w, h }], lines: [[x1, y1, x2, y2]] }], mm from top-left. */
  function writePdf(pages) {
    const encoder = new TextEncoder();
    const chunks = [];
    const offsets = [];
    let length = 0;
    const push = (data) => {
      const bytes = typeof data === "string" ? encoder.encode(data) : data;
      chunks.push(bytes);
      length += bytes.length;
    };
    const object = (id, dict, stream) => {
      offsets[id] = length;
      push(`${id} 0 obj\n${dict}\n`);
      if (stream) {
        push("stream\n");
        push(stream);
        push("\nendstream\n");
      }
      push("endobj\n");
    };
    const n = (mm) => (mm * PT_PER_MM).toFixed(3);

    // Ids: 1 catalog, 2 page tree, then per page: page, contents, its images.
    let nextId = 3;
    const layout = pages.map((page) => ({
      page,
      id: nextId++,
      contentsId: nextId++,
      imageIds: page.images.map(() => nextId++),
    }));

    push(new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37, 0x0a, 0x25, 0xe2, 0xe3, 0xcf, 0xd3, 0x0a])); // %PDF-1.7 + binary marker
    // PrintScaling /None: viewers default to actual size. Duplex: default to long-edge double-sided.
    object(1, "<< /Type /Catalog /Pages 2 0 R /ViewerPreferences << /PrintScaling /None /Duplex /DuplexFlipLongEdge >> >>");
    object(2, `<< /Type /Pages /Kids [${layout.map((p) => `${p.id} 0 R`).join(" ")}] /Count ${layout.length} >>`);

    for (const { page, id, contentsId, imageIds } of layout) {
      const ops = [];
      page.images.forEach((img, i) => {
        ops.push(`q ${n(img.w)} 0 0 ${n(img.h)} ${n(img.x)} ${n(PAGE_H - img.y - img.h)} cm /Im${i} Do Q`);
      });
      if (page.lines.length) {
        ops.push(`${n(0.2)} w 0 G`);
        for (const [x1, y1, x2, y2] of page.lines) ops.push(`${n(x1)} ${n(PAGE_H - y1)} m ${n(x2)} ${n(PAGE_H - y2)} l S`);
      }
      const contents = encoder.encode(ops.join("\n"));
      const xobjects = imageIds.map((imageId, i) => `/Im${i} ${imageId} 0 R`).join(" ");
      object(
        id,
        `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(PAGE_W)} ${n(PAGE_H)}] ` +
          `/Resources << /XObject << ${xobjects} >> >> /Contents ${contentsId} 0 R >>`,
      );
      object(contentsId, `<< /Length ${contents.length} >>`, contents);
      page.images.forEach(({ jpeg }, i) => {
        object(
          imageIds[i],
          `<< /Type /XObject /Subtype /Image /Width ${jpeg.width} /Height ${jpeg.height} /ColorSpace /DeviceRGB ` +
            `/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.bytes.length} >>`,
          jpeg.bytes,
        );
      });
    }

    const xref = length;
    const count = nextId;
    let table = `xref\n0 ${count}\n0000000000 65535 f \n`;
    for (let id = 1; id < count; id++) table += `${String(offsets[id]).padStart(10, "0")} 00000 n \n`;
    push(table);
    push(`trailer\n<< /Size ${count} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);

    const out = new Uint8Array(length);
    let at = 0;
    for (const chunk of chunks) {
      out.set(chunk, at);
      at += chunk.length;
    }
    return out;
  }

  return { makePdf, PER_SHEET };
})();
