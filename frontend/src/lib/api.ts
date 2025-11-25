// src/lib/api.ts
import axios from 'axios';

const API_URL = 'http://localhost:8080'; // 你的后端地址

const api = axios.create({
  baseURL: API_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// 请求拦截器：自动携带 Token
api.interceptors.request.use((config) => {
  if (typeof window !== 'undefined') {
    const token = localStorage.getItem('token');
    if (token) {
      // 假设你的后端 Security 配置需要 Bearer 前缀
      config.headers.Authorization = `Bearer ${token}`;
    }
  }
  return config;
});

// 响应拦截器：处理 Token 过期
api.interceptors.response.use(
  (response) => {
    // 正常 200 → 不变
    return response;
  },
  (error) => {
    const status = error.response?.status;
    const err = error.response?.data;

    // ========================
    //     处理 401 / 403
    // ========================
    if (status === 401 || status === 403) {
      if (typeof window !== "undefined") {
        localStorage.removeItem("token");
        window.location.href = "/login";
      }
    }

    // ========================
    //   统一错误结构（关键）
    // ========================
    return Promise.resolve({
      data: {
        code: err?.code ?? status ?? -1,
        message: err?.message ?? "Unknown error",
        data: null,
        timestamp: err?.timestamp ?? new Date().toISOString(),
      },
    });
  }
);


export default api;