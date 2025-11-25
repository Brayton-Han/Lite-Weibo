'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import api from '@/lib/api';
import { ApiResponse } from '@/types';
import { toast } from 'react-hot-toast';

export default function LoginPage() {
  const router = useRouter();
  const [formData, setFormData] = useState({ username: '', password: '' });
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    
      const res = await api.post<ApiResponse<string>>('/login', formData);
      const responseData = res.data;

      if (responseData.code === 0) {
        const token = responseData.data;
        localStorage.setItem('token', token);
        
        toast.success('Login successful!'); 
        router.push('/me');
      } else {
        // 调用全局配置的 Toast 显示错误
        toast.error(responseData.message || 'Login failed');
      }
    
      setLoading(false);
    
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-100">
      <div className="bg-white p-8 rounded-lg shadow-md w-full max-w-md">
        <h1 className="text-2xl font-bold mb-8 text-center text-gray-800">Weibo Login</h1>

        <form onSubmit={handleSubmit} className="space-y-6">
          {/* Username: Label on the left */}
          <div className="flex items-center">
            <label className="w-24 text-sm font-medium text-gray-700 shrink-0">
              Username
            </label>
            <input
              type="text"
              required
              className="flex-1 px-3 py-2 border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
              value={formData.username}
              onChange={(e) => setFormData({ ...formData, username: e.target.value })}
            />
          </div>
          
          {/* Password: Label on the left */}
          <div className="flex items-center">
            <label className="w-24 text-sm font-medium text-gray-700 shrink-0">
              Password
            </label>
            <input
              type="password"
              required
              className="flex-1 px-3 py-2 border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
              value={formData.password}
              onChange={(e) => setFormData({ ...formData, password: e.target.value })}
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-blue-600 text-white py-2 rounded hover:bg-blue-700 transition disabled:bg-blue-400 mt-2"
          >
            {loading ? 'Logging in...' : 'Login'}
          </button>
        </form>

        <div className="mt-6 text-center text-sm text-gray-600">
          Don't have an account? <Link href="/register" className="text-blue-600 hover:underline">Register here</Link>
        </div>
      </div>
    </div>
  );
}