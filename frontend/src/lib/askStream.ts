// src/lib/askStream.ts
// 流式问答：后端 SSE（GET /posts/search/ask/stream）。
// EventSource 不能带 Authorization 头，所以用 fetch 读 ReadableStream，自己按 SSE 格式切事件。
import { API_URL } from '@/lib/api';
import { AskResponse, QueryIntent } from '@/types';

export interface AskStreamHandlers {
  onIntent: (intent: QueryIntent) => void;
  onDelta: (text: string) => void;
  onResult: (result: AskResponse) => void;
  onError: (message: string) => void;
}

export async function askStream(query: string, handlers: AskStreamHandlers, signal: AbortSignal) {
  const token = typeof window !== 'undefined' ? localStorage.getItem('token') : null;

  let res: Response;
  try {
    res = await fetch(`${API_URL}/posts/search/ask/stream?q=${encodeURIComponent(query)}`, {
      headers: {
        Accept: 'text/event-stream',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      signal,
    });
  } catch {
    if (!signal.aborted) handlers.onError('AI answer is temporarily unavailable');
    return;
  }

  if (res.status === 401 || res.status === 403) {
    localStorage.removeItem('token');
    window.location.href = '/login';
    return;
  }
  if (!res.ok || !res.body) {
    handlers.onError('AI answer is temporarily unavailable');
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finished = false;

  const dispatch = (raw: string) => {
    let event = 'message';
    const data: string[] = [];
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (data.length === 0) return;
    const payload = JSON.parse(data.join('\n'));
    switch (event) {
      case 'intent':
        handlers.onIntent(payload.intent);
        break;
      case 'delta':
        handlers.onDelta(payload.text);
        break;
      case 'result':
        finished = true;
        handlers.onResult(payload);
        break;
      case 'error':
        finished = true;
        handlers.onError(payload.message || 'AI answer is temporarily unavailable');
        break;
    }
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n?/g, '\n');
      let idx;
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        dispatch(buffer.slice(0, idx));
        buffer = buffer.slice(idx + 2);
      }
    }
    if (buffer.trim()) dispatch(buffer);
  } catch {
    if (signal.aborted) return;
  }
  if (!finished && !signal.aborted) {
    handlers.onError('Connection lost before the answer finished');
  }
}
