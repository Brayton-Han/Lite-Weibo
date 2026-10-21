'use client';

import { useState } from 'react';
import { ChevronDown, Layers, Loader2 } from 'lucide-react';
import api from '@/lib/api';
import { ApiResponse, FusedHit, RetrievalDetail, RetrievalHit } from '@/types';

// 检索详情：词面 / 向量两路各自的 top-k，以及 RRF 怎么融合。只在展开时请求（多一次 embedding 调用），
// 父组件以 key={query} 挂载，换 query 自动回到折叠状态
export default function RetrievalPanel({ query }: { query: string }) {
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState<RetrievalDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hoverId, setHoverId] = useState<number | null>(null);

  const toggle = async () => {
    const next = !open;
    setOpen(next);
    if (!next || detail || loading) return;
    setLoading(true);
    setError(null);
    const res = await api.get<ApiResponse<RetrievalDetail>>(
      `/posts/search/retrieval?q=${encodeURIComponent(query)}&k=5`
    );
    if (res.data.code === 0) setDetail(res.data.data);
    else setError(res.data.message || 'Failed to load retrieval details');
    setLoading(false);
  };

  return (
    <div className="bg-white rounded-xl shadow mb-4">
      <button
        type="button"
        onClick={toggle}
        className="w-full flex items-center gap-2 px-4 py-3 text-sm font-medium text-gray-700 hover:bg-gray-50 rounded-xl transition-colors"
      >
        <Layers size={16} className="text-gray-400" />
        Retrieval details
        <span className="text-xs font-normal text-gray-400">lexical · vector · RRF</span>
        <ChevronDown size={16} className={`ml-auto text-gray-400 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {open && (
        <div className="px-4 pb-4 border-t border-gray-100">
          {loading && (
            <div className="py-6 flex items-center justify-center text-sm text-gray-500">
              <Loader2 size={16} className="animate-spin mr-2" />
              Running both retrieval paths...
            </div>
          )}
          {error && <p className="py-4 text-sm text-gray-500">{error}</p>}
          {detail && <DetailBody detail={detail} hoverId={hoverId} setHoverId={setHoverId} />}
        </div>
      )}
    </div>
  );
}

function DetailBody({ detail, hoverId, setHoverId }: {
  detail: RetrievalDetail;
  hoverId: number | null;
  setHoverId: (id: number | null) => void;
}) {
  const { config, timings, vectorTop1 } = detail;
  const passes = vectorTop1 !== null && vectorTop1 >= config.refusalThreshold;

  return (
    <>
      {/* 向量 top-1 与拒答阈值：解释这次为什么生成 / 为什么拒答 */}
      <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-gray-500">
        <span>
          Vector top-1{' '}
          <span className="font-mono text-gray-800">{vectorTop1 === null ? '—' : vectorTop1.toFixed(3)}</span>
          {' '}vs refusal threshold <span className="font-mono text-gray-800">{config.refusalThreshold.toFixed(2)}</span>
        </span>
        <span className={`px-2 py-0.5 rounded-full font-medium ${passes ? 'bg-blue-50 text-blue-700' : 'bg-gray-100 text-gray-600'}`}>
          {passes ? 'passes → may generate' : 'below → refused before generation'}
        </span>
        <span className="ml-auto text-gray-400">
          lexical {timings.lexicalMs}ms · embed {timings.embedMs}ms · vector {timings.vectorMs}ms
        </span>
      </div>

      <div className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-4">
        <HitColumn
          title="Lexical"
          scoreLabel={detail.lexicalMode === 'trigram' ? 'word_similarity' : 'ts_rank_cd'}
          hits={detail.lexical}
          hoverId={hoverId}
          setHoverId={setHoverId}
        />
        <HitColumn title="Vector" scoreLabel="cosine" hits={detail.vector} hoverId={hoverId} setHoverId={setHoverId} />
      </div>

      <div className="mt-5">
        <div className="flex flex-wrap items-baseline gap-x-2 mb-2">
          <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider">RRF fused</h3>
          <code className="text-[11px] text-gray-500">
            1/({config.rrfK} + r<sub>lex</sub>) + {config.vectorWeight}/({config.rrfK} + r<sub>vec</sub>), each path top-{config.n}
          </code>
        </div>
        {detail.fused.length === 0 ? (
          <p className="text-sm text-gray-400">Neither path returned anything.</p>
        ) : (
          <ol className="space-y-1">
            {detail.fused.map(h => (
              <FusedRow key={h.postId} hit={h} hoverId={hoverId} setHoverId={setHoverId} />
            ))}
          </ol>
        )}
      </div>

      <p className="mt-4 text-xs text-gray-400 leading-relaxed">
        The AI answer is generated from the vector path top-{config.askTopK}; its refusal threshold was calibrated on
        vector top-1. RRF is the hybrid ranking chosen in offline eval and is not used for answers yet.
        Hover a row to find the same post in the other lists.
      </p>
    </>
  );
}

function HitColumn({ title, scoreLabel, hits, hoverId, setHoverId }: {
  title: string;
  scoreLabel: string;
  hits: RetrievalHit[];
  hoverId: number | null;
  setHoverId: (id: number | null) => void;
}) {
  return (
    <div>
      <div className="flex items-baseline justify-between mb-2">
        <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider">{title}</h3>
        <span className="text-[11px] text-gray-400">{scoreLabel}</span>
      </div>
      {hits.length === 0 ? (
        <p className="text-sm text-gray-400">No matches.</p>
      ) : (
        <ol className="space-y-1">
          {hits.map(h => (
            <li
              key={h.postId}
              onMouseEnter={() => setHoverId(h.postId)}
              onMouseLeave={() => setHoverId(null)}
              className={`flex gap-2 rounded-lg px-2 py-1.5 text-sm transition-colors ${hoverId === h.postId ? 'bg-blue-50' : ''}`}
            >
              <span className="w-4 flex-shrink-0 text-xs text-gray-400 pt-0.5">{h.rank}</span>
              <PostSnippet hit={h} />
              <span className="flex-shrink-0 font-mono text-xs text-gray-600 pt-0.5">{h.score.toFixed(3)}</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function FusedRow({ hit, hoverId, setHoverId }: {
  hit: FusedHit;
  hoverId: number | null;
  setHoverId: (id: number | null) => void;
}) {
  return (
    <li
      onMouseEnter={() => setHoverId(hit.postId)}
      onMouseLeave={() => setHoverId(null)}
      className={`flex gap-2 rounded-lg px-2 py-1.5 text-sm transition-colors ${hoverId === hit.postId ? 'bg-blue-50' : ''}`}
    >
      <span className="w-4 flex-shrink-0 text-xs text-gray-400 pt-0.5">{hit.rank}</span>
      <div className="min-w-0 flex-1">
        <PostSnippet hit={hit} />
        <div className="mt-1 flex flex-wrap gap-1.5 text-[11px] font-mono">
          <PathChip label="L" rank={hit.lexicalRank} contribution={hit.lexicalContribution} />
          <span className="text-gray-300">+</span>
          <PathChip label="V" rank={hit.vectorRank} contribution={hit.vectorContribution} />
        </div>
      </div>
      <span className="flex-shrink-0 font-mono text-xs text-gray-800 pt-0.5">{hit.score.toFixed(3)}</span>
    </li>
  );
}

function PathChip({ label, rank, contribution }: { label: string; rank: number | null; contribution: number }) {
  if (rank === null) {
    return <span className="px-1.5 rounded bg-gray-50 text-gray-300">{label} —</span>;
  }
  return (
    <span className="px-1.5 rounded bg-gray-100 text-gray-600">
      {label}#{rank} <span className="text-gray-400">{contribution.toFixed(3)}</span>
    </span>
  );
}

function PostSnippet({ hit }: { hit: RetrievalHit }) {
  if (hit.snippet === null) {
    return <span className="min-w-0 flex-1 text-gray-300 italic">Post #{hit.postId} unavailable</span>;
  }
  return (
    <span className="min-w-0 flex-1 text-gray-700">
      {hit.author && <span className="text-gray-400 mr-1">@{hit.author}</span>}
      <span className="line-clamp-2">{hit.snippet}</span>
    </span>
  );
}
