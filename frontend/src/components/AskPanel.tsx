'use client';

import { useState } from 'react';
import { AlertCircle, CloudOff, HeartHandshake, Loader2, Phone, SearchX, Sparkles } from 'lucide-react';
import { AskDegradation, AskRefusal, AnswerSegment, Citation, CitedAnswer, Post } from '@/types';
import { AskState } from '@/lib/useAskStream';
import PostCard from './PostCard';

// 回答里偶尔带 markdown 标记，纯文本渲染时去掉，避免满屏 ** 和 #
const clean = (text: string) => text.replace(/\*\*/g, '').replace(/^#{1,6}\s+/gm, '');

const sourceAnchor = (postId: number) => `ask-source-${postId}`;

const REFUSAL_HINT: Record<AskRefusal, string> = {
  LOW_SCORE: 'No posts are close enough to this question to answer it.',
  NO_DOCS: 'The matching posts are no longer available.',
  MODEL: 'Related posts were found, but none of them actually answers this question.',
  FILTERED: 'The generated answer was withheld by a safety filter.',
};

const DEGRADATION_HINT: Record<AskDegradation, string> = {
  RATE_LIMITED: "You've asked a lot in the last hour.",
  BUDGET_EXHAUSTED: "Today's AI budget has been used up.",
  AI_UNAVAILABLE: 'The AI service is not responding right now.',
  AI_TIMEOUT: 'The AI took too long to answer.',
  SEARCH_UNAVAILABLE: 'Semantic search is temporarily unavailable.',
  OVERLOADED: 'Too many questions are being answered right now.',
};

const formatWait = (seconds: number) =>
  seconds < 60 ? `${seconds}s` : `${Math.ceil(seconds / 60)} min`;

interface AskPanelProps {
  state: AskState;
  currentUserId: string | null;
  hasKeywordResults: boolean;
}

// 搜索页顶部的 AI 区域：只在需要（或已经）调用生成时出现；共鸣类不在这里渲染，直接展示帖子
export default function AskPanel({ state, currentUserId, hasKeywordResults }: AskPanelProps) {
  const { phase, intent, streamed, result, error } = state;

  if (phase === 'idle') return null;

  if (phase === 'classifying') {
    return (
      <div className="bg-white rounded-xl shadow p-4 mb-4 flex items-center text-sm text-gray-500">
        <Loader2 size={16} className="animate-spin mr-2" />
        Understanding your question...
      </div>
    );
  }

  if (phase === 'error') {
    return (
      <div className="bg-white rounded-xl shadow p-4 mb-4 flex items-start gap-3 text-sm text-gray-600">
        <AlertCircle size={18} className="text-gray-400 flex-shrink-0 mt-0.5" />
        <div>
          <p className="font-medium text-gray-900">AI answer unavailable</p>
          <p className="mt-0.5 text-gray-500">{error}</p>
        </div>
      </div>
    );
  }

  if (result?.mode === 'RISK') return <RiskCard message={result.message} helplines={result.helplines ?? []} />;

  // 降级：AI 回答这次不可用，下面照常给帖子（流式中途降级时，已推出的文本被这张卡片替换）
  if (result?.mode === 'DEGRADED') {
    return (
      <div className="bg-white rounded-xl shadow p-4 mb-4 flex items-start gap-3 text-sm">
        <CloudOff size={18} className="text-amber-500 flex-shrink-0 mt-0.5" />
        <div>
          <p className="font-medium text-gray-900">{result.message || 'AI 回答暂不可用'}</p>
          <p className="mt-0.5 text-gray-500">
            {result.degradation ? DEGRADATION_HINT[result.degradation] : ''}
            {result.retryAfterSeconds ? ` Try again in ${formatWait(result.retryAfterSeconds)}.` : ''}
            {' '}Search results are still shown below.
          </p>
        </div>
      </div>
    );
  }

  if (result?.mode === 'RESONANCE' || (intent && intent !== 'EXPERIENCE')) return null;

  if (result?.mode === 'NO_ANSWER') {
    return (
      <div className="bg-white rounded-xl shadow p-6 mb-4 text-center">
        <div className="mx-auto flex items-center justify-center h-10 w-10 rounded-full bg-gray-100 mb-3">
          <SearchX size={20} className="text-gray-400" />
        </div>
        <p className="font-medium text-gray-900">{result.message || '站内没有找到相关讨论'}</p>
        <p className="mt-1 text-sm text-gray-500">
          {result.refusal ? REFUSAL_HINT[result.refusal] : ''}
          {hasKeywordResults ? ' Showing keyword matches below.' : ''}
        </p>
      </div>
    );
  }

  // EXPERIENCE：检索中 → 流式生成 → 带引用的最终回答
  return (
    <div className="bg-white rounded-xl shadow p-4 mb-4">
      <div className="flex items-center gap-2 text-sm font-semibold text-blue-700 mb-3">
        <Sparkles size={16} />
        AI Answer
        {phase === 'done' && result?.cached && (
          <span className="ml-auto text-xs font-normal text-gray-400">cached</span>
        )}
        {phase !== 'done' && (
          <span className="ml-auto flex items-center text-xs font-normal text-gray-400">
            <Loader2 size={12} className="animate-spin mr-1" />
            {phase === 'retrieving' ? 'Searching posts...' : 'Writing...'}
          </span>
        )}
      </div>

      {phase === 'retrieving' && (
        <div className="space-y-2 animate-pulse">
          <div className="h-3 bg-gray-100 rounded w-full" />
          <div className="h-3 bg-gray-100 rounded w-5/6" />
          <div className="h-3 bg-gray-100 rounded w-2/3" />
        </div>
      )}

      {phase === 'streaming' && (
        <p className="text-gray-800 leading-relaxed whitespace-pre-wrap">
          {clean(streamed)}
          <span className="inline-block w-1.5 h-4 bg-blue-500 ml-0.5 align-middle animate-pulse" />
        </p>
      )}

      {phase === 'done' && result?.answer && (
        <AnswerBody answer={result.answer} sourcePosts={result.sourcePosts ?? []} currentUserId={currentUserId} />
      )}
    </div>
  );
}

function AnswerBody({ answer, sourcePosts, currentUserId }: {
  answer: CitedAnswer;
  sourcePosts: Post[];
  currentUserId: string | null;
}) {
  const [flashId, setFlashId] = useState<number | null>(null);
  const postsById = new Map(sourcePosts.map(p => [p.id, p]));

  const jumpTo = (postId: number) => {
    const el = document.getElementById(sourceAnchor(postId));
    if (!el) return;
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setFlashId(postId);
    setTimeout(() => setFlashId(id => (id === postId ? null : id)), 1600);
  };

  return (
    <>
      <p className="text-gray-800 leading-relaxed whitespace-pre-wrap">
        {answer.segments.map((seg, i) => (
          <SegmentText key={i} segment={seg} answer={answer} onJump={jumpTo} />
        ))}
      </p>

      {answer.sources.length > 0 && (
        <div className="mt-5 pt-4 border-t border-gray-100">
          <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3">Sources</h3>
          {answer.sources.map(src => {
            const post = postsById.get(src.postId);
            return (
              <div
                key={src.ref}
                id={sourceAnchor(src.postId)}
                className={`rounded-xl transition-shadow duration-300 scroll-mt-24 ${flashId === src.postId ? 'ring-2 ring-blue-400' : ''}`}
              >
                <div className="flex items-center gap-2 text-xs text-gray-500 mb-1 px-1">
                  <span className="inline-flex items-center justify-center min-w-5 h-5 px-1 rounded bg-blue-50 text-blue-700 font-semibold">
                    {src.ref}
                  </span>
                  {src.title}
                </div>
                {post ? (
                  <PostCard post={post} currentUserId={currentUserId} onDelete={() => {}} />
                ) : (
                  <div className="bg-gray-50 rounded-xl p-4 mb-4 text-sm text-gray-400">This post is no longer available.</div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}

function SegmentText({ segment, answer, onJump }: {
  segment: AnswerSegment;
  answer: CitedAnswer;
  onJump: (postId: number) => void;
}) {
  // 同一段可能多次引用同一帖子，角标按 ref 去重，悬浮框里列出全部片段
  const byRef = new Map<number, Citation[]>();
  for (const c of segment.citations) {
    byRef.set(c.ref, [...(byRef.get(c.ref) ?? []), c]);
  }

  return (
    <>
      {clean(segment.text)}
      {[...byRef.entries()].map(([ref, cites]) => {
        const source = answer.sources.find(s => s.ref === ref);
        return (
          <span key={ref} className="relative group inline-block align-super">
            <button
              type="button"
              onClick={() => onJump(cites[0].postId)}
              className="mx-0.5 px-1 text-[10px] leading-4 font-semibold rounded bg-blue-50 text-blue-700 hover:bg-blue-600 hover:text-white transition-colors"
              aria-label={`Jump to source ${ref}`}
            >
              {ref}
            </button>
            <span className="pointer-events-none absolute left-1/2 -translate-x-1/2 bottom-full mb-2 w-64 z-20 hidden group-hover:block bg-white border border-gray-200 rounded-lg shadow-lg p-3 text-left text-xs font-normal leading-relaxed whitespace-normal">
              <span className="block text-gray-400 mb-1">{source?.title}</span>
              {cites.map((c, i) => (
                <span key={i} className="block text-gray-700 border-l-2 border-blue-300 pl-2 mt-1 line-clamp-4">
                  {c.citedText}
                </span>
              ))}
            </span>
          </span>
        );
      })}
    </>
  );
}

function RiskCard({ message, helplines }: { message: string | null; helplines: { name: string; phone: string; note: string }[] }) {
  return (
    <div className="bg-white rounded-xl shadow p-5 mb-4 border-l-4 border-rose-400">
      <div className="flex items-center gap-2 font-semibold text-rose-600 mb-2">
        <HeartHandshake size={18} />
        你不是一个人
      </div>
      <p className="text-gray-700 leading-relaxed whitespace-pre-wrap">{message}</p>
      <ul className="mt-4 space-y-2">
        {helplines.map(h => (
          <li key={h.phone} className="flex items-center justify-between bg-rose-50 rounded-lg px-3 py-2">
            <div>
              <p className="text-sm font-medium text-gray-900">{h.name}</p>
              <p className="text-xs text-gray-500">{h.note}</p>
            </div>
            <a href={`tel:${h.phone.split(' ')[0]}`} className="flex items-center gap-1 text-sm font-semibold text-rose-600 hover:text-rose-700">
              <Phone size={14} />
              {h.phone}
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
