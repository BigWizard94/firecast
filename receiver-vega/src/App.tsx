import React, {useEffect, useRef, useState, useCallback} from 'react';
import {StyleSheet, Text, View, Linking} from 'react-native';
import {
  VideoPlayer,
  KeplerVideoSurfaceView,
} from '@amazon-devices/react-native-w3cmedia';
import {
  FirecastReceiver,
  ReceiverDelegate,
  PlayPayload,
  StatusPayload,
  parseWsUrl,
} from './protocol';

type Phase = 'waiting' | 'connecting' | 'pairing' | 'ready';

export const App = () => {
  const [phase, setPhase] = useState<Phase>('waiting');
  const [pin, setPin] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [playState, setPlayState] = useState('idle');

  const playerRef = useRef<VideoPlayer | null>(null);
  const surfaceRef = useRef<string | null>(null);
  const initializedRef = useRef(false);
  const receiverRef = useRef<FirecastReceiver | null>(null);
  const posTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const titleRef = useRef('');

  // ---------- player ----------
  const videoState = useCallback((): string => {
    const p = playerRef.current;
    if (!p) return 'idle';
    try {
      if (p.error) return 'error';
      if (p.ended) return 'ended';
      if (p.paused) return p.currentTime > 0 ? 'paused' : 'idle';
      if (p.readyState < 3) return 'buffering';
      return 'playing';
    } catch {
      return 'idle';
    }
  }, []);

  const getStatus = useCallback((): StatusPayload => {
    const p = playerRef.current;
    let position = 0;
    let duration = -1;
    let volume = 1;
    let muted = false;
    try {
      if (p) {
        position = p.currentTime || 0;
        duration = isFinite(p.duration) ? p.duration : -1;
        volume = p.volume;
        muted = p.muted;
      }
    } catch {}
    return {
      state: videoState(),
      position,
      duration,
      volume,
      muted,
      title: titleRef.current,
    };
  }, [videoState]);

  const stopPosTimer = useCallback(() => {
    if (posTimerRef.current) {
      clearInterval(posTimerRef.current);
      posTimerRef.current = null;
    }
  }, []);

  const startPosTimer = useCallback(() => {
    stopPosTimer();
    posTimerRef.current = setInterval(() => {
      const r = receiverRef.current;
      const p = playerRef.current;
      if (r && p && !p.paused && !p.ended) {
        try {
          r.sendPosition(
            p.currentTime || 0,
            isFinite(p.duration) ? p.duration : -1,
          );
        } catch {}
      }
    }, 1000);
  }, [stopPosTimer]);

  const sendStatus = useCallback(() => {
    receiverRef.current?.sendStatus();
    setPlayState(videoState());
  }, [videoState]);

  // ---------- delegate ----------
  const doPlay = useCallback(
    (payload: PlayPayload): Promise<boolean> => {
      return new Promise(resolve => {
        const player = playerRef.current;
        if (!player) {
          resolve(false);
          return;
        }
        stopPosTimer();
        titleRef.current = payload.title || 'Casting…';
        setTitle(titleRef.current);
        setPlayState('loading');
        let done = false;
        const finish = (ok: boolean) => {
          if (done) return;
          done = true;
          player.removeEventListener('playing', onPlaying);
          player.removeEventListener('error', onError);
          if (ok) startPosTimer();
          sendStatus();
          resolve(ok);
        };
        const onPlaying = () => finish(true);
        const onError = () => {
          receiverRef.current?.sendError(
            'fetch_failed',
            'Could not load that media.',
          );
          finish(false);
        };
        player.addEventListener('playing', onPlaying);
        player.addEventListener('error', onError);
        setTimeout(() => finish(!player.error), 20000);
        try {
          // Stop any current playback first (protocol requirement).
          try {
            player.pause();
          } catch {}
          player.src = payload.url;
          if (payload.position) {
            try {
              player.currentTime = payload.position;
            } catch {}
          }
          player.play();
        } catch {
          finish(false);
        }
      });
    },
    [startPosTimer, stopPosTimer, sendStatus],
  );

  const delegateRef = useRef<ReceiverDelegate | null>(null);
  delegateRef.current = {
    showPin: (p: string) => {
      setPin(p);
      setPhase('pairing');
    },
    hidePin: () => {
      setPin(null);
    },
    onPaired: () => {
      setPhase('ready');
    },
    play: doPlay,
    pause: () => {
      try {
        playerRef.current?.pause();
      } catch {}
    },
    resume: () => {
      try {
        playerRef.current?.play();
      } catch {}
    },
    stop: () => {
      stopPosTimer();
      try {
        playerRef.current?.pause();
      } catch {}
      titleRef.current = '';
      setTitle('');
      sendStatus();
    },
    seek: (pos: number) => {
      try {
        if (playerRef.current) playerRef.current.currentTime = pos;
      } catch {}
    },
    setVolume: (lvl: number) => {
      try {
        if (playerRef.current) {
          playerRef.current.volume = lvl;
          if (lvl > 0) playerRef.current.muted = false;
        }
      } catch {}
    },
    setMuted: (m: boolean) => {
      try {
        if (playerRef.current) playerRef.current.muted = m;
      } catch {}
    },
    getStatus,
  };

  // ---------- init: player ----------
  useEffect(() => {
    const player = new VideoPlayer();
    playerRef.current = player;
    player
      .initialize()
      .then(() => {
        initializedRef.current = true;
        if (surfaceRef.current) {
          player.setSurfaceHandle(surfaceRef.current);
        }
      })
      .catch(e => console.error('VideoPlayer init failed', e));

    const onEnded = () => {
      stopPosTimer();
      receiverRef.current?.sendEnded();
      sendStatus();
    };
    const onPausePlay = () => sendStatus();
    player.addEventListener('ended', onEnded);
    player.addEventListener('pause', onPausePlay);
    player.addEventListener('play', onPausePlay);

    return () => {
      stopPosTimer();
      try {
        player.removeEventListener('ended', onEnded);
        player.removeEventListener('pause', onPausePlay);
        player.removeEventListener('play', onPausePlay);
        player.deinitialize();
      } catch {}
      playerRef.current = null;
    };
  }, [sendStatus, stopPosTimer]);

  // ---------- init: launch URL → connect ----------
  useEffect(() => {
    const receiver = new FirecastReceiver(delegateRef.current!);
    receiverRef.current = receiver;

    const handleUrl = (url: string | null) => {
      const wsUrl = parseWsUrl(url);
      if (wsUrl) {
        setPhase('connecting');
        receiver.connect(wsUrl);
      }
    };

    Linking.getInitialURL().then(handleUrl).catch(() => {});
    const sub = Linking.addEventListener('url', ev => handleUrl(ev.url));

    return () => {
      sub.remove();
      receiver.stop();
      receiverRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onSurfaceCreated = useCallback((handle: string) => {
    surfaceRef.current = handle;
    if (initializedRef.current && playerRef.current) {
      try {
        playerRef.current.setSurfaceHandle(handle);
      } catch (e) {
        console.error('setSurfaceHandle failed', e);
      }
    }
  }, []);

  // ---------- UI ----------
  return (
    <View style={styles.root}>
      <KeplerVideoSurfaceView
        style={styles.video}
        scalingmode="fit"
        onSurfaceViewCreated={onSurfaceCreated}
      />
      {phase !== 'ready' || !title ? (
        <View style={styles.overlay}>
          <Text style={styles.logo}>FireCast</Text>
          {phase === 'waiting' && (
            <Text style={styles.sub}>
              Waiting for your phone…{'\n'}Open FireCast on your phone and cast.
            </Text>
          )}
          {phase === 'connecting' && (
            <Text style={styles.sub}>Connecting to your phone…</Text>
          )}
          {phase === 'pairing' && pin && (
            <>
              <Text style={styles.sub}>
                Enter this PIN in the FireCast app on your phone:
              </Text>
              <Text style={styles.pin}>{pin}</Text>
            </>
          )}
          {phase === 'ready' && !title && (
            <Text style={styles.sub}>
              Paired! Pick a video on your phone to cast it here.
            </Text>
          )}
        </View>
      ) : (
        <View style={styles.nowPlaying}>
          <Text style={styles.npTitle} numberOfLines={1}>
            {title}
          </Text>
          <Text style={styles.npState}>{playState}</Text>
        </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#000000',
  },
  video: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
  },
  overlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(10,5,25,0.92)',
    padding: 48,
  },
  logo: {
    fontSize: 72,
    fontWeight: '800',
    color: '#a855f7',
    letterSpacing: 4,
  },
  sub: {
    marginTop: 24,
    fontSize: 28,
    color: '#e9d5ff',
    textAlign: 'center',
    lineHeight: 40,
  },
  pin: {
    marginTop: 32,
    fontSize: 120,
    fontWeight: '800',
    color: '#ffffff',
    letterSpacing: 24,
  },
  nowPlaying: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    padding: 32,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  npTitle: {
    fontSize: 30,
    fontWeight: '600',
    color: '#ffffff',
  },
  npState: {
    marginTop: 8,
    fontSize: 22,
    color: '#c4b5fd',
    textTransform: 'capitalize',
  },
});
