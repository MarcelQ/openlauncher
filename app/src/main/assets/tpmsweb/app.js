/*
 * The phone page is a dumb viewer, and that is deliberate.
 *
 * Every number, every colour decision and every warning verdict arrives from /tpms.json, which the
 * launcher computes with the same alarmingWheels() decision the dash card and the audible chime use.
 * Nothing here compares pressures or picks thresholds — that is what keeps the phone from ever
 * disagreeing with the dash when the user changes units, thresholds or the alarm switch.
 *
 * Served by the launcher itself (tpms\TpmsWebServer.kt) from assets/tpmsweb/, so it works with the
 * car and the phone both fully offline. External file rather than inline so the server's CSP needs
 * no 'unsafe-inline'.
 */
const POLL_MS = 2000;

const grid = document.getElementById('grid');
const ageEl = document.getElementById('age');
const checkEl = document.getElementById('check');
const banner = document.getElementById('banner');
const linkIcon = document.getElementById('linkIcon');
const titleEl = document.getElementById('title');
let lastGoodPayloadAt = 0;
let lastGoodData = null;

function esc(s) {
  return String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function setLink(status, detail) {
  linkIcon.className =
    status === 'CONNECTED' ? 'link-ok' :
    status === 'CONNECTING' ? 'link-idle' :
    status === 'ERROR' ? 'link-bad' : 'link-off';
  // Same wording the card's USB glyph carries in its content description.
  linkIcon.setAttribute('aria-label', detail || (status ? status.toLowerCase() : 'unknown'));
}

function setBanner(text) {
  if (text) { banner.textContent = text; banner.classList.remove('hidden'); }
  else banner.classList.add('hidden');
}

function temperatureText(w) {
  if (w.temp === null || w.temp === undefined) return '—';
  // The JSON carries Celsius; only the display conversion happens here, exactly as on the card.
  return (w.metric === false || w.fahrenheit === true)
    ? Math.round(w.temp * 9 / 5 + 32) + '\u00B0F'
    : Math.round(w.temp) + '\u00B0';
}

function render(data) {
  const wheels = data.wheels || [];
  const hasSpare = wheels.some(w => w.pos === 'SP');
  const anyAlarm = wheels.some(w => w.alarm);
  const linkDown = (data.status === 'DISCONNECTED' || data.status === 'ERROR');

  grid.className = 'grid' +
    (hasSpare && data.showSpare ? ' has-spare' : '') +
    (linkDown ? ' link-down' : '') +
    (anyAlarm ? ' alarm-kept' : '');

  grid.innerHTML = wheels.map(w => {
    const cls = ['tile'];
    if (w.pos === 'SP') cls.push('spare');
    if (w.alarm) cls.push('alarm', 'pulsing');
    else if (!w.hasReading || w.stale) cls.push('dimmed');
    else if (w.noSignal) cls.push('nosignal');

    // Exactly the card's text rules: a wheel that never reported is "—", never a plausible number,
    // and a sensor the receiver cannot hear says NO SIGNAL instead of a stale temperature.
    const value = (w.pressure === null || w.pressure === undefined)
      ? '\u2014'
      : w.pressure.toFixed(data.decimals);

    let sub = '\u2014';
    if (w.hasReading) {
      if (w.noSignal) sub = 'NO SIGNAL';
      else {
        sub = temperatureText({ temp: w.temp, metric: data.metric });
        if (w.stale && w.ageSeconds !== null) sub += ' ' + w.ageSeconds + 's';
      }
    }

    return '<div class="' + cls.join(' ') + '" role="img" aria-label="' +
      esc(w.name + ': ' + value + ' ' + (data.unit || '') + (w.alarm ? ', warning' : '')) + '">' +
      '<div class="pos">' + esc(w.pos) + '</div>' +
      '<div class="value">' + esc(value) + '</div>' +
      '<div class="unit">' + esc(data.unit || '') + '</div>' +
      '<div class="sub">' + esc(sub) + '</div>' +
      '</div>';
  }).join('');

  setLink(data.status, data.detail);

  const alarming = wheels.filter(w => w.alarm);
  if (alarming.length) {
    checkEl.textContent = 'CHECK: ' + alarming.map(w => w.pos).join(' ');
    checkEl.classList.remove('hidden');
    document.title = '\u26A0 ' + (data.unit === 'bar' ? 'Tyre pressure' : 'Tyre pressure');
    if (titleEl) titleEl.textContent = 'Warning';
  } else {
    checkEl.classList.add('hidden');
    document.title = 'Tyre Pressure';
    if (titleEl) titleEl.textContent = 'Tyre pressure';
  }

  if (data.lastFrameAgeSeconds === null || data.lastFrameAgeSeconds === undefined) {
    ageEl.textContent = 'no sensor frames';
  } else {
    ageEl.textContent = 'link ' + data.lastFrameAgeSeconds + 's ago';
  }

  // The same four link states the card footer glyph encodes by colour.
  if (data.status === 'CONNECTED' && data.lastFrameAgeSeconds !== null && data.lastFrameAgeSeconds > 20) {
    setBanner('Receiver link idle \u2014 sensors have not reported for ' + data.lastFrameAgeSeconds + 's.');
  } else if (data.status === 'CONNECTING') {
    setBanner('Opening the USB receiver\u2026');
  } else if (data.status === 'DISCONNECTED') {
    setBanner('No USB TPMS receiver attached' + (data.detail ? ' (' + data.detail + ')' : '') + '.');
  } else if (data.status === 'ERROR') {
    setBanner('USB receiver link error: ' + (data.detail || 'unknown') + '.');
  } else {
    setBanner('');
  }

  lastGoodPayloadAt = Date.now();
  lastGoodData = data;
  fitScale();
}

/**
 * Fit the numerals to the tile the way the card does (see fontScaleFor in TpmsWidget.kt):
 * min(height/60, width/115), clamped, so a phone held at arm's length and a tablet on the
 * passenger mount both stay legible without the digits ever overflowing a tile.
 */
function fitScale() {
  const tile = grid.querySelector('.tile');
  if (!tile) return;
  const byHeight = Math.min(Math.max(tile.clientHeight / 60, 0.8), 2.4);
  const byWidth = Math.min(Math.max(tile.clientWidth / 115, 0.8), 2.4);
  document.documentElement.style.setProperty('--scale', Math.min(byHeight, byWidth).toFixed(3));
}

async function poll() {
  try {
    const res = await fetch('/tpms.json', { cache: 'no-store' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    render(await res.json());
  } catch (e) {
    // The page outlives the link all the time: the phone's home-screen shortcut is opened when the
    // hotspot is off, the app was killed, or the card's WEB button was switched off. Say so loudly
    // instead of freezing stale numbers into something that looks live, and keep the last-known
    // values visible but dimmed — the card does exactly the same thing.
    const reason = (e && e.message) ? e.message : String(e);
    const age = lastGoodPayloadAt ? Math.round((Date.now() - lastGoodPayloadAt) / 1000) : null;
    setLink('ERROR', reason);
    grid.classList.add('link-down');
    if (lastGoodData) grid.classList.toggle('alarm-kept', (lastGoodData.wheels || []).some(w => w.alarm));
    ageEl.textContent = age === null ? 'no data' : 'last data ' + age + 's ago';
    setBanner('Cannot reach the launcher (' + reason + '). Check that the phone is on the head ' +
      'unit\u2019s hotspot and that the card\u2019s WEB button is on.');
  }
}

poll();
setInterval(poll, POLL_MS);
window.addEventListener('resize', fitScale);
// Re-read immediately when the page comes back to the foreground from the home-screen shortcut,
// so the numbers are current the moment the driver glances at the phone.
document.addEventListener('visibilitychange', () => { if (!document.hidden) poll(); });
