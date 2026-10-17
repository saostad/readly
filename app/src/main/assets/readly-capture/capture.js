/* readly-capture content script (protocol "readly-capture/2").
 *
 * Runs in the owned GeckoSession's top frame on HTTPS article pages. Opens a
 * native-messaging port to the embedding app and waits for capture requests:
 * each request carries a native-issued token that every snapshot message
 * must echo back, binding the snapshot to the app's current read. A new
 * request (same-document SPA navigation) supersedes any in-flight capture;
 * only the latest token's messages are accepted.
 *
 * Waits for load completion plus a DOM quiet window (so the real Bypass
 * Paywalls Clean extension has time to process the page), then sends a
 * bounded DOM snapshot over the port.
 *
 * This helper ONLY captures DOM/status for article reading. It contains no
 * bypass, header, cookie, or request logic of any kind.
 */
(function () {
  "use strict";

  const PROTOCOL = "readly-capture/2";
  const NATIVE_APP = "readlyArticle";
  const KIND_REQUEST = "capture-request";
  const MAX_BYTES = 5 * 1024 * 1024; // 5 MiB UTF-8 budget per payload (text, html)
  // 64 Ki-char slices: <= 192 KiB UTF-8 each, never splits a code unit, and at
  // most 80 chunks for a full 5 MiB all-ASCII payload (native cap is 96).
  const CHARS_PER_CHUNK = 65536;
  const MAX_TOKEN = 128;
  const LOAD_TIMEOUT_MS = 20000;
  const QUIET_MS = 2500;
  const OVERALL_TIMEOUT_MS = 30000;
  const RECONNECT_DELAY_MS = 2000;

  // Top frame only; the native side additionally requires sender.isTopLevel.
  if (window !== window.top) {
    return;
  }

  // The token of the latest capture request; older cycles abort instead of
  // sending (their messages would be rejected natively anyway).
  let activeToken = null;
  let gotRequest = false;
  let reconnectTried = false;

  function connect() {
    let port = null;
    try {
      port = browser.runtime.connectNative(NATIVE_APP);
    } catch (e) {
      return;
    }
    if (!port) {
      return;
    }
    port.onMessage.addListener((message) => {
      try {
        if (!message || message.protocol !== PROTOCOL) {
          return;
        }
        if (message.kind !== KIND_REQUEST) {
          return;
        }
        const token = message.requestToken;
        if (typeof token !== "string" || token.length === 0 || token.length > MAX_TOKEN) {
          return;
        }
        gotRequest = true;
        activeToken = token;
        runCapture(port, token);
      } catch (ignored) {
        // Malformed requests are dropped.
      }
    });
    port.onDisconnect.addListener(() => {
      // The app disconnects to retire this document (navigation, pause).
      // A disconnect before any request may be a transient race, so retry
      // the connection exactly once; otherwise stop quietly.
      activeToken = null;
      if (!gotRequest && !reconnectTried) {
        reconnectTried = true;
        setTimeout(connect, RECONNECT_DELAY_MS);
      }
    });
  }

  function post(port, token, message) {
    // Superseded cycles never send: only the latest token is live.
    if (token !== activeToken) {
      return false;
    }
    try {
      port.postMessage(message);
    } catch (e) {
      // The port is gone; the app's capture watchdog already owns recovery.
      return false;
    }
    return true;
  }

  function reportError(port, token, captureId, pageUrl, e) {
    // Single bounded error report, never recursive.
    post(port, token, {
      protocol: PROTOCOL,
      kind: "error",
      captureId: captureId,
      url: pageUrl,
      requestToken: token,
      message: String((e && e.message) || e).slice(0, 200),
    });
  }

  function utf8Bytes(s) {
    return new TextEncoder().encode(s).length;
  }

  // Truncate a string so its UTF-8 encoding fits maxBytes. Never splits a
  // UTF-16 code unit; returns [text, truncated].
  function fitUtf8(s, maxBytes) {
    if (utf8Bytes(s) <= maxBytes) {
      return [s, false];
    }
    let lo = 0;
    let hi = s.length;
    while (lo < hi) {
      const mid = Math.floor((lo + hi + 1) / 2);
      if (utf8Bytes(s.slice(0, mid)) <= maxBytes) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    // Avoid ending on a dangling high surrogate.
    let end = lo;
    if (end > 0 && end < s.length) {
      const c = s.charCodeAt(end - 1);
      if (c >= 0xd800 && c <= 0xdbff) {
        end -= 1;
      }
    }
    return [s.slice(0, end), true];
  }

  function waitForLoad() {
    return new Promise((resolve) => {
      if (document.readyState === "complete") {
        resolve();
        return;
      }
      const timer = setTimeout(() => {
        window.removeEventListener("load", onLoad);
        resolve();
      }, LOAD_TIMEOUT_MS);
      function onLoad() {
        clearTimeout(timer);
        resolve();
      }
      window.addEventListener("load", onLoad);
    });
  }

  // Resolve after QUIET_MS without DOM mutations, or at the overall deadline.
  function waitForQuiet(startedAt) {
    return new Promise((resolve) => {
      const deadline = startedAt + OVERALL_TIMEOUT_MS;
      let quietTimer = null;
      const observer = new MutationObserver(() => {
        if (quietTimer) {
          clearTimeout(quietTimer);
        }
        const remaining = deadline - Date.now();
        if (remaining <= 0) {
          observer.disconnect();
          resolve();
          return;
        }
        quietTimer = setTimeout(() => {
          observer.disconnect();
          resolve();
        }, Math.min(QUIET_MS, remaining));
      });
      if (document.documentElement) {
        observer.observe(document.documentElement, {
          childList: true,
          subtree: true,
          attributes: true,
          characterData: true,
        });
      }
      const remaining = deadline - Date.now();
      quietTimer = setTimeout(() => {
        observer.disconnect();
        resolve();
      }, Math.max(0, Math.min(QUIET_MS, remaining)));
    });
  }

  const GATE_CLASS_TOKENS = [
    "paywall",
    "registration-wall",
    "regwall",
    "signin-wall",
    "premium-lock",
    "metered-wall",
    "tp-modal",
    "piano",
    "tinypass",
    "zephr",
  ];

  function scanGateNodes() {
    const found = [];
    try {
      const els = document.querySelectorAll("[class],[id]");
      const limit = Math.min(els.length, 20000);
      for (let i = 0; i < limit; i++) {
        const el = els[i];
        const hay = ((el.className && el.className.baseVal !== undefined
          ? String(el.className.baseVal)
          : String(el.className || "")) + " " + String(el.id || "")).toLowerCase();
        for (const token of GATE_CLASS_TOKENS) {
          if (hay.indexOf(token) !== -1 && found.indexOf(token) === -1) {
            found.push(token);
          }
        }
        if (found.length === GATE_CLASS_TOKENS.length) {
          break;
        }
      }
    } catch (e) {
      // Report what we have.
    }
    return found;
  }

  const GATE_TEXT_SIGNALS = [
    "subscribe to continue reading",
    "sign in to continue reading",
    "register to continue reading",
    "you have reached your article limit",
    "remaining free article",
    "premium article",
    "members only",
    "already a subscriber",
    "continue reading with a subscription",
  ];

  function scanGateText(lowerText) {
    const found = [];
    for (const signal of GATE_TEXT_SIGNALS) {
      if (lowerText.indexOf(signal) !== -1) {
        found.push(signal);
      }
    }
    return found;
  }

  function snapshot(port, token, captureId, startedAt, pageUrl) {
    let text = "";
    let html = "";
    try {
      text = document.body ? document.body.innerText || "" : "";
    } catch (e) {
      text = "";
    }
    try {
      html = document.documentElement ? document.documentElement.outerHTML || "" : "";
    } catch (e) {
      html = "";
    }
    const fittedText = fitUtf8(text, MAX_BYTES);
    const fittedHtml = fitUtf8(html, MAX_BYTES);
    const finalText = fittedText[0];
    const finalHtml = fittedHtml[0];
    let paragraphs = 0;
    try {
      paragraphs = document.querySelectorAll("p").length;
    } catch (e) {
      paragraphs = -1;
    }
    const gateNodes = scanGateNodes();
    const gateText = scanGateText(finalText.toLowerCase().slice(0, 1048576));

    const textChunks = [];
    for (let off = 0; off < finalText.length; off += CHARS_PER_CHUNK) {
      textChunks.push(finalText.slice(off, off + CHARS_PER_CHUNK));
    }
    const htmlChunks = [];
    for (let off = 0; off < finalHtml.length; off += CHARS_PER_CHUNK) {
      htmlChunks.push(finalHtml.slice(off, off + CHARS_PER_CHUNK));
    }

    if (!post(port, token, {
      protocol: PROTOCOL,
      kind: "meta",
      captureId: captureId,
      url: pageUrl,
      requestToken: token,
      title: String(document.title || "").slice(0, 500),
      readyState: String(document.readyState || ""),
      elapsedMs: Date.now() - startedAt,
      textBytes: utf8Bytes(finalText),
      htmlBytes: utf8Bytes(finalHtml),
      textTruncated: fittedText[1],
      htmlTruncated: fittedHtml[1],
      textChunks: textChunks.length,
      htmlChunks: htmlChunks.length,
      paragraphs: paragraphs,
      gateNodes: gateNodes,
      gateText: gateText,
    })) {
      return;
    }

    for (let i = 0; i < textChunks.length; i++) {
      if (!post(port, token, {
        protocol: PROTOCOL,
        kind: "text-chunk",
        captureId: captureId,
        url: pageUrl,
        requestToken: token,
        index: i,
        total: textChunks.length,
        data: textChunks[i],
      })) {
        return;
      }
    }
    for (let i = 0; i < htmlChunks.length; i++) {
      if (!post(port, token, {
        protocol: PROTOCOL,
        kind: "html-chunk",
        captureId: captureId,
        url: pageUrl,
        requestToken: token,
        index: i,
        total: htmlChunks.length,
        data: htmlChunks[i],
      })) {
        return;
      }
    }
    post(port, token, {
      protocol: PROTOCOL,
      kind: "done",
      captureId: captureId,
      url: pageUrl,
      requestToken: token,
    });
  }

  function runCapture(port, token) {
    const captureId =
      Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 10);
    const startedAt = Date.now();
    waitForLoad()
      .then(() => {
        if (token !== activeToken) {
          return null;
        }
        return waitForQuiet(startedAt);
      })
      .then((quiet) => {
        if (quiet === null || token !== activeToken) {
          return;
        }
        snapshot(port, token, captureId, startedAt, String(location.href));
      })
      .catch((e) => {
        reportError(port, token, captureId, String(location.href), e);
      });
  }

  connect();
})();
