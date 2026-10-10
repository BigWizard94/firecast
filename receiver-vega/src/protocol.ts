/**
 * FireCast wire protocol v1 — receiver side (see PROTOCOL.md).
 *
 * The receiver dials the sender's control WebSocket, then implements the
 * receiver role: replies to hello/pair, executes play/pause/seek/etc.,
 * and emits status/position/ended/error events.
 */

export interface PlayPayload {
  url: string;
  title?: string;
  mime?: string;
  position?: number;
  headers?: Record<string, string>;
}

export interface StatusPayload {
  state: string;
  position: number;
  duration: number;
  volume: number;
  muted: boolean;
  title: string;
}

/** Callbacks the UI/player layer implements. */
export interface ReceiverDelegate {
  /** Show the PIN on screen; called when an unpaired sender says hello. */
  showPin(pin: string): void;
  hidePin(): void;
  /** Sender paired successfully (or was already paired). */
  onPaired(): void;
  /** Execute playback commands. Play resolves true when playback starts. */
  play(payload: PlayPayload): Promise<boolean>;
  pause(): void;
  resume(): void;
  stop(): void;
  seek(position: number): void;
  setVolume(level: number): void;
  setMuted(muted: boolean): void;
  /** Current full status snapshot. */
  getStatus(): StatusPayload;
}

const RECEIVER_NAME = 'FireCast Vega';
const RECEIVER_VERSION = '0.2.1';
const PROTOCOL_VERSION = 1;

export class FirecastReceiver {
  private ws: WebSocket | null = null;
  private url: string | null = null;
  private pin: string | null = null;
  private lastFingerprint: string | null = null;
  private pairedFingerprints = new Set<string>();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private delegate: ReceiverDelegate;
  private stopped = false;

  constructor(delegate: ReceiverDelegate) {
    this.delegate = delegate;
  }

  /** Dial the sender's control WebSocket. Reconnects on drop until stop(). */
  connect(wsUrl: string) {
    this.url = wsUrl;
    this.stopped = false;
    this.open();
  }

  stop() {
    this.stopped = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    try {
      this.ws?.close();
    } catch {}
    this.ws = null;
  }

  private open() {
    if (this.stopped || !this.url) return;
    try {
      const ws = new WebSocket(this.url);
      this.ws = ws;
      ws.onopen = () => {};
      ws.onmessage = (ev: any) => {
        let m: any;
        try {
          m = JSON.parse(ev.data);
        } catch {
          return;
        }
        this.handle(m);
      };
      ws.onclose = () => {
        this.ws = null;
        if (!this.stopped) {
          this.reconnectTimer = setTimeout(() => this.open(), 3000);
        }
      };
      ws.onerror = () => {
        try {
          ws.close();
        } catch {}
      };
    } catch {
      if (!this.stopped) {
        this.reconnectTimer = setTimeout(() => this.open(), 3000);
      }
    }
  }

  private send(obj: any) {
    if (this.ws && this.ws.readyState === 1) {
      this.ws.send(JSON.stringify(obj));
    }
  }

  /** Receiver → sender events (no id). */
  sendStatus() {
    this.send({v: 1, type: 'status', payload: this.delegate.getStatus()});
  }
  sendPosition(position: number, duration: number) {
    this.send({v: 1, type: 'position', payload: {position, duration}});
  }
  sendEnded() {
    this.send({v: 1, type: 'ended', payload: {}});
  }
  sendError(code: string, message: string) {
    this.send({v: 1, type: 'error', payload: {code, message}});
  }

  private handle(m: any) {
    const type = m.type;
    const id = m.id;
    const p = m.payload || {};
    const d = this.delegate;

    if (type === 'hello') {
      this.lastFingerprint = p.fingerprint || null;
      const paired =
        !!this.lastFingerprint &&
        this.pairedFingerprints.has(this.lastFingerprint);
      if (!paired) {
        this.pin = String(Math.floor(1000 + Math.random() * 9000));
        d.showPin(this.pin);
      } else {
        d.hidePin();
        d.onPaired();
      }
      this.send({
        v: 1,
        id,
        type: 'hello_result',
        payload: {
          ok: true,
          paired,
          receiver: RECEIVER_NAME,
          receiver_version: RECEIVER_VERSION,
          protocol: PROTOCOL_VERSION,
        },
      });
    } else if (type === 'pair') {
      const ok = !!this.pin && p.pin === this.pin;
      if (ok) {
        if (this.lastFingerprint) {
          this.pairedFingerprints.add(this.lastFingerprint);
        }
        this.pin = null;
        d.hidePin();
        d.onPaired();
      }
      this.send({
        v: 1,
        id,
        type: 'pair_result',
        payload: ok ? {ok: true} : {ok: false, error: 'bad_pin'},
      });
    } else if (type === 'play') {
      d.play(p as PlayPayload).then(ok => {
        this.send({
          v: 1,
          id,
          type: 'play_result',
          payload: ok ? {ok: true} : {ok: false, error: 'unplayable'},
        });
      });
    } else if (type === 'pause') {
      d.pause();
      this.send({v: 1, id, type: 'pause_result', payload: {ok: true}});
      this.sendStatus();
    } else if (type === 'resume') {
      d.resume();
      this.send({v: 1, id, type: 'resume_result', payload: {ok: true}});
      this.sendStatus();
    } else if (type === 'stop') {
      d.stop();
      this.send({v: 1, id, type: 'stop_result', payload: {ok: true}});
      this.sendStatus();
    } else if (type === 'seek') {
      d.seek(Math.max(0, p.position || 0));
      this.send({v: 1, id, type: 'seek_result', payload: {ok: true}});
      this.sendStatus();
    } else if (type === 'volume') {
      const lvl = Math.min(1, Math.max(0, p.level == null ? 1 : p.level));
      d.setVolume(lvl);
      this.send({v: 1, id, type: 'volume_result', payload: {ok: true}});
      this.sendStatus();
    } else if (type === 'mute') {
      d.setMuted(!!p.muted);
      this.send({v: 1, id, type: 'mute_result', payload: {ok: true}});
      this.sendStatus();
    } else if (type === 'get_status') {
      this.send({
        v: 1,
        id,
        type: 'get_status_result',
        payload: d.getStatus(),
      });
    } else if (type === 'ping') {
      this.send({v: 1, type: 'pong'});
    }
    // Unknown types ignored (forward compatibility).
  }
}

/**
 * Extract the sender's ws_url from a DIAL launch URI.
 * Vega delivers the DIAL POST body "as a parameter in the launch URI";
 * the exact wrapping is version-dependent, so try several shapes:
 *   ?ws_url=ws://...   |   ?payload={"ws_url":"..."}   |   raw JSON
 */
export function parseWsUrl(launchUrl: string | null | undefined): string | null {
  if (!launchUrl) return null;
  try {
    // 1. Query parameter ws_url
    const qMatch = launchUrl.match(/[?&]ws_url=([^&]+)/);
    if (qMatch) {
      const decoded = decodeURIComponent(qMatch[1]);
      if (decoded.startsWith('ws://') || decoded.startsWith('wss://')) {
        return decoded;
      }
    }
    // 2. Query parameter payload containing JSON
    const pMatch = launchUrl.match(/[?&](payload|launchPayload|data)=([^&]+)/);
    if (pMatch) {
      const obj = JSON.parse(decodeURIComponent(pMatch[2]));
      if (obj && typeof obj.ws_url === 'string') return obj.ws_url;
    }
    // 3. JSON embedded anywhere in the string
    const jMatch = launchUrl.match(/\{[^}]*"ws_url"\s*:\s*"([^"]+)"[^}]*\}/);
    if (jMatch) return jMatch[1];
    // 4. The whole string is JSON
    const obj = JSON.parse(launchUrl);
    if (obj && typeof obj.ws_url === 'string') return obj.ws_url;
  } catch {}
  return null;
}
