const STORAGE_KEY = 'soundboard-scoreboard-settings';
const DEFAULT_POLL_MS = 5000;
const DEFAULT_DELAY_MS = 0;

let lastEventId = null;
let playerCache = {};
let playByPlayTimeout;
let delay = DEFAULT_DELAY_MS;
let pollIntervalMs = DEFAULT_POLL_MS;
let playLockUntil = 0;
let eventInterval = null;
let activeGameId = null;
let syncSequence = 0;

const el = {
  settingsPanel: document.getElementById('settings'),
  settingsButton: document.getElementById('settings-button'),
  updateButton: document.getElementById('update-button'),
  closeButton: document.getElementById('close-button'),
  refreshNowButton: document.getElementById('refresh-now-button'),
  gameIdInput: document.getElementById('game-id-input'),
  delayInput: document.getElementById('gamedelay'),
  refreshInput: document.getElementById('refresh-interval'),
  homeImage: document.getElementById('home-image'),
  awayImage: document.getElementById('away-image'),
  homeScore: document.getElementById('home-score'),
  awayScore: document.getElementById('away-score'),
  homeSOG: document.getElementById('homeSOG'),
  awaySOG: document.getElementById('awaySOG'),
  homeHits: document.getElementById('home-hits'),
  awayHits: document.getElementById('away-hits'),
  homePim: document.getElementById('home-pim'),
  awayPim: document.getElementById('away-pim'),
  homePpg: document.getElementById('home-ppg'),
  awayPpg: document.getElementById('away-ppg'),
  homeFopct: document.getElementById('home-fopct'),
  awayFopct: document.getElementById('away-fopct'),
  periodTime: document.getElementById('period-time'),
  periodLabel: document.getElementById('period-label'),
  gameState: document.getElementById('game-state'),
  homeTeamName: document.getElementById('home-team-name'),
  awayTeamName: document.getElementById('away-team-name'),
  homeRecord: document.getElementById('home-record'),
  awayRecord: document.getElementById('away-record'),
  gameTitle: document.getElementById('game-title'),
  lastUpdated: document.getElementById('last-updated'),
  syncState: document.getElementById('sync-state'),
  currentDelay: document.getElementById('current-delay'),
  pollIntervalPill: document.getElementById('poll-interval-pill'),
  gameIdDisplay: document.getElementById('game-id-display'),
  delayDisplay: document.getElementById('delay-display'),
  clockSource: document.getElementById('clock-source'),
  eventLock: document.getElementById('event-lock'),
  headshots: document.getElementById('headshots-container'),
  playDesc: document.getElementById('play-description'),
  panthersVideo: document.getElementById('panthers-video'),
  lightningVideo: document.getElementById('lightning-video'),
  scoreboard: document.getElementById('scoreboard'),
  playByPlayDiv: document.getElementById('play-by-play'),
  nudgeButtons: Array.from(document.querySelectorAll('[data-adjust-seconds]'))
};

function openSettings() {
  el.settingsPanel.classList.add('is-open');
  el.settingsPanel.setAttribute('aria-hidden', 'false');
}

function closeSettings() {
  el.settingsPanel.classList.remove('is-open');
  el.settingsPanel.setAttribute('aria-hidden', 'true');
}

el.settingsButton.addEventListener('click', openSettings);
el.closeButton.addEventListener('click', closeSettings);
el.refreshNowButton.addEventListener('click', () => {
  if (activeGameId) {
    refreshCurrentGame(true);
  }
});

el.nudgeButtons.forEach((button) => {
  button.addEventListener('click', () => {
    const current = parseFloat(el.delayInput.value || '0');
    const delta = parseFloat(button.dataset.adjustSeconds || '0');
    const nextValue = Math.max(0, current + delta);
    el.delayInput.value = nextValue.toFixed(1);
    applySyncInputs();
    saveSettings();
  });
});

function parseGameId(input) {
  const value = (input || '').trim();
  if (!value) {
    return '';
  }

  const directDigits = value.match(/\b\d{10}\b/);
  if (directDigits) {
    return directDigits[0];
  }

  const gameCenterMatch = value.match(/gamecenter\/(\d{10})/i);
  if (gameCenterMatch) {
    return gameCenterMatch[1];
  }

  return value.replace(/\D/g, '');
}

function formatDelay(ms) {
  return `${(ms / 1000).toFixed(1)}s`;
}

function formatTimestamp(date = new Date()) {
  return date.toLocaleTimeString([], {
    hour: 'numeric',
    minute: '2-digit',
    second: '2-digit'
  });
}

function applySyncInputs() {
  delay = Math.max(0, Math.round((parseFloat(el.delayInput.value || '0') || 0) * 1000));
  pollIntervalMs = Math.max(2000, Math.round((parseFloat(el.refreshInput.value || '5') || 5) * 1000));
  el.currentDelay.textContent = `Delay ${formatDelay(delay)}`;
  el.pollIntervalPill.textContent = `Refresh ${(pollIntervalMs / 1000).toFixed(1)}s`;
  el.delayDisplay.textContent = `${delay} ms`;
}

function saveSettings() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({
    gameIdInput: el.gameIdInput.value,
    delaySeconds: el.delayInput.value,
    refreshSeconds: el.refreshInput.value
  }));
}

function loadSettings() {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) {
    applySyncInputs();
    return;
  }

  try {
    const settings = JSON.parse(raw);
    if (settings.gameIdInput) {
      el.gameIdInput.value = settings.gameIdInput;
    }
    if (settings.delaySeconds !== undefined) {
      el.delayInput.value = settings.delaySeconds;
    }
    if (settings.refreshSeconds !== undefined) {
      el.refreshInput.value = settings.refreshSeconds;
    }
  } catch (err) {
    console.warn('Could not restore saved settings', err);
  }

  applySyncInputs();
}

function updateSyncState(text, isError = false) {
  el.syncState.textContent = text;
  el.syncState.style.color = isError ? 'var(--danger)' : 'var(--text-main)';
}

function updateLogos(homeUrl, awayUrl) {
  fetch(`/convertSvgToPng?svgUrl=${encodeURIComponent(homeUrl)}`)
    .then((r) => r.blob())
    .then((blob) => {
      const url = URL.createObjectURL(blob);
      el.homeImage.onload = () => pickColors(el.homeImage, (primary, secondary, tertiary) => {
        document.getElementById('left-half').style.background =
          `radial-gradient(circle at top left, rgba(255,255,255,0.08), transparent 48%),` +
          `linear-gradient(135deg, ${primary} 0%, ${secondary} 58%, ${tertiary} 100%)`;
      });
      el.homeImage.src = url;
    })
    .catch((err) => console.error('Home logo update failed', err));

  fetch(`/convertSvgToPng?svgUrl=${encodeURIComponent(awayUrl)}`)
    .then((r) => r.blob())
    .then((blob) => {
      const url = URL.createObjectURL(blob);
      el.awayImage.onload = () => pickColors(el.awayImage, (primary, secondary, tertiary) => {
        document.getElementById('right-half').style.background =
          `radial-gradient(circle at top right, rgba(255,255,255,0.08), transparent 48%),` +
          `linear-gradient(225deg, ${primary} 0%, ${secondary} 58%, ${tertiary} 100%)`;
      });
      el.awayImage.src = url;
    })
    .catch((err) => console.error('Away logo update failed', err));
}

function pickColors(img, cb) {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  canvas.width = img.width;
  canvas.height = img.height;
  ctx.drawImage(img, 0, 0);
  const data = ctx.getImageData(0, 0, img.width, img.height).data;
  const pixels = [];

  for (let i = 0; i < data.length; i += 4) {
    const [r, g, b, a] = [data[i], data[i + 1], data[i + 2], data[i + 3]];
    if ((r === 255 && g === 255 && b === 255) || a === 0) {
      continue;
    }
    pixels.push([r, g, b]);
  }

  const centroids = kMeans(pixels, 3);
  cb(
    `rgb(${centroids[0].join(',')})`,
    `rgb(${centroids[1].join(',')})`,
    `rgb(${centroids[2].join(',')})`
  );
}

function kMeans(data, k) {
  let centroids = initCentroids(data, k);
  let old;

  do {
    old = centroids.map((c) => c.slice());
    const clusters = Array.from({ length: k }, () => []);
    data.forEach((point) => {
      clusters[nearest(point, centroids)].push(point);
    });
    clusters.forEach((cluster, i) => {
      if (cluster.length) {
        centroids[i] = meanColor(cluster);
      }
    });
  } while (!equalCentroids(centroids, old));

  return centroids;
}

function initCentroids(data, k) {
  const centroids = [];
  const used = new Set();
  while (centroids.length < k && data.length) {
    const i = Math.floor(Math.random() * data.length);
    if (!used.has(i)) {
      used.add(i);
      centroids.push(data[i]);
    }
  }
  return centroids.length ? centroids : [[20, 30, 45], [35, 55, 90], [70, 100, 140]];
}

function nearest(point, centroids) {
  let min = Infinity;
  let idx = 0;
  centroids.forEach((centroid, i) => {
    const dist = Math.hypot(point[0] - centroid[0], point[1] - centroid[1], point[2] - centroid[2]);
    if (dist < min) {
      min = dist;
      idx = i;
    }
  });
  return idx;
}

function meanColor(cluster) {
  const sum = [0, 0, 0];
  cluster.forEach((point) => {
    sum[0] += point[0];
    sum[1] += point[1];
    sum[2] += point[2];
  });
  return sum.map((value) => Math.floor(value / cluster.length));
}

function equalCentroids(a, b) {
  if (a.length !== b.length) {
    return false;
  }
  return a.every((centroid, i) => centroid.every((value, j) => value === b[i][j]));
}

const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
const sock = new WebSocket(`${wsProtocol}//${window.location.hostname}:8080/client`);
sock.onopen = () => console.log('WS open');
sock.onmessage = (e) => {
  if (e.data === 'spressed') {
    playVideo(el.panthersVideo);
  }
  if (e.data === 'lpressed') {
    playVideo(el.lightningVideo);
  }
  if (e.data === 'all_stop') {
    stopAllVideos();
  }
};

function playVideo(video) {
  video.style.display = 'block';
  el.scoreboard.style.visibility = 'hidden';
  el.playByPlayDiv.style.visibility = 'hidden';
  video.play();
  if (playByPlayTimeout) {
    clearTimeout(playByPlayTimeout);
  }
  playByPlayTimeout = setTimeout(() => {
    el.playByPlayDiv.style.visibility = 'visible';
  }, 10000);
  video.onended = () => video.play();
}

function stopAllVideos() {
  [el.panthersVideo, el.lightningVideo].forEach((video) => {
    video.pause();
    video.style.display = 'none';
  });
  el.scoreboard.style.visibility = 'visible';
  el.playByPlayDiv.style.visibility = 'visible';
}

async function fetchPlayerInfo(id) {
  if (playerCache[id]) {
    return playerCache[id];
  }
  const res = await fetch(`/proxy/${id}`);
  const data = await res.json();
  playerCache[id] = {
    firstName: data.firstName?.default || '',
    lastName: data.lastName?.default || '',
    sweaterNumber: data.sweaterNumber || '',
    headshot: data.headshot,
    heroImage: data.heroImage
  };
  return playerCache[id];
}

function renderHeadshots(players) {
  el.headshots.innerHTML = players
    .filter(Boolean)
    .map((player) => `<img src="${player.headshot}" alt="${player.lastName || player.firstName || 'Player'}">`)
    .join('');
}

async function generateGoalDescription(play) {
  const scorer = await fetchPlayerInfo(play.details.scoringPlayerId);
  const assist1 = play.details.assist1PlayerId ? await fetchPlayerInfo(play.details.assist1PlayerId) : null;
  const assist2 = play.details.assist2PlayerId ? await fetchPlayerInfo(play.details.assist2PlayerId) : null;
  const goalie = play.details.goalieInNetId ? await fetchPlayerInfo(play.details.goalieInNetId) : null;

  renderHeadshots([scorer, assist1, assist2, goalie]);

  const parts = [`Goal scored by #${scorer.sweaterNumber} ${scorer.firstName} ${scorer.lastName}`];
  if (assist1) {
    parts.push(`assisted by #${assist1.sweaterNumber} ${assist1.firstName} ${assist1.lastName}`);
  }
  if (assist2) {
    parts.push(`and #${assist2.sweaterNumber} ${assist2.firstName} ${assist2.lastName}`);
  }
  if (goalie) {
    parts.push(`against #${goalie.sweaterNumber} ${goalie.firstName} ${goalie.lastName}`);
  }
  return parts.join(', ') + '.';
}

async function generateFaceoffDescription(play) {
  const winner = await fetchPlayerInfo(play.details.winningPlayerId);
  const loser = await fetchPlayerInfo(play.details.losingPlayerId);
  renderHeadshots([winner, loser]);
  return `Faceoff won by #${winner.sweaterNumber} ${winner.firstName} ${winner.lastName} against #${loser.sweaterNumber} ${loser.firstName} ${loser.lastName}.`;
}

async function generatePenaltyDescription(play) {
  const player = await fetchPlayerInfo(play.details.committedByPlayerId);
  renderHeadshots([player]);
  return `Penalty on #${player.sweaterNumber} ${player.firstName} ${player.lastName} for ${play.details.descKey || 'a penalty'}.`;
}

async function generateStoppageDescription() {
  el.headshots.innerHTML = '';
  return 'Play has been stopped.';
}

const eventTypeMapping = {
  goal: generateGoalDescription,
  faceoff: generateFaceoffDescription,
  penalty: generatePenaltyDescription,
  stoppage: generateStoppageDescription
};

function updateEventLockLabel() {
  const remaining = Math.max(0, playLockUntil - Date.now());
  el.eventLock.textContent = remaining > 0 ? `Holding ${Math.ceil(remaining / 1000)}s` : 'No hold';
}

function setClockDisplay(timeRemaining, periodNumber, sourceLabel = 'Landing') {
  el.periodTime.textContent = timeRemaining || '--:--';
  el.periodLabel.textContent = periodNumber ? `Period ${periodNumber}` : 'Period -';
  el.clockSource.textContent = sourceLabel;
}

function formatGameState(gameState, gameScheduleState) {
  return gameState || gameScheduleState || 'Waiting for game';
}

function setStatValue(node, value, suffix = '') {
  node.textContent = value === undefined || value === null || value === '' ? '-' : `${value}${suffix}`;
}

function extractStat(teamData, summaryTeam, summaryKey) {
  return summaryTeam?.[summaryKey] ?? teamData?.[summaryKey];
}

function applyLandingData(landingData) {
  const home = landingData.homeTeam || {};
  const away = landingData.awayTeam || {};
  const summary = landingData.summary?.teamGameStats || {};

  el.homeTeamName.textContent = home.placeName?.default || home.abbrev || 'Home';
  el.awayTeamName.textContent = away.placeName?.default || away.abbrev || 'Away';
  el.homeRecord.textContent = home.record || home.abbrev || '';
  el.awayRecord.textContent = away.record || away.abbrev || '';
  el.gameTitle.textContent = `${away.placeName?.default || away.abbrev || 'Away'} at ${home.placeName?.default || home.abbrev || 'Home'}`;
  el.gameState.textContent = formatGameState(landingData.gameState, landingData.gameScheduleState);

  const periodNumber = landingData.periodDescriptor?.number || landingData.period;
  const timeRemaining = landingData.clock?.timeRemaining || landingData.timeRemaining;
  setClockDisplay(timeRemaining, periodNumber, 'Landing');

  setStatValue(el.homePim, extractStat(home, summary.homeTeam, 'pim'));
  setStatValue(el.awayPim, extractStat(away, summary.awayTeam, 'pim'));
  setStatValue(el.homeHits, extractStat(home, summary.homeTeam, 'hits'));
  setStatValue(el.awayHits, extractStat(away, summary.awayTeam, 'hits'));
  setStatValue(el.homeFopct, extractStat(home, summary.homeTeam, 'faceoffWinningPctg'), '%');
  setStatValue(el.awayFopct, extractStat(away, summary.awayTeam, 'faceoffWinningPctg'), '%');

  const homePp = summary.homeTeam?.powerPlayConversion || home.powerPlayConversion;
  const awayPp = summary.awayTeam?.powerPlayConversion || away.powerPlayConversion;
  setStatValue(el.homePpg, homePp);
  setStatValue(el.awayPpg, awayPp);
}

async function getNewestEvent(data) {
  if (!data.plays || !data.plays.length) {
    return;
  }

  const newest = data.plays.reduce((acc, current) => (current.sortOrder > acc.sortOrder ? current : acc));

  if (!el.periodTime.textContent || el.periodTime.textContent === '--:--') {
    setClockDisplay(newest.timeRemaining, newest.periodDescriptor?.number || newest.periodNumber, 'Play-by-play');
  }

  if (newest.eventId === lastEventId) {
    return;
  }

  const now = Date.now();
  if (now < playLockUntil && newest.typeDescKey !== 'goal') {
    return;
  }

  lastEventId = newest.eventId;

  if (newest.typeDescKey === 'goal') {
    playLockUntil = now + 45000;
    updateEventLockLabel();
  }

  const handler = eventTypeMapping[newest.typeDescKey];
  if (!handler) {
    return;
  }

  const desc = await handler(newest);
  el.playDesc.textContent = desc;
}

function applyPlayByPlayData(data) {
  if (data.homeTeam?.score !== undefined) {
    el.homeScore.textContent = data.homeTeam.score;
  }
  if (data.awayTeam?.score !== undefined) {
    el.awayScore.textContent = data.awayTeam.score;
  }
  setStatValue(el.homeSOG, data.homeTeam?.sog);
  setStatValue(el.awaySOG, data.awayTeam?.sog);
}

async function runDelayedUpdate(playByPlayData, sequenceAtSchedule) {
  await new Promise((resolve) => setTimeout(resolve, delay));
  if (sequenceAtSchedule !== syncSequence) {
    return;
  }

  applyPlayByPlayData(playByPlayData);
  await getNewestEvent(playByPlayData);
  el.lastUpdated.textContent = `Last refresh ${formatTimestamp()}`;
}

async function fetchEventsAndStats(gameId, isFirst) {
  updateSyncState('Syncing...');
  const sequenceAtSchedule = ++syncSequence;

  try {
    const [playByPlayRes, landingRes] = await Promise.all([
      fetch(`/proxyGame/${gameId}`),
      fetch(`/proxyLanding/${gameId}`)
    ]);

    if (!playByPlayRes.ok) {
      throw new Error(`Play-by-play request failed (${playByPlayRes.status})`);
    }
    if (!landingRes.ok) {
      throw new Error(`Landing request failed (${landingRes.status})`);
    }

    const [playByPlayData, landingData] = await Promise.all([
      playByPlayRes.json(),
      landingRes.json()
    ]);

    if (isFirst && playByPlayData.homeTeam?.logo && playByPlayData.awayTeam?.logo) {
      updateLogos(playByPlayData.homeTeam.logo, playByPlayData.awayTeam.logo);
    }

    applyLandingData(landingData);
    if (playByPlayData.plays) {
      runDelayedUpdate(playByPlayData, sequenceAtSchedule);
    }

    updateSyncState('Live');
  } catch (err) {
    console.error('Event fetch error:', err);
    updateSyncState('Sync error', true);
    el.lastUpdated.textContent = `Last error ${formatTimestamp()}`;
    el.playDesc.textContent = err.message || 'Could not load game data.';
  }
}

function clearPollingLoop() {
  if (eventInterval) {
    clearInterval(eventInterval);
    eventInterval = null;
  }
}

function startPolling(gameId) {
  clearPollingLoop();
  eventInterval = setInterval(() => fetchEventsAndStats(gameId, false), pollIntervalMs);
}

async function refreshCurrentGame(openPanelOnError = false) {
  const parsedGameId = parseGameId(el.gameIdInput.value);
  if (!parsedGameId) {
    updateSyncState('Need game ID', true);
    el.playDesc.textContent = 'Enter a valid game ID or NHL game URL to start syncing.';
    if (openPanelOnError) {
      openSettings();
    }
    return;
  }

  activeGameId = parsedGameId;
  playerCache = {};
  el.gameIdDisplay.textContent = activeGameId;
  saveSettings();
  await fetchEventsAndStats(activeGameId, true);
  startPolling(activeGameId);
}

async function updateGame() {
  applySyncInputs();
  const parsedGameId = parseGameId(el.gameIdInput.value);

  if (!parsedGameId) {
    updateSyncState('Need game ID', true);
    el.playDesc.textContent = 'Enter a valid game ID or NHL game URL to start syncing.';
    return;
  }

  activeGameId = parsedGameId;
  el.gameIdInput.value = parsedGameId;
  el.gameIdDisplay.textContent = parsedGameId;
  lastEventId = null;
  playLockUntil = 0;
  updateEventLockLabel();
  saveSettings();
  await fetchEventsAndStats(parsedGameId, true);
  startPolling(parsedGameId);
  closeSettings();
}

el.updateButton.addEventListener('click', updateGame);
el.gameIdInput.addEventListener('change', saveSettings);
el.delayInput.addEventListener('change', () => {
  applySyncInputs();
  saveSettings();
  if (activeGameId) {
    startPolling(activeGameId);
  }
});
el.refreshInput.addEventListener('change', () => {
  applySyncInputs();
  saveSettings();
  if (activeGameId) {
    startPolling(activeGameId);
  }
});

window.setInterval(updateEventLockLabel, 500);

loadSettings();
updateEventLockLabel();

if (parseGameId(el.gameIdInput.value)) {
  refreshCurrentGame(false);
}
