// ---- Globals & Caches ----
let lastEventId = null;
let playerCache = {};
let playByPlayTimeout;
let delay = 0;

// ---- DOM Elements ----
const el = {
  settingsPanel:    document.getElementById('settings'),
  settingsButton:   document.getElementById('settings-button'),
  updateButton:     document.getElementById('update-button'),
  closeButton:      document.getElementById('close-button'),
  gameIdInput:      document.getElementById('game-id-input'),
  delayInput:       document.getElementById('gamedelay'),
  homeImage:        document.getElementById('home-image'),
  awayImage:        document.getElementById('away-image'),
  homeScore:        document.getElementById('home-score'),
  awayScore:        document.getElementById('away-score'),
  homeSOG:          document.getElementById('homeSOG'),
  awaySOG:          document.getElementById('awaySOG'),
  periodTime:       document.getElementById('period-time'),
  headshots:        document.getElementById('headshots-container'),
  playDesc:         document.getElementById('play-description'),
  panthersVideo:    document.getElementById('panthers-video'),
  lightningVideo:   document.getElementById('lightning-video'),
  scoreboard:       document.getElementById('scoreboard'),
  playByPlayDiv:    document.getElementById('play-by-play')
};

// ---- Settings Panel ----
el.settingsButton.addEventListener('click',  () => el.settingsPanel.style.display = 'flex');
el.closeButton.addEventListener('click',   () => el.settingsPanel.style.display = 'none');
el.updateButton.addEventListener('click', () => {
  delay = parseInt(el.delayInput.value, 10) || 0;
  el.settingsPanel.style.display = 'none';
});

let playLockUntil = 0;

// ---- Logo & Gradient Processing ----
function updateLogos(homeUrl, awayUrl) {
  // Home
  fetch(`/convertSvgToPng?svgUrl=${encodeURIComponent(homeUrl)}`)
    .then(r => r.blob()).then(blob => {
      const url = URL.createObjectURL(blob);
      el.homeImage.onload = () =>
        pickColors(el.homeImage, (p,s,t) => {
          document.getElementById('left-half').style.background =
            `linear-gradient(to bottom right, ${s} 25%, transparent 40%, ${t} 45%, transparent 50%),` +
            `linear-gradient(to right, ${p} 0%, ${s} 50%)`;
        });
      el.homeImage.src = url;
    });
  // Away
  fetch(`/convertSvgToPng?svgUrl=${encodeURIComponent(awayUrl)}`)
    .then(r => r.blob()).then(blob => {
      const url = URL.createObjectURL(blob);
      el.awayImage.onload = () =>
        pickColors(el.awayImage, (p,s,t) => {
          document.getElementById('right-half').style.background =
            `linear-gradient(to bottom right, ${s} 25%, transparent 40%, ${t} 45%, transparent 50%),` +
            `linear-gradient(to left, ${p} 0%, ${s} 50%)`;
        });
      el.awayImage.src = url;
    });
}

function pickColors(img, cb) {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  canvas.width = img.width; canvas.height = img.height;
  ctx.drawImage(img, 0, 0);
  const data = ctx.getImageData(0,0,img.width,img.height).data;
  const pixels = [];
  for (let i=0; i<data.length; i+=4) {
    const [r,g,b,a] = [data[i], data[i+1], data[i+2], data[i+3]];
    if ((r===255&&g===255&&b===255) || a===0) continue;
    pixels.push([r,g,b]);
  }
  const centroids = kMeans(pixels, 3);
  cb(
    `rgb(${centroids[0].join(',')})`,
    `rgb(${centroids[1].join(',')})`,
    `rgb(${centroids[2].join(',')})`
  );
}

// ---- K‑Means Helpers ----
function kMeans(data, k) {
  let centroids = initCentroids(data, k),
      old;
  do {
    old = centroids.map(c=>c.slice());
    const clusters = Array.from({length:k}, ()=>[]);
    data.forEach(p => {
      clusters[nearest(p,centroids)].push(p);
    });
    clusters.forEach((cl,i) => {
      if (cl.length)
        centroids[i] = meanColor(cl);
    });
  } while (!equalCentroids(centroids, old));
  return centroids;
}
function initCentroids(data,k) {
  const c=[], used=new Set();
  while(c.length<k) {
    const i=Math.floor(Math.random()*data.length);
    if(!used.has(i)){ used.add(i); c.push(data[i]); }
  }
  return c;
}
function nearest(pt, cents) {
  let min=Infinity, idx=0;
  cents.forEach((c,i)=>{
    const d = Math.hypot(pt[0]-c[0],pt[1]-c[1],pt[2]-c[2]);
    if(d<min){min=d;idx=i;}
  });
  return idx;
}
function meanColor(cluster) {
  const sum=[0,0,0];
  cluster.forEach(p=>{sum[0]+=p[0];sum[1]+=p[1];sum[2]+=p[2];});
  return sum.map(s=>Math.floor(s/cluster.length));
}
function equalCentroids(a,b) {
  if(a.length!==b.length) return false;
  return a.every((c,i)=>c.every((v,j)=>v===b[i][j]));
}

// ---- Video Handlers ----
const sock = new WebSocket('ws://localhost:8080/client');
sock.onopen    = () => console.log('WS open');
sock.onmessage = e => {
  if (e.data==='spressed') playVideo(el.panthersVideo);
  if (e.data==='lpressed') playVideo(el.lightningVideo);
  if (e.data==='all_stop')  stopAllVideos();
};

function playVideo(v) {
  v.style.display='block';
  el.scoreboard.style.display='none';
  el.playByPlayDiv.style.display='none';
  v.play();
  if(playByPlayTimeout) clearTimeout(playByPlayTimeout);
  playByPlayTimeout = setTimeout(() => {
    el.playByPlayDiv.style.display='flex';
  }, 10000);
  v.onended = ()=>v.play();
}
function stopAllVideos() {
  [el.panthersVideo, el.lightningVideo].forEach(v => {
    v.pause();
    v.style.display='none';
  });
  el.scoreboard.style.display='flex';
  el.playByPlayDiv.style.display='flex';
}

// ---- Player Fetch & Play Descriptions ----
async function fetchPlayerInfo(id) {
  if (playerCache[id]) return playerCache[id];
  const res = await fetch(`http://localhost:4567/proxy/${id}`);
  const d   = await res.json();
  return playerCache[id] = {
    firstName:    d.firstName.default,
    lastName:     d.lastName.default,
    sweaterNumber:d.sweaterNumber,
    headshot:     d.headshot,
    heroImage:    d.heroImage
  };
}

async function generateGoalDescription(play) {
  const s  = await fetchPlayerInfo(play.details.scoringPlayerId);
  const a1 = play.details.assist1PlayerId ? await fetchPlayerInfo(play.details.assist1PlayerId) : null;
  const a2 = play.details.assist2PlayerId ? await fetchPlayerInfo(play.details.assist2PlayerId) : null;
  const g  = await fetchPlayerInfo(play.details.goalieInNetId);

  let desc = `Goal scored by #${s.sweaterNumber} ${s.firstName} ${s.lastName}`;
  if (a1) desc += `, assisted by #${a1.sweaterNumber} ${a1.firstName} ${a1.lastName}`;
  if (a2) desc += ` and #${a2.sweaterNumber} ${a2.firstName} ${a2.lastName}`;
  desc += ` on #${g.sweaterNumber} ${g.firstName} ${g.lastName}.`;

  el.headshots.innerHTML = `
    <img src="${s.headshot}" alt="${s.lastName}">
    <img src="${s.heroImage}" alt="${s.lastName}">
    ${a1? `<img src="${a1.headshot}"><img src="${a1.heroImage}">` : ''}
    ${a2? `<img src="${a2.headshot}"><img src="${a2.heroImage}">` : ''}
    <img src="${g.headshot}"><img src="${g.heroImage}">
  `;
  return desc;
}

async function generateBlockedShotDescription(play) {
  const b = await fetchPlayerInfo(play.details.blockingPlayerId);
  const s = await fetchPlayerInfo(play.details.shootingPlayerId);
  el.headshots.innerHTML = `
    <img src="${s.headshot}"><img src="${b.headshot}">
  `;
  return `Shot blocked by #${b.sweaterNumber} ${b.firstName} ${b.lastName} on #${s.sweaterNumber} ${s.firstName} ${s.lastName}.`;
}

async function generateFaceoffDescription(play) {
  const w = await fetchPlayerInfo(play.details.winningPlayerId);
  const l = await fetchPlayerInfo(play.details.losingPlayerId);
  el.headshots.innerHTML = `
    <img src="${w.headshot}"><img src="${l.headshot}">
  `;
  return `Faceoff won by #${w.sweaterNumber} ${w.firstName} ${w.lastName} against #${l.sweaterNumber} ${l.firstName} ${l.lastName}.`;
}

async function generateTakeawayDescription(play) {
  const p = await fetchPlayerInfo(play.details.playerId);
  el.headshots.innerHTML = `<img src="${p.headshot}">`;
  return `Takeaway by #${p.sweaterNumber} ${p.firstName} ${p.lastName}.`;
}

async function generateGiveawayDescription(play) {
  const p = await fetchPlayerInfo(play.details.playerId);
  el.headshots.innerHTML = `<img src="${p.headshot}">`;
  return `Giveaway by #${p.sweaterNumber} ${p.firstName} ${p.lastName}.`;
}

async function generateShotOnGoalDescription(play) {
  const p = await fetchPlayerInfo(play.details.shootingPlayerId);
  el.headshots.innerHTML = `<img src="${p.headshot}">`;
  return `${play.details.shotType} shot by #${p.sweaterNumber} ${p.firstName} ${p.lastName}.`;
}

async function generateMissedShotDescription(play) {
  const p = await fetchPlayerInfo(play.details.shootingPlayerId);
  el.headshots.innerHTML = `<img src="${p.headshot}">`;
  return `${play.details.shotType} shot by #${p.sweaterNumber} ${p.firstName} ${p.lastName} missed.`;
}

async function generateHitDescription(play) {
  const h = await fetchPlayerInfo(play.details.hittingPlayerId);
  const t = await fetchPlayerInfo(play.details.hitteePlayerId);
  el.headshots.innerHTML = `
    <img src="${h.headshot}"><img src="${t.headshot}">
  `;
  return `#${h.sweaterNumber} ${h.firstName} ${h.lastName} hit on #${t.sweaterNumber} ${t.firstName} ${t.lastName}.`;
}

async function generatePenaltyDescription(play) {
  const p = await fetchPlayerInfo(play.details.committedByPlayerId);
  el.headshots.innerHTML = `<img src="${p.headshot}">`;
  return `Penalty on #${p.sweaterNumber} ${p.firstName} ${p.lastName} for ${play.details.descKey}.`;
}

async function generateStoppageDescription(play) {
  return `Play has been stopped.`;
}

const eventTypeMapping = {
  "goal":          generateGoalDescription,
  "faceoff":       generateFaceoffDescription,
  "penalty":       generatePenaltyDescription,
  "stoppage"       generateStoppageDescription
};

async function getNewestEvent(data) {
  if (!data.plays || !data.plays.length) return;

  // pick the latest play
  const newest = data.plays.reduce((a, c) =>
    c.sortOrder > a.sortOrder ? c : a
  );

  // update the clock from the play’s timeRemaining
  el.periodTime.innerText = `Time Left: ${newest.timeRemaining || '--:--'}`;

  // if we’ve already shown this event, do nothing
  if (newest.eventId === lastEventId) return;

  const now = Date.now();

  // if still within goal‑lock period, and this play isn't another goal, skip it
  if (now < playLockUntil && newest.typeDescKey !== 'goal') {
    return;
  }

  // commit to this event
  lastEventId = newest.eventId;

  // if it’s a goal, lock out other events for 45 seconds
  if (newest.typeDescKey === 'goal') {
    playLockUntil = now + 45_000;  // 45 000ms = 45s
  }

  // clear out old headshots
  el.headshots.innerHTML = '';

  // generate and display the description
  const handler = eventTypeMapping[newest.typeDescKey];
  if (!handler) return;
  const desc = await handler(newest);
  el.playDesc.innerText = desc;
}

function delayedUpdate(data) {
  console.log(`Delaying full update by ${delay}ms`);
  setTimeout(() => {
    getNewestEvent(data);
    el.homeSOG.innerText  = `Home Shots: ${data.homeTeam.sog}`;
    el.awaySOG.innerText  = `Away Shots: ${data.awayTeam.sog}`;
    el.homeScore.innerText = data.homeTeam.score;
    el.awayScore.innerText = data.awayTeam.score;
  }, delay);
}

function fetchGameData() {
  const id = el.gameIdInput.value;
  fetch(`http://localhost:4567/proxyGame/${id}`)
    .then(r => r.json())
    .then(data => { if (data && data.plays) delayedUpdate(data); })
    .catch(e => console.error('Fetch error:', e));
}

// ---- Logo Update on “Update” click ----
el.updateButton.addEventListener('click', updateGame);

// ---- Called when “Update” is clicked ----
async function updateGame() {
  // read settings
  delay     = parseInt(el.delayInput.value, 10) || 0;
  const gameID = el.gameIdInput.value;

  // clear any old loops
  clearInterval(clockInterval);
  clearInterval(eventInterval);


  await fetchEventsAndStats(gameID, true);

  eventInterval = setInterval(() => fetchEventsAndStats(gameID, false), 5000);  // every 5s

  el.settingsPanel.style.display = 'none';
}


// ---- Fetch play‑by‑play & scores/SOG, and on first call update logos ----
async function fetchEventsAndStats(gameID, isFirst) {
  try {
    // play‑by‑play + score + SOG
    const res1  = await fetch(`/proxyGame/${gameID}`);
    const data1 = await res1.json();

    // first time only: kick off logo gradients
    if (isFirst && data1.homeTeam && data1.awayTeam) {
      updateLogos(data1.homeTeam.logo, data1.awayTeam.logo);
    }

    // any time: handle plays + scoring
    if (data1.plays) {
      delayedUpdate(data1);
    }
  } catch (err) {
    console.error('Event fetch error:', err);
  }
}

// Make sure you’ve declared these up top:
let clockInterval = null;
let eventInterval = null;
