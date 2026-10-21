// src/lib/useAskStream.ts
'use client';

import { useEffect, useState } from 'react';
import { askStream } from '@/lib/askStream';
import { AskResponse, QueryIntent } from '@/types';

export type AskPhase = 'idle' | 'classifying' | 'retrieving' | 'streaming' | 'done' | 'error';

export interface AskState {
  phase: AskPhase;
  intent: QueryIntent | null;
  streamed: string;          // 生成中的纯文本；结束后以 result 为准
  result: AskResponse | null;
  error: string | null;
}

const INITIAL: AskState = { phase: 'idle', intent: null, streamed: '', result: null, error: null };

// query 变化时中止上一次流（后端检测到断开会停止生成），重新开始。
// state 记着它属于哪个 query，换 query 后在拿到新事件之前直接按"分类中"渲染，不在 effect 里同步 setState
export function useAskStream(query: string): AskState {
  const [state, setState] = useState<AskState & { query: string }>({ ...INITIAL, query: '' });

  useEffect(() => {
    if (!query) return;
    const controller = new AbortController();
    const update = (f: (s: AskState) => Partial<AskState>) => {
      if (controller.signal.aborted) return;
      setState(prev => {
        const base = prev.query === query ? prev : { ...INITIAL, phase: 'classifying' as const, query };
        return { ...base, ...f(base) };
      });
    };

    askStream(query, {
      onIntent: intent => update(s => ({ intent, phase: intent === 'EXPERIENCE' ? 'retrieving' : s.phase })),
      onDelta: text => update(s => ({ phase: 'streaming', streamed: s.streamed + text })),
      onResult: result => update(() => ({ phase: 'done', result })),
      onError: error => update(() => ({ phase: 'error', error })),
    }, controller.signal);

    return () => controller.abort();
  }, [query]);

  if (!query) return INITIAL;
  if (state.query !== query) return { ...INITIAL, phase: 'classifying' };
  return state;
}
