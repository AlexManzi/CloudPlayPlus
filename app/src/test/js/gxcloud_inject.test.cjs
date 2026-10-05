// Run with: node --test app/src/test/js/gxcloud_inject.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { runInNewContext } = require('node:vm');
const source = readFileSync(resolve(__dirname, '../../main/res/raw/gxcloud_inject.js'), 'utf8');

// A small browser harness exercises the injected script's public entry points.
// Mutation delivery is deferred, as in the browser, so reparenting is atomic.
function browser(mode = 'off', { attached = true, webgl = true, fetch, preferIpv6, RTCPeerConnection } = {}) {
    const observers = new Set();
    const pending = new Set();
    const timers = new Map();
    const raf = new Map();
    const vfc = new Map();
    const draws = [];
    let id = 0;
    let lostContexts = 0;
    function mutation(target) {
        for (const observer of observers) {
            for (const [node, options] of observer.targets) {
                if (node === target || (options.subtree && node.contains(target))) pending.add(observer);
            }
        }
    }
    class Element {
        constructor(tagName) {
            this.tagName = tagName;
            this.children = [];
            this.parentNode = null;
            this.dataset = {};
            this.style = {};
            this.listeners = new Map();
            this.width = 300;
            this.height = 150;
        }
        get parentElement() { return this.parentNode; }
        appendChild(child) {
            child.remove();
            this.children.push(child);
            child.parentNode = this;
            mutation(this);
            return child;
        }
        remove() {
            if (!this.parentNode) return;
            const parent = this.parentNode;
            parent.children.splice(parent.children.indexOf(this), 1);
            this.parentNode = null;
            mutation(parent);
        }
        contains(node) { return this === node || this.children.some(child => child.contains(node)); }
        addEventListener(type, listener) {
            if (!this.listeners.has(type)) this.listeners.set(type, new Set());
            this.listeners.get(type).add(listener);
        }
        removeEventListener(type, listener) { this.listeners.get(type)?.delete(listener); }
        emit(type) {
            const event = { target: this, preventDefault() {} };
            for (const listener of [...(this.listeners.get(type) ?? [])]) listener(event);
        }
        closest() { return this.parentNode; }
        getContext(type) {
            if (type === '2d') return { drawImage: () => draws.push('bridge') };
            if (!webgl) return null;
            return new Proxy({}, {
                get: (_, key) => {
                    if (/^[A-Z_0-9]+$/.test(key)) return key;
                    if (key === 'getShaderParameter' || key === 'getProgramParameter') return () => true;
                    if (key === 'getExtension') return () => ({ loseContext() { lostContexts++; } });
                    if (key === 'drawArrays') return () => draws.push('draw');
                    if (key === 'texImage2D') return (...args) => {
                        if (args.length === 6) draws.push('upload');
                    };
                    return () => ({});
                }
            });
        }
    }
    class Video extends Element {
        constructor() {
            super('VIDEO');
            this.src = '';
            this.srcObject = {};
            this.className = '';
            this.videoWidth = 1920;
            this.videoHeight = 1080;
            this.readyState = 2;
            this.paused = false;
            this.ended = false;
        }
        play() { this.paused = false; return Promise.resolve(); }
        requestVideoFrameCallback(callback) { vfc.set(++id, callback); return id; }
        cancelVideoFrameCallback(handle) { vfc.delete(handle); }
    }
    class Observer {
        constructor(callback) { this.callback = callback; this.targets = new Map(); observers.add(this); }
        observe(node, options) { this.targets.set(node, options); }
        disconnect() { this.targets.clear(); pending.delete(this); }
    }
    const document = new Element('#DOCUMENT');
    document.documentElement = document.appendChild(new Element('HTML'));
    document.head = document.documentElement.appendChild(new Element('HEAD'));
    document.body = document.documentElement.appendChild(new Element('BODY'));
    document.hidden = false;
    document.createElement = tag => new Element(tag.toUpperCase());
    const menu = document.body.appendChild(new Element('DIV'));
    const toggle = menu.appendChild(new Element('BUTTON'));
    document.querySelector = () => toggle;
    document.querySelectorAll = tag => {
        const found = [];
        function visit(node) {
            if (node.tagName === tag.toUpperCase()) found.push(node);
            node.children.forEach(visit);
        }
        visit(document);
        return found;
    };
    const streamContainer = new Element('DIV');
    streamContainer.dataset.testid = 'media-container';
    const video = streamContainer.appendChild(new Video());
    if (attached) document.body.appendChild(streamContainer);
    const window = { __gxcloudInitialCasMode: mode, __gxcloudInitialPreferIpv6: preferIpv6, fetch, RTCPeerConnection };
    runInNewContext(source, {
        window, document, HTMLMediaElement: Video, HTMLVideoElement: Video,
        MutationObserver: Observer,
        requestAnimationFrame: callback => { raf.set(++id, callback); return id; },
        cancelAnimationFrame: handle => raf.delete(handle),
        setTimeout: (callback, delay) => { timers.set(++id, { callback, delay }); return id; },
        clearTimeout: handle => timers.delete(handle)
    });
    function flush() {
        while (pending.size) {
            const observer = pending.values().next().value;
            pending.delete(observer);
            if (observer.targets.size) observer.callback([]);
        }
    }
    return {
        window, document, video, streamContainer, Element, observers, timers, raf, vfc, draws, flush,
        get lostContexts() { return lostContexts; },
        canvases: () => document.querySelectorAll('canvas'),
        fireTimer(delay) {
            const entry = [...timers].find(([, timer]) => timer.delay === delay);
            assert.ok(entry, `missing ${delay}ms timer`);
            timers.delete(entry[0]);
            entry[1].callback();
        },
        frame() {
            const [handle, callback] = vfc.entries().next().value;
            vfc.delete(handle);
            callback();
        }
    };
}

test('initial Off has no canvas, render loop, or liveness timer', () => {
    const b = browser();
    assert.equal(b.window.__gxcloudGetStreamStats().casMode, 'off');
    assert.equal(b.canvases().length, 0);
    assert.equal(b.raf.size, 0);
    assert.equal(b.vfc.size, 0);
    assert.equal(b.timers.size, 0);
    assert.equal(b.video.listeners.get('ended').size, 1);
    assert.equal(b.video.listeners.get('emptied').size, 1);
    for (const observer of b.observers) {
        for (const options of observer.targets.values()) assert.equal(options.subtree, undefined);
    }
});

for (const event of ['ended', 'error', 'emptied']) {
    test(`Off cleans up on element-bound ${event} after detachment`, () => {
        const b = browser();
        b.streamContainer.remove();
        b.video.emit(event);
        b.flush();
        assert.equal(b.window.__gxcloudGetStreamStats().state, 'No active stream');
        assert.equal(b.video.dataset.gxBound, undefined);
        assert.equal(b.video._gxMenuCleanup, undefined);
        assert.equal(b.video._gxBindingCleanup, undefined);
        for (const observer of b.observers) assert.equal(observer.targets.size, 0);
    });
}

test('Off detects silent ancestor removal and permits reuse of the same video', () => {
    const b = browser();
    b.streamContainer.remove();
    b.flush();
    assert.equal(b.window.__gxcloudGetStreamStats().state, 'No active stream');
    b.document.body.appendChild(b.streamContainer);
    b.video.play();
    b.flush();
    assert.equal(b.video.dataset.gxBound, 'true');
    assert.equal(b.video.listeners.get('ended').size, 1);
});

test('Off keeps a reparented stream and then observes its new ancestors', () => {
    const b = browser();
    const parent = b.document.body.appendChild(new b.Element('DIV'));
    parent.appendChild(b.streamContainer);
    b.flush();
    assert.equal(b.video.dataset.gxBound, 'true');
    parent.remove();
    b.flush();
    assert.equal(b.window.__gxcloudGetStreamStats().state, 'No active stream');
});

test('detached play acquisition binds on attachment and expires if never attached', () => {
    const b = browser('off', { attached: false });
    b.video.play();
    assert.equal(b.timers.size, 1);
    b.document.body.appendChild(b.streamContainer);
    b.flush();
    assert.equal(b.timers.size, 0);
    assert.equal(b.video.dataset.gxBound, 'true');
    b.streamContainer.remove();
    b.flush();
    assert.equal(b.window.__gxcloudGetStreamStats().state, 'No active stream');

    const abandoned = browser('off', { attached: false });
    abandoned.video.play();
    abandoned.fireTimer(3000);
    assert.equal(abandoned.video.dataset.gxBound, undefined);
    abandoned.document.body.appendChild(abandoned.streamContainer);
    abandoned.video.play();
    abandoned.flush();
    assert.equal(abandoned.video.dataset.gxBound, 'true');
});

test('mode switches preserve binding, one canvas, and the measured draw cadence', () => {
    const b = browser();
    for (const mode of ['normal', 'high', 'off', 'normal', 'off']) {
        b.window.__gxcloudSetCasMode(mode);
        assert.equal(b.video.listeners.get('ended').size, 1);
        assert.equal(b.canvases().length, mode === 'off' ? 0 : 1);
        assert.equal(b.raf.size, mode === 'off' ? 0 : 1);
        assert.equal(b.vfc.size, mode === 'off' ? 0 : 1);
        assert.equal(b.timers.size, 0);
        if (mode !== 'off') {
            const start = b.draws.length;
            b.frame();
            assert.deepEqual(b.draws.slice(start), ['bridge', 'upload', 'draw']);
        }
    }
    assert.equal(b.lostContexts, 3);
    b.video.emit('ended');
    assert.equal(b.window.__gxcloudGetStreamStats().state, 'No active stream');
});

test('context loss retains binding cleanup and falls back to scoped removal detection', () => {
    const b = browser('normal');
    b.canvases()[0].emit('webglcontextlost');
    assert.equal(b.video.style.visibility, '');
    assert.equal(b.raf.size, 0);
    assert.equal(b.timers.size, 0);
    assert.equal(b.video.listeners.get('ended').size, 1);
    b.streamContainer.remove();
    b.flush();
    assert.equal(b.window.__gxcloudGetStreamStats().state, 'No active stream');
});

test('failed WebGL setup retains independent cleanup without a render loop', () => {
    const b = browser('high', { webgl: false });
    assert.equal(b.canvases().length, 0);
    assert.equal(b.video.style.visibility, '');
    assert.equal(b.raf.size, 0);
    b.streamContainer.remove();
    b.flush();
    assert.equal(b.window.__gxcloudGetStreamStats().state, 'No active stream');
});

test('fresh documents retain supplied modes and repeated injection does not duplicate bindings', () => {
    for (const mode of ['off', 'normal', 'high']) {
        const b = browser(mode);
        assert.equal(b.window.__gxcloudGetStreamStats().casMode, mode);
        // Reinjection returns before touching the environment or the current binding.
        runInNewContext(source, { window: b.window });
        assert.equal(b.video.listeners.get('ended').size, 1);
        assert.equal(b.canvases().length, mode === 'off' ? 0 : 1);
    }
});

test('CAS pause keeps the backoff watchdog, resumes cleanly, and tears down a stopped stream', () => {
    const b = browser('normal');
    b.video.paused = true;
    b.video.emit('pause');
    assert.equal(b.raf.size, 0);
    assert.equal(b.vfc.size, 0);
    for (const delay of [500, 1000, 2000, 4000, 5000]) b.fireTimer(delay);
    assert.equal([...b.timers.values()][0].delay, 5000);
    b.video.paused = false;
    b.video.emit('play');
    assert.equal(b.timers.size, 0);
    assert.equal(b.raf.size, 1);
    b.video.paused = true;
    b.video.emit('pause');
    assert.equal([...b.timers.values()][0].delay, 500);
    b.streamContainer.remove();
    b.fireTimer(500);
    assert.equal(b.canvases().length, 0);
    assert.equal(b.timers.size, 0);
    assert.equal(b.window.__gxcloudGetStreamStats().state, 'No active stream');
});

test('a stale terminal callback cannot retire a replacement binding on the same element', () => {
    const b = browser('normal');
    const oldEnded = [...b.video.listeners.get('ended')][0];
    b.video.emit('ended');
    b.video.play();
    assert.equal(b.canvases().length, 1);
    oldEnded();
    assert.equal(b.video.dataset.gxBound, 'true');
    assert.equal(b.canvases().length, 1);
    assert.equal(b.video.listeners.get('ended').size, 1);
});

// Minimal Response stand-in: clone() reads the same body, as a real clone would.
function iceResponse(candidates, { ok = true } = {}) {
    const text = JSON.stringify({ exchangeResponse: JSON.stringify(candidates) });
    const response = {
        ok,
        url: 'https://example.test/v5/sessions/home/ABC/ice',
        json: () => Promise.resolve(JSON.parse(text)),
        text: () => Promise.resolve(text),
        clone: () => ({ json: () => Promise.resolve(JSON.parse(text)) })
    };
    return response;
}
const serverCandidates = () => [
    { candidate: 'a=candidate:1 1 UDP 2130706431 20.1.2.3 9002 typ host', messageType: 'iceCandidate', sdpMLineIndex: '0', sdpMid: '0' },
    { candidate: 'a=candidate:2 1 UDP 1 2603:1030::5 9002 typ host', messageType: 'iceCandidate', sdpMLineIndex: '0', sdpMid: '0' },
    { candidate: 'a=end-of-candidates', messageType: 'iceCandidate', sdpMLineIndex: '0', sdpMid: '0' }
];
const priorities = async (response) => JSON.parse((await response.json()).exchangeResponse)
    .map(c => c.candidate.split(' ').slice(3, 6).join(' '));

test('Prefer IPv6 ranks every server IPv6 candidate above IPv4 and keeps IPv4 as fallback', async () => {
    const b = browser('off', { preferIpv6: true, fetch: () => Promise.resolve(iceResponse(serverCandidates())) });
    const response = await b.window.fetch('https://example.test/v5/sessions/home/ABC/ice');
    assert.deepEqual(await priorities(response), ['1000 20.1.2.3 9002', '2130706431 2603:1030::5 9002', '']);
    assert.equal(JSON.parse((await response.json()).exchangeResponse)[2].candidate, 'a=end-of-candidates');
    assert.equal(b.window.__gxcloudGetStreamStats().serverIpv6Candidates, 1);
});

test('Prefer IPv6 off, or toggled off at runtime, leaves the ICE exchange untouched', async () => {
    const b = browser('off', { preferIpv6: true, fetch: () => Promise.resolve(iceResponse(serverCandidates())) });
    b.window.__gxcloudSetPreferIpv6(false);
    const response = await b.window.fetch('https://example.test/v5/sessions/cloud/ABC/ice');
    assert.deepEqual(await priorities(response), ['2130706431 20.1.2.3 9002', '1 2603:1030::5 9002', '']);
    // Still observed, so the stats page can say whether IPv6 was on offer.
    assert.equal(b.window.__gxcloudGetStreamStats().serverIpv6Candidates, 1);
});

test('Prefer IPv6 ignores non-ICE requests, POSTs, failures and unparseable bodies', async () => {
    const sent = [];
    const bad = { ok: true, clone: () => ({ json: () => Promise.reject(new Error('204')) }) };
    const b = browser('off', { preferIpv6: true, fetch: (url) => { sent.push(url); return Promise.resolve(url.endsWith('/bad/ice') ? bad : iceResponse(serverCandidates())); } });
    const other = await b.window.fetch('https://example.test/v5/sessions/home/ABC/sdp');
    assert.deepEqual(await priorities(other), ['2130706431 20.1.2.3 9002', '1 2603:1030::5 9002', '']);
    const post = await b.window.fetch('https://example.test/v5/sessions/home/ABC/ice', { method: 'POST' });
    assert.deepEqual(await priorities(post), ['2130706431 20.1.2.3 9002', '1 2603:1030::5 9002', '']);
    assert.equal(await b.window.fetch('https://example.test/v5/sessions/home/bad/ice'), bad);
    assert.equal(sent.length, 3);
});

// Serves a Remote Play configuration (console address) and ICE exchange.
function remotePlayFetch(serverDetails, candidates = serverCandidates()) {
    return (url) => Promise.resolve(url.endsWith('/configuration')
        ? { ok: true, clone: () => ({ json: () => Promise.resolve({ serverDetails }) }) }
        : iceResponse(candidates));
}
const settle = () => new Promise(resolve => setImmediate(resolve));

test('Remote Play adds the console IPv6 address before end-of-candidates, only when on and not already listed', async () => {
    const details = { ipV4Address: '98.1.2.3', ipV4Port: 9002, ipV6Address: '2601:aa::7', ipV6Port: 1234 };
    const on = browser('off', { preferIpv6: true, fetch: remotePlayFetch(details) });
    await on.window.fetch('https://example.test/v5/sessions/home/ABC/configuration');
    await settle();
    const response = await on.window.fetch('https://example.test/v5/sessions/home/ABC/ice');
    assert.deepEqual(await priorities(response), [
        '1000 20.1.2.3 9002', '2130706431 2603:1030::5 9002',
        '2130706430 2601:aa::7 1234', '2130706429 2601:aa::7 9002', ''
    ]);
    assert.equal(on.window.__gxcloudGetStreamStats().serverIpv6Candidates, 3);

    // A cloud session never receives a Remote Play console's address.
    const cloud = await on.window.fetch('https://example.test/v5/sessions/cloud/ABC/ice');
    assert.equal((await priorities(cloud)).length, 3);

    const off = browser('off', { preferIpv6: false, fetch: remotePlayFetch(details) });
    await off.window.fetch('https://example.test/v5/sessions/home/ABC/configuration');
    await settle();
    const untouched = await off.window.fetch('https://example.test/v5/sessions/home/ABC/ice');
    assert.deepEqual(await priorities(untouched), ['2130706431 20.1.2.3 9002', '1 2603:1030::5 9002', '']);
    assert.equal(off.window.__gxcloudGetStreamStats().serverIpv6Candidates, 3);

    const listed = browser('off', { preferIpv6: true, fetch: remotePlayFetch({ ipV6Address: '2603:1030::5', ipV6Port: 9002 }) });
    await listed.window.fetch('https://example.test/v5/sessions/home/ABC/configuration');
    await settle();
    const deduped = await listed.window.fetch('https://example.test/v5/sessions/home/ABC/ice');
    assert.equal((await priorities(deduped)).length, 3);
});

test('stats report the selected pair as IPv4/IPv6 and direct/relay, one poll behind', async () => {
    let report = [];
    class PeerConnection {
        constructor() { this.connectionState = 'connected'; }
        getStats() { return Promise.resolve(new Map(report.map(stat => [stat.id, stat]))); }
    }
    const b = browser('off', { RTCPeerConnection: PeerConnection });
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, null);
    const pc = new b.window.RTCPeerConnection();
    assert.ok(pc instanceof PeerConnection);
    report = [
        { id: 'T', type: 'transport', selectedCandidatePairId: 'P' },
        { id: 'P', type: 'candidate-pair', localCandidateId: 'L', remoteCandidateId: 'R' },
        { id: 'L', type: 'local-candidate', candidateType: 'host' },
        { id: 'R', type: 'remote-candidate', candidateType: 'host', address: '2603:1030::5' }
    ];
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, null);
    await settle();
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, 'IPv6 (direct)');
    report[2].candidateType = 'relay';
    report[3].address = '20.1.2.3';
    await settle();
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, 'IPv4 (relay)');
});

test('selected video ICE pair is current on the first poll and never collects full stats', () => {
    let pair = { local: { type: 'host' }, remote: { type: 'host', address: '2603:1030::5' } };
    let reads = 0;
    const iceTransport = { getSelectedCandidatePair() { reads++; return pair; } };
    class PeerConnection {
        constructor() { this.connectionState = 'connected'; }
        getReceivers() {
            return [
                { track: { kind: 'audio' }, transport: { iceTransport: { getSelectedCandidatePair() { assert.fail('audio path'); } } } },
                { track: { kind: 'video' }, transport: { iceTransport } }
            ];
        }
        getStats() { assert.fail('full stats collection'); }
    }
    const b = browser('off', { RTCPeerConnection: PeerConnection });
    const pc = new b.window.RTCPeerConnection();
    assert.equal(reads, 0); // No background polling.
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, 'IPv6 (direct)');
    pair.local.type = 'relay';
    pair.remote.address = '20.1.2.3';
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, 'IPv4 (relay)');
    pair.local.type = 'host';
    pair.remote.type = 'relay';
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, 'IPv4 (relay)');
    pair = null;
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, null);
    pair = { local: { type: 'host' }, remote: { type: 'host', address: null } };
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, null);
    const beforeClose = reads;
    pc.connectionState = 'closed';
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, null);
    assert.equal(reads, beforeClose);
});

test('selected pair can be read through senders or SCTP when no video transport exists', () => {
    for (const source of ['sender', 'sctp']) {
        const transport = { iceTransport: { getSelectedCandidatePair: () => ({
            local: { type: 'host' }, remote: { type: 'host', address: '20.1.2.3' }
        }) } };
        class PeerConnection {
            constructor() { if (source === 'sctp') this.sctp = { transport }; }
            getReceivers() { return [{ track: { kind: 'video' }, transport: null }]; }
            getSenders() { return source === 'sender' ? [{ transport }] : []; }
            getStats() { assert.fail('full stats collection'); }
        }
        const b = browser('off', { RTCPeerConnection: PeerConnection });
        new b.window.RTCPeerConnection();
        assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, 'IPv4 (direct)');
    }
});

const pathReport = address => new Map([
    ['T', { id: 'T', type: 'transport', selectedCandidatePairId: 'P' }],
    ['P', { id: 'P', type: 'candidate-pair', remoteCandidateId: 'R' }],
    ['R', { id: 'R', type: 'remote-candidate', candidateType: 'host', address }]
]);

test('unavailable or throwing selected-pair API falls back without overlapping requests', async () => {
    for (const api of [undefined, () => { throw new Error('unavailable'); }]) {
        let resolveStats;
        let calls = 0;
        class PeerConnection {
            getReceivers() { return [{ track: { kind: 'video' }, transport: { iceTransport: { getSelectedCandidatePair: api } } }]; }
            getStats() { calls++; return new Promise(resolve => { resolveStats = resolve; }); }
        }
        const b = browser('off', { RTCPeerConnection: PeerConnection });
        new b.window.RTCPeerConnection();
        assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, null);
        b.window.__gxcloudGetStreamStats();
        await settle();
        assert.equal(calls, 1);
        resolveStats(pathReport('2603:1030::5'));
        await settle();
        assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, 'IPv6 (direct)');
        await settle();
        resolveStats(new Map());
        await settle();
    }
});

test('old pending stats neither block nor overwrite a replacement connection', async () => {
    class PeerConnection {
        getStats() { return new Promise(resolve => { this.resolveStats = resolve; }); }
    }
    const b = browser('off', { RTCPeerConnection: PeerConnection });
    const old = new b.window.RTCPeerConnection();
    b.window.__gxcloudGetStreamStats();
    await settle();
    const current = new b.window.RTCPeerConnection();
    b.window.__gxcloudGetStreamStats();
    await settle();
    assert.equal(typeof current.resolveStats, 'function');
    current.resolveStats(pathReport('20.1.2.3'));
    await settle();
    old.resolveStats(pathReport('2603:1030::5'));
    await settle();
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, 'IPv4 (direct)');
    await settle();
    current.resolveStats(new Map());
    await settle();
});

test('closed peers and synchronous or asynchronous stats failures are contained', async () => {
    for (const failure of ['sync', 'async', 'closed']) {
        let calls = 0;
        class PeerConnection {
            constructor() { this.connectionState = failure === 'closed' ? 'closed' : 'connected'; }
            getStats() {
                calls++;
                if (failure === 'sync') throw new Error('stats unavailable');
                return Promise.reject(new Error('stats unavailable'));
            }
        }
        const b = browser('off', { RTCPeerConnection: PeerConnection });
        new b.window.RTCPeerConnection();
        for (let i = 0; i < 2; i++) {
            assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, null);
            await settle();
        }
        assert.equal(calls, failure === 'closed' ? 0 : 2);
    }
});

test('late full stats cannot overwrite a newly available selected-pair result', async () => {
    let transport = null;
    let resolveStats;
    class PeerConnection {
        getReceivers() { return [{ track: { kind: 'video' }, transport }]; }
        getStats() { return new Promise(resolve => { resolveStats = resolve; }); }
    }
    const b = browser('off', { RTCPeerConnection: PeerConnection });
    new b.window.RTCPeerConnection();
    b.window.__gxcloudGetStreamStats();
    await settle();
    transport = { iceTransport: { getSelectedCandidatePair: () => ({
        local: { type: 'host' }, remote: { type: 'host', address: '20.1.2.3' }
    }) } };
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, 'IPv4 (direct)');
    resolveStats(pathReport('2603:1030::5'));
    await settle();
    transport = null;
    assert.equal(b.window.__gxcloudGetStreamStats().connectionPath, 'IPv4 (direct)');
    await settle();
    resolveStats(new Map());
    await settle();
});
