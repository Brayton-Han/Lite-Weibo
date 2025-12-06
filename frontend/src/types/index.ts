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

export enum PostVisibility {
  PUBLIC = 'PUBLIC',
  PRIVATE = 'PRIVATE',
  FOLLOWERS = 'FOLLOWERS',
  FRIENDS = 'FRIENDS'
}

export interface Post {
  id: number;
  user: User;
  content: string;
  images: string[];
  visibility: PostVisibility;
  liked: boolean;
  likeCount: number;
  commentCount: number;
  createdAt: string; // Java LocalDateTime 序列化后通常是 ISO 字符串
  updatedAt: string;
  edited: boolean;
}

export interface CreatePostRequest {
  content: string;
  images: string[];
  visibility: PostVisibility;
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