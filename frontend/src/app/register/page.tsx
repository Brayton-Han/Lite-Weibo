'use client';
import React, { JSX, useState } from 'react';
import api from '@/lib/api';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { ApiResponse } from '@/types';
import toast from 'react-hot-toast';

interface RegisterRequest {
  username: string;
  email: string;
  password: string;
}

export default function RegisterPage(): JSX.Element {
  const [formData, setFormData] = useState<RegisterRequest>({
    username: '',
    email: '',
    password: '',
  });
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  const router = useRouter();

  const validate = (data: RegisterRequest): string | null => {
    if (!data.username.trim()) return 'Username cannot be empty.';
    if (!data.email.trim()) return 'Email cannot be empty.';
    const emailRe = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRe.test(data.email)) return 'Please enter a valid email.';
    if (data.password.length < 6) return 'Password must be at least 6 characters.';
    return null;
  };

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();

    const validationMsg = validate(formData);
    if (validationMsg) {
      toast.error(validationMsg);
      return;
    }

    setIsSubmitting(true);

    const registerPromise = async () => {
      const res = await api.post<ApiResponse<String>>('/register', formData);
      if (res?.data?.code !== 0) {
        throw new Error(res?.data?.message || 'Registration failed');
      }
      return res;
    };

    await toast.promise(
      registerPromise(),
      {
        loading: 'Creating your account...',
        success: 'Registration successful! Redirecting...',
        error: (err) => err?.response?.data?.message || err?.message || 'Registration failed',
      }
    )
    .then(() => {
      setTimeout(() => {
        router.push('/login');
      }, 1000);
    })
    .catch((error) => {
      console.error(error);
    })
    .finally(() => {
      setIsSubmitting(false);
    });
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-100">
      {/* 注意：Toaster 最好放在 layout.tsx，如果没放，这里需要保留 <Toaster /> */}
      
      <div className="bg-white p-8 rounded shadow-md w-[28rem]"> {/* 稍微加宽一点宽度 w-96 -> w-[28rem] 以容纳标签 */}
        <h2 className="text-2xl font-bold mb-6 text-center">Create an Account</h2>

        <form onSubmit={handleRegister} className="space-y-5">
          
          {/* Username 行 */}
          <div className="flex items-center">
            <label 
              htmlFor="username" 
              className="w-24 text-sm font-medium text-gray-700"
            >
              Username
            </label>
            <input
              id="username"
              type="text"
              placeholder="Enter your username"
              className="flex-1 h-10 p-2 border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-200 transition-all"
              value={formData.username}
              onChange={(e) => setFormData({ ...formData, username: e.target.value })}
              disabled={isSubmitting}
            />
          </div>

          {/* Email 行 */}
          <div className="flex items-center">
            <label 
              htmlFor="email" 
              className="w-24 text-sm font-medium text-gray-700"
            >
              Email
            </label>
            <input
              id="email"
              type="email"
              placeholder="name@example.com"
              className="flex-1 h-10 p-2 border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-200 transition-all"
              value={formData.email}
              onChange={(e) => setFormData({ ...formData, email: e.target.value })}
              disabled={isSubmitting}
            />
          </div>

          {/* Password 行 */}
          <div className="flex items-center">
            <label 
              htmlFor="password" 
              className="w-24 text-sm font-medium text-gray-700"
            >
              Password
            </label>
            <input
              id="password"
              type="password"
              placeholder="••••••••"
              className="flex-1 h-10 p-2 border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-200 transition-all"
              value={formData.password}
              onChange={(e) => setFormData({ ...formData, password: e.target.value })}
              disabled={isSubmitting}
            />
          </div>

          {/* 注册按钮 (增加一点上边距 mt-6) */}
          <div className="pt-2">
            <button
              type="submit"
              className={`w-full text-white p-2 rounded transition-all ${
                isSubmitting ? 'bg-blue-300 cursor-not-allowed' : 'bg-blue-500 hover:bg-blue-600 shadow-md hover:shadow-lg'
              }`}
              disabled={isSubmitting}
            >
              {isSubmitting ? 'Registering...' : 'Register'}
            </button>
          </div>
        </form>

        <div className="mt-4 text-center text-sm">
          Already have an account? <Link href="/login" className="text-blue-500 hover:underline">Log in</Link>
        </div>
      </div>
    </div>
  );
}