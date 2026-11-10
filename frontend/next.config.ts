import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactCompiler: true,
  // 生成 .next/standalone，供 Dockerfile 多阶段构建的运行阶段使用
  output: 'standalone',
};

export default nextConfig;
