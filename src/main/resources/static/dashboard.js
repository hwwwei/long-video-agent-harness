const $ = id => document.getElementById(id);
let activeRun = null;
let sampleFile = null;
let polling = null;

function make(tag, className, value) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (value !== undefined) element.textContent = String(value);
  return element;
}

async function request(path, options = {}) {
  const response = await fetch(path, options);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.detail || body.message || `${response.status} ${response.statusText}`);
  return body;
}

async function sha256(blob) {
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
}

function segmentsFromText() {
  const lines = $('transcript').value.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  if (!lines.length) throw new Error('请先填写转写文本。');
  return lines.map((text, index) => ({id: `s${index + 1}`, startMs: index * 5000, endMs: (index + 1) * 5000, text}));
}

async function uploadAndRun(file) {
  if (!file) throw new Error('请选择视频文件，或点击「生成样例视频并运行」。');
  const segments = segmentsFromText();
  $('upload').disabled = true;
  $('sample').disabled = true;
  try {
    $('upload-state').textContent = '创建上传会话…';
    const session = await request('/api/v1/uploads', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({filename: file.name, contentType: file.type || 'video/mp4', sizeBytes: file.size})});
    for (let index = 0; index < session.chunkCount; index++) {
      const chunk = file.slice(index * session.chunkSize, Math.min(file.size, (index + 1) * session.chunkSize));
      await request(`/api/v1/uploads/${session.uploadId}/chunks/${index}`, {method: 'PUT', headers: {'X-Chunk-SHA256': await sha256(chunk)}, body: chunk});
      $('progress').firstElementChild.style.width = `${Math.round((index + 1) * 100 / session.chunkCount)}%`;
      $('upload-state').textContent = `已上传 ${index + 1} / ${session.chunkCount} 个分片`;
    }
    $('upload-state').textContent = '计算整文件 SHA-256 并完成上传…';
    const complete = await request(`/api/v1/uploads/${session.uploadId}/complete`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({sha256: await sha256(file)})});
    await request(`/api/v1/videos/${complete.videoId}/transcript`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({segments})});
    const run = await request('/api/v1/runs', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({videoId: complete.videoId})});
    $('upload-state').textContent = `上传完成 · 模拟转写已绑定 · Run ${run.runId}`;
    await selectRun(run.runId);
    await loadRuns();
  } finally {
    $('upload').disabled = false;
    $('sample').disabled = false;
  }
}

async function generateSampleVideo() {
  if (!window.MediaRecorder || !HTMLCanvasElement.prototype.captureStream) throw new Error('当前浏览器不支持生成样例 WebM；请手动选择视频。');
  const canvas = document.createElement('canvas');
  canvas.width = 320; canvas.height = 180;
  const context = canvas.getContext('2d');
  const stream = canvas.captureStream(8);
  const audioContext = new AudioContext();
  const destination = audioContext.createMediaStreamDestination();
  const oscillator = audioContext.createOscillator();
  oscillator.frequency.value = 220;
  oscillator.connect(destination);
  destination.stream.getAudioTracks().forEach(track => stream.addTrack(track));
  oscillator.start();
  const chunks = [];
  const recorder = new MediaRecorder(stream, {mimeType: MediaRecorder.isTypeSupported('video/webm;codecs=vp8') ? 'video/webm;codecs=vp8' : 'video/webm'});
  recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data); };
  const finished = new Promise(resolve => { recorder.onstop = resolve; });
  recorder.start();
  for (let frame = 0; frame < 14; frame++) {
    context.fillStyle = frame < 5 ? '#125b52' : frame < 10 ? '#267ea4' : '#a86445';
    context.fillRect(0, 0, 320, 180);
    context.fillStyle = '#ffffff';
    context.font = 'bold 25px sans-serif';
    context.fillText(`Agent Harness ${frame + 1}`, 24, 98);
    await new Promise(resolve => setTimeout(resolve, 120));
  }
  recorder.stop();
  await finished;
  oscillator.stop();
  await audioContext.close();
  stream.getTracks().forEach(track => track.stop());
  sampleFile = new File(chunks, 'agent-harness-demo.webm', {type: 'video/webm'});
  $('transcript').value = '今天介绍长视频内容理解系统的设计。\n上传服务使用分片和 SHA-256 校验，完成后由 FFmpeg 提取音频。\nAgent 从提供的转写文本提取事实，Critic 对照原文进行盲审。';
  return sampleFile;
}

function renderRun(run) {
  $('run-id').textContent = run.id;
  $('run-status').textContent = run.status;
  $('run-status').className = `status ${run.status}`;
  const budget = run.budget;
  $('budget').textContent = `调用 ${budget.usedCalls}/${budget.maxCalls} · Token ${budget.usedTokens}/${budget.maxTokens} · 时间 ${budget.usedMillis}/${budget.maxMillis} ms · 版本 ${run.version}`;
  const dag = $('dag'); dag.replaceChildren(); dag.classList.remove('empty');
  for (const node of run.nodes) {
    const button = make('button', `node ${node.status}`);
    button.append(make('b', '', node.node), make('small', '', `${node.status} · attempt ${node.attempt} · ${node.durationMs} ms`));
    button.addEventListener('click', () => { $('node-detail').textContent = `${node.node}: ${node.status}; attempt ${node.attempt}; ${node.durationMs} ms${node.error ? '; ' + node.error : ''}`; });
    dag.append(button);
  }
  const report = run.report;
  $('summary').textContent = report?.summary || 'Run 完成后显示摘要。';
  $('facts').replaceChildren();
  for (const fact of report?.facts || []) {
    const card = make('div', 'fact');
    card.append(make('strong', '', `${fact.segmentId} · ${fact.startMs}–${fact.endMs} ms`), make('p', '', fact.claim), make('small', '', `证据：${fact.evidence}`));
    $('facts').append(card);
  }
  $('critic').replaceChildren();
  for (const item of report?.critic || []) {
    const card = make('div', 'review');
    card.append(make('strong', '', item.decision), make('p', '', item.reason));
    $('critic').append(card);
  }
  $('trace').replaceChildren();
  for (const event of run.trace || []) {
    const card = make('div', 'event');
    card.append(make('time', '', event.at.substring(11, 19)));
    const content = make('div');
    content.append(make('strong', '', `${event.type}${event.node ? ' · ' + event.node : ''}`), make('small', '', JSON.stringify(event.payload)));
    card.append(content); $('trace').append(card);
  }
}

async function selectRun(id) {
  activeRun = id;
  renderRun(await request(`/api/v1/runs/${id}`));
  if (polling) clearInterval(polling);
  polling = setInterval(async () => {
    try {
      const run = await request(`/api/v1/runs/${activeRun}`);
      renderRun(run);
      if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(run.status)) {
        clearInterval(polling); polling = null; await loadRuns();
      }
    } catch (error) { $('upload-state').textContent = error.message; }
  }, 1500);
}

async function loadRuns() {
  const runs = await request('/api/v1/runs'); $('runs').replaceChildren();
  for (const run of runs) {
    const row = make('div', 'run-item');
    row.append(make('span', '', run.id.slice(0, 8) + ' · ' + run.version), make('b', '', run.status));
    row.addEventListener('click', () => selectRun(run.id)); $('runs').append(row);
  }
  return runs;
}

function card(label, value) {
  const element = make('div', 'metric'); element.append(make('b', '', value), make('span', '', label)); return element;
}

function showMetrics(data) {
  const target = $('metrics'); target.replaceChildren();
  for (const split of ['validation', 'holdout']) {
    const result = data[split]; if (!result) continue;
    const name = split === 'validation' ? 'Validation' : 'Holdout';
    target.append(card(`${name} · 事实一致性`, `${(result.factConsistency * 100).toFixed(1)}%`));
    target.append(card(`${name} · 关键信息召回`, `${(result.keyInformationRecall * 100).toFixed(1)}%`));
    target.append(card(`${name} · 摘要完整性`, `${(result.summaryCompleteness * 100).toFixed(1)}%`));
    target.append(card(`${name} · 任务成功率`, `${(result.taskSuccessRate * 100).toFixed(1)}%`));
  }
}

async function loadVersions() {
  const versions = await request('/api/v1/evolution/versions'); $('versions').replaceChildren();
  for (const version of versions) {
    const row = make('div', 'version-item');
    row.append(make('span', '', `${version.version} · maxFacts=${version.candidate.maxFacts}`), make('b', '', `${version.status} / ${version.gateDecision}`));
    $('versions').append(row);
  }
}

async function action(operation) {
  try { await operation(); }
  catch (error) { $('upload-state').textContent = `错误：${error.message}`; }
}

$('upload').addEventListener('click', () => action(() => uploadAndRun($('file').files[0] || sampleFile)));
$('sample').addEventListener('click', () => action(async () => uploadAndRun(await generateSampleVideo())));
$('refresh').addEventListener('click', () => action(loadRuns));
$('benchmark').addEventListener('click', () => action(async () => showMetrics(await request('/api/v1/benchmark', {method: 'POST'}))));
$('propose').addEventListener('click', () => action(async () => { const result = await request('/api/v1/evolution/candidates', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({maxFacts: 3})}); $('upload-state').textContent = `候选 ${result.version}: ${result.gateDecision}`; await loadVersions(); }));
$('rollback').addEventListener('click', () => action(async () => { const result = await request('/api/v1/evolution/rollback', {method: 'POST'}); $('upload-state').textContent = `已恢复版本 ${result.activeVersion}`; await loadVersions(); }));
action(async () => {
  const runs = await loadRuns();
  if (runs.length) await selectRun(runs[0].id);
  await loadVersions();
});
