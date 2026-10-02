(function() {
    if (window.__gxcloudInjected) return;
    window.__gxcloudInjected = true;

    document.documentElement.style.overscrollBehavior = 'none';
    document.body.style.overscrollBehavior = 'none';


    const triVerts = new Float32Array([-1,-1,3,-1,-1,3]);
    const EMPTY_PIXEL = new Uint8Array([0,0,0,255]);

    const bridge = document.createElement('canvas');
    const bridgeCtx = bridge.getContext('2d', { alpha: false, willReadFrequently: false });
    bridgeCtx.imageSmoothingEnabled = false;
    bridgeCtx.globalCompositeOperation = 'copy';

    const style = document.createElement('style');
    style.textContent =
        'html, body { -webkit-tap-highlight-color: transparent !important; }' +
        // An xCloud container on the home route is wider than this screen. Mobile
        // Chromium widens the layout viewport to fit it, which drags every
        // fixed-position start-0/end-0 bar (the title bar) wider too and leaves its
        // end-side buttons off-screen behind a horizontal pan. Clipping x-overflow
        // on the root keeps the layout viewport at screen width. Needed on html,
        // not just body — Android Chromium ignores it on body alone.
        'html, body { overflow-x: hidden !important; }' +
        // Shell title-bar pill rings: the site draws them with a two-layer mask +
        // mask-composite:subtract trick, which this WebView renders wrong (invisible
        // or stretched past the button) because it assumes the desktop Chrome/Edge
        // UA it's given implies full mask-* support. The gradient behind it is a
        // single color stop, so an inset box-shadow is pixel-equivalent.
        '[class~="mask-composite-subtract"][class~="rounded-pill"] {' +
        ' -webkit-mask: none !important; mask: none !important;' +
        ' background: none !important;' +
        ' box-shadow: inset 0 0 0 var(--border-solid-padding, 2px) var(--border-solid-color, transparent) !important;' +
        '}';
    document.head.appendChild(style);

    // Returns true once the toggle is hidden (or was already hidden), so callers
    // can stop retrying. Idempotent — cheap to call repeatedly.
    const hideMenuButton = () => {
        const toggle = document.querySelector('button[aria-label="Quick actions toggle" i]');
        if (!toggle) return false;
        const container = toggle.closest('.absolute') ?? toggle.parentElement;
        if (!container) return false;
        if (container.dataset.hidden) return true;
        container.dataset.hidden = 'true';
        container.style.visibility = 'hidden';
        container.addEventListener('mouseenter', () => container.style.visibility = 'visible');
        container.addEventListener('mouseleave', () => container.style.visibility = 'hidden');
        container.addEventListener('touchstart', () => container.style.visibility = 'visible');
        container.addEventListener('touchend', () => setTimeout(() => container.style.visibility = 'hidden', 1000));
        return true;
    };

    // Exactly one stream may own the pipeline at a time. `bridge` is shared by every
    // pipeline and _casCleanup shrinks it to 1x1, so an old pipeline tearing down
    // after a new one binds would leave the new one drawing through a 1x1 bridge —
    // on top of two stacked opaque canvases and two live GL contexts.
    let activeStreamVideo = null;
    // Controlled by the native quick menu. Normal keeps the tuned production
    // shader; High is deliberately compiled with its own constant.
    const initialCasMode = window.__gxcloudInitialCasMode;
    let casMode = initialCasMode === 'off' || initialCasMode === 'high' ? initialCasMode : 'normal';

    // Full unbind: menu watch, CAS pipeline, and the binding marker, so the next
    // stream can bind even if xCloud re-uses the same <video> element.
    const teardown = (video) => {
        if (video._gxBindingCleanup) video._gxBindingCleanup();
        if (video._gxOffCleanup) video._gxOffCleanup();
        if (video._gxMenuCleanup) video._gxMenuCleanup();
        if (video._casCleanup) video._casCleanup();
        delete video.dataset.gxBound;
        if (activeStreamVideo === video) activeStreamVideo = null;
    };

    // CAS Off (and failed/lost GL setup) still owns a stream binding. Watch only
    // direct child removals along its ancestor chain; no steady-state polling and
    // no permanent subtree observer. CAS's existing rAF/watchdog takes over on success.
    const watchWithoutCAS = (video) => {
        if (video._gxOffCleanup || !video._gxBindingCleanup || video._casCleanup) return;
        const binding = video._gxBindingCleanup;
        let attachmentTimer = null;
        let ancestors = [];
        const isCurrent = () => activeStreamVideo === video && video._gxBindingCleanup === binding;
        const cleanup = () => {
            observer.disconnect();
            clearTimeout(attachmentTimer);
            attachmentTimer = null;
            ancestors = [];
            if (video._gxOffCleanup === cleanup) delete video._gxOffCleanup;
        };
        const observeAncestors = () => {
            const next = [];
            for (let parent = video.parentNode; parent; parent = parent.parentNode) next.push(parent);
            if (next.length === ancestors.length && next.every((node, i) => node === ancestors[i])) return;
            observer.disconnect();
            ancestors = next;
            for (const parent of ancestors) observer.observe(parent, { childList: true });
        };
        const observer = new MutationObserver(() => {
            if (!isCurrent()) { cleanup(); return; }
            // While waiting for first attachment, unrelated page mutations are harmless.
            if (attachmentTimer !== null) {
                if (!document.contains(video)) return;
                clearTimeout(attachmentTimer);
                attachmentTimer = null;
                observeAncestors();
            }
            if (!video.srcObject || !document.contains(video) || video.ended) {
                teardown(video);
                return;
            }
            observeAncestors();
        });
        video._gxOffCleanup = cleanup;
        if (document.contains(video)) {
            observeAncestors();
        } else {
            // play() can be called before React attaches the video. Bound this cold
            // watch, then let a later play/metadata/playing event retry acquisition.
            attachmentTimer = setTimeout(() => {
                attachmentTimer = null;
                if (isCurrent()) teardown(video);
                else cleanup();
            }, 3000);
            observer.observe(document.documentElement, { childList: true, subtree: true });
        }
    };

    const setupWebGLCAS = (video) => {
        if (casMode === 'off') return;
        if (video.dataset.casSetup) return;
        video.dataset.casSetup = 'true';

        const canvas = document.createElement('canvas');
        canvas.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;pointer-events:none;contain:strict;';
        document.body.appendChild(canvas);
        video.style.visibility = 'hidden';

        // Unwind a partial setup completely, so the element stays eligible for a
        // later acquisition event instead of being stuck with casSetup set and no
        // pipeline behind it.
        const abortSetup = (glCtx, program, shaders) => {
            if (glCtx) {
                if (shaders) for (const s of shaders) if (s) glCtx.deleteShader(s);
                if (program) glCtx.deleteProgram(program);
                glCtx.getExtension('WEBGL_lose_context')?.loseContext();
            }
            canvas.remove();
            video.style.visibility = '';
            delete video.dataset.casSetup;
        };

        const gl = canvas.getContext('webgl2', { powerPreference: 'low-power', alpha: false, depth: false, stencil: false, preserveDrawingBuffer: false, antialias: false, desynchronized: true, premultipliedAlpha: false });
        if (!gl) {
            abortSetup(null, null, null);
            return;
        }

        const vert = '#version 300 es\nin vec4 position;\nout vec2 vUV;\nvoid main(){gl_Position=position;vUV=vec2(position.x*0.5+0.5,0.5-position.y*0.5);}';
        // %SHARPEN_FACTOR% is substituted below, not string-replaced against a tuned
        // literal — a future retune of the normal-mode constant can't silently break
        // high mode by no longer matching the old text.
        const fragTemplate = '#version 300 es\nprecision mediump float;\nuniform sampler2D data;\nin vec2 vUV;\nconst float sharpenFactor=%SHARPEN_FACTOR%;\nout vec4 fragColor;\nvoid main(){\n  vec3 e=texture(data,vUV).rgb;\n  vec3 b=textureOffset(data,vUV,ivec2(0,1)).rgb;\n  vec3 d=textureOffset(data,vUV,ivec2(-1,0)).rgb;\n  vec3 f=textureOffset(data,vUV,ivec2(1,0)).rgb;\n  vec3 h=textureOffset(data,vUV,ivec2(0,-1)).rgb;\n  const vec3 lw=vec3(0.2126,0.7152,0.0722);\n  float le=dot(e,lw);float lb=dot(b,lw);float ld=dot(d,lw);float lf=dot(f,lw);float lh=dot(h,lw);\n  float mn_l=min(min(min(ld,le),min(lf,lb)),lh);\n  float mx_l=max(max(max(ld,le),max(lf,lb)),lh);\n  float amp=mn_l/(mx_l+0.01);\n  float wm=clamp((le-0.05)*2.2222,0.0,1.0);\n  float cg=clamp((mx_l-mn_l-0.005)*28.57,0.0,1.0);\n  float w=-(wm*cg)*(amp*0.2);\n  float rw=1.0/(4.0*w+1.0);\n  float detL=clamp(((lb+ld+lf+lh)*w+le)*rw,0.0,1.0)-le;\n  float satBoost=1.0+wm*0.18;\n  float sharpL=le+detL/(1.0+abs(detL)*4.0)*sharpenFactor*satBoost;\n  fragColor=vec4(clamp(vec3(sharpL)+(e-vec3(le))*satBoost,0.0,1.0),1.0);\n}';

        const SHARPEN_FACTOR_NORMAL = '0.37';
        const SHARPEN_FACTOR_HIGH = '1.0';
        const fragSource = fragTemplate.replace(
            '%SHARPEN_FACTOR%',
            casMode === 'high' ? SHARPEN_FACTOR_HIGH : SHARPEN_FACTOR_NORMAL
        );
        const mkShader = (type, src) => {
            const s = gl.createShader(type);
            gl.shaderSource(s, src);
            gl.compileShader(s);
            if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
                gl.deleteShader(s);
                return null;
            }
            return s;
        };

        const prog = gl.createProgram();
        const vs = mkShader(gl.VERTEX_SHADER, vert);
        const fs = mkShader(gl.FRAGMENT_SHADER, fragSource);
        if (!vs || !fs) {
            abortSetup(gl, prog, [vs, fs]);
            return;
        }
        gl.attachShader(prog, vs);
        gl.attachShader(prog, fs);
        gl.linkProgram(prog);
        gl.detachShader(prog, vs); gl.deleteShader(vs);
        gl.detachShader(prog, fs); gl.deleteShader(fs);
        if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
            abortSetup(gl, prog, null);
            return;
        }
        gl.useProgram(prog);
        gl.disable(gl.BLEND);
        gl.disable(gl.DITHER);

        const vao = gl.createVertexArray();
        gl.bindVertexArray(vao);

        const buf = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, buf);
        gl.bufferData(gl.ARRAY_BUFFER, triVerts, gl.STATIC_DRAW);
        const posLoc = gl.getAttribLocation(prog, 'position');
        gl.enableVertexAttribArray(posLoc);
        gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 0, 0);

        const tex = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, tex);

        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, EMPTY_PIXEL);
        gl.activeTexture(gl.TEXTURE0);
        gl.uniform1i(gl.getUniformLocation(prog, 'data'), 0);
        // Runs synchronously on 'resize'. It used to be debounced 16ms, which left a
        // frame where present() scaled the new stream resolution into the old bridge
        // size — xCloud changes resolution mid-stream on network conditions, so that
        // was a visible hitch every switch. Nothing to debounce: the event only fires
        // when the dimensions actually changed, and the equality check below already
        // absorbs any burst.
        const _syncSize = () => {
            if (!video.videoWidth || !video.videoHeight) return;
            const w = video.videoWidth;
            const h = video.videoHeight;
            if (canvas.width === w && canvas.height === h) return;
            canvas.width = bridge.width = w;
            canvas.height = bridge.height = h;
            // Resizing the bridge resets its 2D context to defaults — reapply
            bridgeCtx.imageSmoothingEnabled = false;
            bridgeCtx.globalCompositeOperation = 'copy';
            gl.viewport(0, 0, w, h);
        };
        _syncSize();

        video.addEventListener('loadedmetadata', _syncSize);
        video.addEventListener('resize', _syncSize);

        let frameHandle = null;
        let vfcHandle = null;
        const hasRVFC = 'requestVideoFrameCallback' in HTMLVideoElement.prototype;

        const present = () => {
            if (video.readyState < 2 || video.paused || document.hidden) return;
            bridgeCtx.drawImage(video, 0, 0, bridge.width, bridge.height);
            gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, bridge);
            gl.drawArrays(gl.TRIANGLES, 0, 3);
        };

        // Draw the moment the frame is decoded. This used to set a flag that the
        // next rAF tick acted on, which cost up to a full vsync of latency before
        // the frame was even copied, and put the GPU work on the vsync critical
        // path. Draw count is unchanged — rVFC fires exactly once per decoded
        // frame, which is what the old flag gated on.
        const onVFC = () => {
            vfcHandle = null;
            present();
            if (!video.paused && !document.hidden)
                vfcHandle = video.requestVideoFrameCallback(onVFC);
        };

        // Teardown detection has to survive a stopped loop. 'pause' and
        // visibilitychange both cancel rAF, and the liveness check lives inside
        // render() — so quitting a game or switching games left the last frame
        // frozen under the opaque canvas with no path back to teardown. This only
        // ticks while the loop is already stopped: zero cost while streaming.
        let watchdog = null;
        let watchdogDelay = 500;
        const stopWatchdog = () => {
            if (watchdog !== null) { clearTimeout(watchdog); watchdog = null; }
            // Reset here, not in armWatchdog: stopWatchdog is the "loop is alive
            // again" signal (scheduleFrame calls it on resume), so the next stall
            // starts back at a fast 500ms first check.
            watchdogDelay = 500;
        };
        const checkAlive = () => {
            watchdog = null;
            if (!video.srcObject || !document.contains(video) || video.ended) {
                teardown(video);
                return;
            }
            // Paused-but-alive (srcObject intact, still in the DOM): nothing can
            // change until an event fires, so don't keep polling at 2Hz forever —
            // that was a permanent wakeup with nothing to do. Backs off
            // 500 -> 1000 -> 2000 -> 4000 -> 5000ms. First check is still 500ms.
            if (frameHandle === null) {
                watchdogDelay = Math.min(watchdogDelay * 2, 5000);
                watchdog = setTimeout(checkAlive, watchdogDelay);
            }
        };
        const armWatchdog = () => {
            if (watchdog === null) watchdog = setTimeout(checkAlive, watchdogDelay);
        };

        const scheduleFrame = () => {
            if (frameHandle !== null) return;
            // Arm rather than bail silently: this is the path taken when setup runs
            // against an already-paused video, and when visibilitychange restores a
            // stream that died while backgrounded.
            if (video.paused || document.hidden) { armWatchdog(); return; }
            stopWatchdog();
            frameHandle = requestAnimationFrame(render);
            if (hasRVFC && vfcHandle === null) vfcHandle = video.requestVideoFrameCallback(onVFC);
        };

        const cancelFrame = () => {
            if (frameHandle !== null) { cancelAnimationFrame(frameHandle); frameHandle = null; }
            if (vfcHandle !== null) { video.cancelVideoFrameCallback(vfcHandle); vfcHandle = null; }
            armWatchdog();
        };

        // The rAF loop still spins every frame — it keeps the compositor and the
        // CPU governor warm, which is why it is unconditional — but rVFC now drives
        // the draw, so this is only the liveness ticker. It replaces the old
        // parent-scoped removal MutationObserver, and remains the draw driver on
        // the fallback path where rVFC is unavailable.
        let liveCheck = 0;
        const render = () => {
            frameHandle = null;
            if (++liveCheck >= 60) {
                liveCheck = 0;
                if (!video.srcObject || !document.contains(video)) {
                    teardown(video);
                    return;
                }
            }
            if (!hasRVFC) present();
            scheduleFrame();
        };

        const onVisibility = () => { if (document.hidden) cancelFrame(); else scheduleFrame(); };
        video.addEventListener('pause', cancelFrame);
        video.addEventListener('play', scheduleFrame);
        document.addEventListener('visibilitychange', onVisibility);
        scheduleFrame();

        // stopWatchdog() must follow cancelFrame() — cancelFrame arms the watchdog,
        // so the reverse order leaves a timer running against a torn-down pipeline.
        const detach = () => {
            cancelFrame();
            stopWatchdog();
            video.removeEventListener('pause', cancelFrame);
            video.removeEventListener('play', scheduleFrame);
            document.removeEventListener('visibilitychange', onVisibility);
            video.removeEventListener('loadedmetadata', _syncSize);
            video.removeEventListener('resize', _syncSize);
            canvas.removeEventListener('webglcontextlost', onContextLost, false);
            canvas.remove();
            video.style.visibility = '';
            delete video.dataset.casSetup;
            delete video._casCleanup;
        };

        const onContextLost = (e) => {
            e.preventDefault();
            detach();
            watchWithoutCAS(video);
        };
        // detach() (via onContextLost) leaves this listener attached, so a restore can
        // arrive after this stream ended or another one bound. Rebuilding then would
        // stack a second opaque canvas and GL context over the live stream.
        const onContextRestored = () => {
            if (activeStreamVideo === video && video.srcObject && document.contains(video)) {
                setupWebGLCAS(video);
            }
        };
        canvas.addEventListener('webglcontextlost', onContextLost, false);
        canvas.addEventListener('webglcontextrestored', onContextRestored, false);

        video._casCleanup = () => {
            detach();
            // Both context listeners must be gone before loseContext(): it queues a
            // real 'webglcontextlost' task, and by the time that task runs xCloud may
            // have re-used this same <video> for the next stream. The stale handler
            // would then delete the new pipeline's casSetup/_casCleanup (leaking its
            // GL context and canvas on the following switch) and unhide the video so
            // it composites underneath the new opaque canvas every frame.
            canvas.removeEventListener('webglcontextrestored', onContextRestored, false);
            bridge.width = 1; bridge.height = 1;
            gl.getExtension('WEBGL_lose_context')?.loseContext();
        };
        if (video._gxOffCleanup) video._gxOffCleanup();
    };

    window.__gxcloudSetCasMode = (mode) => {
        const next = mode === 'off' || mode === 'high' ? mode : 'normal';
        if (next === casMode) return;
        casMode = next;
        const video = activeStreamVideo;
        if (!video) return;
        // Cleanup restores direct video and releases the old context before a
        // replacement pipeline is created, so mode changes cannot stack canvases.
        if (video._casCleanup) video._casCleanup();
        watchWithoutCAS(video);
        if (casMode !== 'off' && document.contains(video)) setupWebGLCAS(video);
    };

    // Prefer IPv6. xCloud and Remote Play hand the client the server's ICE
    // candidates in the JSON-encoded `exchangeResponse` of a GET .../sessions/
    // {cloud|home}/{id}/ice. Re-ranking those candidates so every IPv6 one outranks
    // every IPv4 one makes the controlling side (this client) nominate an IPv6 pair
    // whenever one connects. IPv4 candidates are kept at low priority, so a network
    // without working IPv6 still connects over IPv4 — only the preference changes.
    // Takes effect when a stream connects; toggling mid-stream changes nothing
    // until the next one.
    //
    // Same endpoint, payload and json()/text() override as Better xCloud's
    // patchIceCandidates (src/utils/network.ts). Two deliberate differences: with
    // the toggle off the exchange is left byte-for-byte untouched (Better xCloud
    // always renumbers), and every IPv6 candidate outranks IPv4 rather than only
    // the first candidate getting the top priority.
    let preferIpv6 = window.__gxcloudInitialPreferIpv6 === true;
    // IPv6 candidates available to the last stream, or null before any stream.
    let serverIpv6Candidates = null;
    // Remote Play only: the console's own IPv6 address from the session's
    // configuration response. The ICE exchange doesn't necessarily list it, so —
    // like Better xCloud's XhomeInterceptor.handleConfiguration — it is added as a
    // candidate, here only while Prefer IPv6 is on.
    let consoleIpv6 = null;
    const ICE_URL = /\/sessions\/(cloud|home)\/[^/?#]+\/ice(?:[?#]|$)/;
    const CONSOLE_CONFIG_URL = /\/sessions\/home\/[^/?#]+\/configuration(?:[?#]|$)/;
    // Better xCloud also tries 9002 alongside the advertised port.
    const CONSOLE_DEFAULT_PORT = 9002;
    const MAX_ICE_PRIORITY = 2130706431;
    const IPV4_FALLBACK_PRIORITY = 1000;

    const readConsoleAddress = (config) => {
        const details = config?.serverDetails;
        const address = typeof details?.ipV6Address === 'string' && details.ipV6Address.includes(':')
            ? details.ipV6Address : null;
        consoleIpv6 = address
            ? { address, ports: [...new Set([details.ipV6Port, CONSOLE_DEFAULT_PORT].filter(Number.isInteger))] }
            : null;
    };

    // Candidate line: "a=candidate:<foundation> <component> <transport> <priority>
    // <address> <port> typ ...". Lines that don't parse pass through untouched.
    const parseCandidate = (entry) => {
        const parts = typeof entry?.candidate === 'string' ? entry.candidate.split(' ') : null;
        const valid = !!parts && parts.length > 5 && parts[0].startsWith('a=candidate:') && /^\d+$/.test(parts[3]);
        return { entry, parts, valid, ipv6: valid && parts[4].includes(':') };
    };

    const rankIceCandidates = (candidates, consoleAddr) => {
        const parsed = candidates.map(parseCandidate);
        const known = new Set(parsed.filter(p => p.valid).map(p => p.parts[4].toLowerCase() + ' ' + p.parts[5]));
        const template = parsed.find(p => p.valid)?.entry ?? { messageType: 'iceCandidate', sdpMLineIndex: '0', sdpMid: '0' };
        const added = (consoleAddr?.ports ?? [])
            .filter(port => !known.has(consoleAddr.address.toLowerCase() + ' ' + port))
            .map((port, i) => parseCandidate({
                ...template,
                candidate: `a=candidate:${candidates.length + i + 1} 1 UDP 1 ${consoleAddr.address} ${port} typ host`
            }));
        serverIpv6Candidates = parsed.filter(p => p.ipv6).length + added.length;
        if (!preferIpv6 || !serverIpv6Candidates) return null;
        // New candidates go before a=end-of-candidates, which must stay last.
        const end = parsed.findIndex(p => p.entry?.candidate === 'a=end-of-candidates');
        parsed.splice(end < 0 ? parsed.length : end, 0, ...added);
        const byPriority = (a, b) => Number(b.parts[3]) - Number(a.parts[3]);
        parsed.filter(p => p.ipv6).sort(byPriority)
            .forEach((p, i) => { p.parts[3] = String(MAX_ICE_PRIORITY - i); });
        parsed.filter(p => p.valid && !p.ipv6).sort(byPriority)
            .forEach((p, i) => { p.parts[3] = String(Math.max(1, IPV4_FALLBACK_PRIORITY - i)); });
        return parsed.map(p => p.valid ? { ...p.entry, candidate: p.parts.join(' ') } : p.entry);
    };

    // Overrides json()/text() on the original Response rather than building a new
    // one, so status, headers and url stay exactly what xCloud's client expects.
    const rewriteIceResponse = async (response, consoleAddr) => {
        if (!response.ok) return response;
        const body = await response.clone().json();
        if (typeof body?.exchangeResponse !== 'string') return response;
        const candidates = JSON.parse(body.exchangeResponse);
        if (!Array.isArray(candidates)) return response;
        const ranked = rankIceCandidates(candidates, consoleAddr);
        if (!ranked) return response;
        body.exchangeResponse = JSON.stringify(ranked);
        const text = JSON.stringify(body);
        response.json = () => Promise.resolve(JSON.parse(text));
        response.text = () => Promise.resolve(text);
        return response;
    };

    const nativeFetch = window.fetch;
    if (typeof nativeFetch === 'function') {
        window.fetch = function(input, init) {
            const result = nativeFetch.apply(this, arguments);
            const url = typeof input === 'string' ? input : input?.url ?? String(input);
            const method = String(init?.method ?? input?.method ?? 'GET').toUpperCase();
            if (method !== 'GET') return result;
            if (CONSOLE_CONFIG_URL.test(url)) {
                // Observe only. Cloned before this returns, so it never races xCloud's read.
                result.then(response => response.ok ? response.clone().json() : null)
                    .then(readConsoleAddress, () => { consoleIpv6 = null; });
                return result;
            }
            const ice = ICE_URL.exec(url);
            if (!ice) return result;
            // Any parse failure (204 while the server is still gathering, a schema
            // change) hands xCloud the untouched response.
            return result.then(response =>
                rewriteIceResponse(response, ice[1] === 'home' ? consoleIpv6 : null).catch(() => response));
        };
    }

    window.__gxcloudSetPreferIpv6 = (enabled) => { preferIpv6 = enabled === true; };

    // Which path the live stream actually took. Only the newest peer connection is
    // kept — xCloud makes one per stream. getStats() is async and the stats page
    // polls synchronously, so each poll starts a refresh and reports the previous
    // one (at most one poll interval stale). Nothing runs unless the page asks.
    let streamPeerConnection = null;
    let connectionPath = null;
    let connectionPathPending = false;
    const NativePeerConnection = window.RTCPeerConnection;
    if (typeof NativePeerConnection === 'function') {
        window.RTCPeerConnection = class RTCPeerConnection extends NativePeerConnection {
            constructor(...args) {
                super(...args);
                streamPeerConnection = this;
                connectionPath = null;
            }
        };
    }

    const describePath = (stats) => {
        let pairId = null;
        const reports = new Map();
        stats.forEach(stat => {
            reports.set(stat.id, stat);
            if (stat.type === 'transport' && stat.selectedCandidatePairId) pairId = stat.selectedCandidatePairId;
        });
        const pair = reports.get(pairId);
        const remote = reports.get(pair?.remoteCandidateId);
        const local = reports.get(pair?.localCandidateId);
        const address = remote?.address ?? remote?.ip;
        if (typeof address !== 'string') return null;
        const relayed = remote.candidateType === 'relay' || local?.candidateType === 'relay';
        return (address.includes(':') ? 'IPv6' : 'IPv4') + (relayed ? ' (relay)' : ' (direct)');
    };

    const refreshConnectionPath = () => {
        const pc = streamPeerConnection;
        if (!pc || connectionPathPending) return;
        if (pc.connectionState === 'closed') { connectionPath = null; return; }
        connectionPathPending = true;
        pc.getStats().then(stats => {
            if (pc === streamPeerConnection) connectionPath = describePath(stats);
        }, () => {}).finally(() => { connectionPathPending = false; });
    };

    window.__gxcloudGetStreamStats = () => {
        refreshConnectionPath();
        const video = activeStreamVideo;
        if (!video) return { state: 'No active stream', casMode, serverIpv6Candidates, connectionPath };
        const quality = video.getVideoPlaybackQuality?.();
        const total = quality?.totalVideoFrames ?? video.webkitDecodedFrameCount ?? '--';
        const dropped = quality?.droppedVideoFrames ?? video.webkitDroppedFrameCount ?? '--';
        const presented = typeof total === 'number' && typeof dropped === 'number'
            ? Math.max(total - dropped, 0)
            : '--';
        return {
            state: video.ended ? 'Ended' : video.paused ? 'Paused' : video.readyState >= 2 ? 'Playing' : 'Loading',
            resolution: video.videoWidth && video.videoHeight ? video.videoWidth + '×' + video.videoHeight : '--',
            totalFrames: String(total),
            presentedFrames: String(presented),
            droppedFrames: String(dropped),
            casMode,
            serverIpv6Candidates,
            connectionPath
        };
    };

    const foundVideo = (video) => {
        if (video.dataset.gxBound) return;
        // Retire the previous stream before the new one allocates anything — see
        // activeStreamVideo above for why overlap is not survivable.
        if (activeStreamVideo && activeStreamVideo !== video) teardown(activeStreamVideo);
        video.dataset.gxBound = 'true';
        activeStreamVideo = video;

        // Binding lifetime is independent of sharpening. Element-bound events
        // still reach us after detachment and survive mode changes/context loss.
        const onStreamEnd = () => {
            if (video._gxBindingCleanup === cleanupBinding) teardown(video);
        };
        const cleanupBinding = () => {
            video.removeEventListener('ended', onStreamEnd);
            video.removeEventListener('error', onStreamEnd);
            video.removeEventListener('emptied', onStreamEnd);
            if (video._gxBindingCleanup === cleanupBinding) delete video._gxBindingCleanup;
        };
        video._gxBindingCleanup = cleanupBinding;
        video.addEventListener('ended', onStreamEnd);
        video.addEventListener('error', onStreamEnd);
        video.addEventListener('emptied', onStreamEnd);
        watchWithoutCAS(video);

        // xCloud renders the quick-actions toggle in the same commit as the video,
        // so it is normally already in the DOM — check synchronously first. A
        // MutationObserver alone starves here: the stream page goes DOM-quiet once
        // playing, and only guide open/close churn would ever wake it.
        const retryDelays = [150, 400, 1000, 2500, 5000];
        let attempt = 0;
        let menuTimer = null;
        const menuObserver = new MutationObserver(() => { hideMenuButton(); });
        const tryHide = () => {
            menuTimer = null;
            if (hideMenuButton()) {
                // Landed. Keep a narrowly-scoped watch in case xCloud re-renders
                // the toggle and drops our dataset marker.
                const toggle = document.querySelector('button[aria-label="Quick actions toggle" i]');
                const container = toggle?.closest('.absolute');
                if (container?.parentNode) menuObserver.observe(container.parentNode, { childList: true });
                return;
            }
            if (attempt >= retryDelays.length) return;
            menuTimer = setTimeout(tryHide, retryDelays[attempt++]);
        };
        tryHide();

        video._gxMenuCleanup = () => {
            clearTimeout(menuTimer);
            menuTimer = null;
            attempt = retryDelays.length;
            menuObserver.disconnect();
            delete video._gxMenuCleanup;
        };

        setupWebGLCAS(video);
    };

    // xCloud plays three different <video> elements over a session: the Xbox
    // splash, a rocket loading animation, and the real stream. Binding to either
    // of the first two is why CAS appeared not to activate. The stream is the one
    // with no src (it is fed by srcObject) whose direct parent is the media
    // container — the only stable anchor; the element carries no id.
    const isStreamVideo = (v) => {
        if (!v || v.tagName !== 'VIDEO' || v.src) return false;
        const cls = typeof v.className === 'string' ? v.className : '';
        if (cls.startsWith('XboxSplashVideo') || cls.includes('RocketAnimationVideo')) return false;
        // Preferred anchor; fall back to srcObject alone so an upstream markup
        // change degrades instead of never binding at all.
        return v.parentElement?.dataset.testid === 'media-container' || !!v.srcObject;
    };

    const bindWhenSized = (v) => {
        if (v.dataset.gxBound) return;
        if (v.videoWidth) foundVideo(v);
        else v.addEventListener('loadedmetadata', () => {
            if (v.videoWidth) foundVideo(v);
        }, { once: true });
    };

    // Primary acquisition. Patching play() catches the element the instant xCloud
    // plays it, even while it is still detached — capture-phase listeners on
    // document never see events from a node outside the tree, which is why the
    // event-only approach missed the stream.
    const nativePlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function() {
        if (isStreamVideo(this)) bindWhenSized(this);
        return nativePlay.apply(this, arguments);
    };

    // Secondary net, in case the stream video was already playing before this
    // script was injected. Costs nothing until a media event actually fires.
    const onMediaReady = (e) => {
        if (isStreamVideo(e.target) && e.target.videoWidth) foundVideo(e.target);
    };
    const onMediaGone = (e) => {
        const v = e.target;
        if (v && v.tagName === 'VIDEO' && v.dataset.gxBound) teardown(v);
    };
    document.addEventListener('loadedmetadata', onMediaReady, true);
    document.addEventListener('playing', onMediaReady, true);
    document.addEventListener('emptied', onMediaGone, true);

    // Warm case: already streaming when the script runs (WebView restore, or
    // re-injection after a real navigation) — no play() call or media event is
    // coming, so neither hook above would ever fire.
    for (const v of document.querySelectorAll('video')) {
        if (isStreamVideo(v)) { bindWhenSized(v); break; }
    }
})();
