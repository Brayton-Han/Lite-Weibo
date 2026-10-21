// src/types/index.ts

// 对应后端的 Gender 枚举
export type Gender = 'MALE' | 'FEMALE' | 'NON_BINARY' | 'OTHER';

// 对应后端的 ApiResponse<T>
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: string;
}

// 对应后端的 UserResponse DTO
export interface User {
  id: number;
  username: string;
  gender: Gender | null;
  avatarUrl?: string;
  bio?: string;
  birthday?: string; // LocalDate 序列化后通常是字符串
  joinDate: string;
  followerCount: number;
  followCount: number;
  friendCount: number;
  following: boolean;
  followed: boolean;
  postCount: number;
}

// 登录请求参数
export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  id: string;
}

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
}

export enum PostType {
  ORIGINAL = 'ORIGINAL',
  REPOST = 'REPOST',
  QUOTE = 'QUOTE'
}

export enum PostVisibility {
  PUBLIC = 'PUBLIC',
  PRIVATE = 'PRIVATE',
  FOLLOWERS = 'FOLLOWERS',
  FRIENDS = 'FRIENDS'
}

export interface Post {
  id: number;
  user: User;
  type: PostType;
  content: string;
  images: string[];
  refPost: Post | null;
  visibility: PostVisibility;
  liked: boolean;
  likeCount: number;
  commentCount: number;
  repostCount: number;
  createdAt: string; // Java LocalDateTime 序列化后通常是 ISO 字符串
  updatedAt: string;
  edited: boolean;
}

export interface CreatePostRequest {
  content: string;
  images: string[];
  visibility: PostVisibility;
  type?: PostType; 
  refPostId?: number;
}

export interface UpdatePostRequest {
  content: string;
  images: string[];
  visibility: PostVisibility;
}

export interface Comment {
  id: number;
  user: User;
  content: string;
  createdAt: string;
}

export interface LikedPostsResponse {
  posts: Post[];
  nextCursor: number;
}

export interface NotificationCounts {
  follow: number;
  like: number;
  comment: number;
}

export interface NotificationItem {
  id: number;
  type: 'FOLLOW' | 'LIKE' | 'COMMENT';
  sender: User; // 发送通知的用户
  postId?: number; // 相关联的帖子ID (如果是点赞或评论)
  postPreview?: string; // 帖子内容预览或图片URL
  commentContent?: string; // 评论内容
  createdAt: string;
  read: boolean;
}
// --- 站内问答（/posts/search/ask/stream），对应后端 AskResponse / CitedAnswer ---

export type QueryIntent = 'EXPERIENCE' | 'RESONANCE' | 'RISK';

export type AskMode = 'ANSWER' | 'NO_ANSWER' | 'RESONANCE' | 'RISK';

// 拒答发生在哪一层：检索分数不够 / 没有可用帖子 / 模型自己拒答 / 输出过滤拦截
export type AskRefusal = 'LOW_SCORE' | 'NO_DOCS' | 'MODEL' | 'FILTERED';

export interface Citation {
  ref: number;        // 角标编号，从 1 开始
  postId: number;
  citedText: string;  // API 从原帖逐字截取的片段
  startChar: number;  // UTF-16 下标，区间无效时为 -1
  endChar: number;
}

export interface AnswerSegment {
  text: string;
  citations: Citation[];
}

export interface AnswerSource {
  ref: number;
  postId: number;
  title: string; // "@作者 · 日期"
}

export interface CitedAnswer {
  text: string;
  segments: AnswerSegment[];
  sources: AnswerSource[];
}

export interface ScoredPost {
  post: Post;
  score: number;
}

export interface Helpline {
  name: string;
  phone: string;
  note: string;
}

export interface AskResponse {
  mode: AskMode;
  answer: CitedAnswer | null;
  sourcePosts: Post[] | null;
  posts: ScoredPost[] | null;
  message: string | null;
  refusal: AskRefusal | null;
  helplines: Helpline[] | null;
}

// --- 检索详情面板（/posts/search/retrieval），对应后端 RetrievalDetailResponse ---

export interface RetrievalHit {
  rank: number;
  postId: number;
  score: number;
  author: string | null;  // 帖子已删或改为非公开时为 null
  snippet: string | null;
}

export interface FusedHit extends RetrievalHit {
  lexicalRank: number | null;
  lexicalContribution: number;
  vectorRank: number | null;
  vectorContribution: number;
}

export interface RetrievalDetail {
  lexicalMode: 'trigram' | 'ts_rank_cd';
  lexical: RetrievalHit[];
  vector: RetrievalHit[];
  fused: FusedHit[];
  vectorTop1: number | null;
  config: { k: number; n: number; rrfK: number; vectorWeight: number; refusalThreshold: number; askTopK: number };
  timings: { lexicalMs: number; embedMs: number; vectorMs: number };
}
