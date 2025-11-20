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
}

// 登录请求参数
export interface LoginRequest {
  username: string;
  password: string;
}