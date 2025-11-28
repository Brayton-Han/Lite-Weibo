'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Search, Home, LogIn } from 'lucide-react';
import { useEffect, useState } from 'react';
import api from '@/lib/api';
import { ApiResponse, User } from '@/types';

export default function Navbar() {
  const router = useRouter();
  const [me, setMe] = useState<User | null>(null);

  // 初始化：获取当前用户信息用于头像显示
  useEffect(() => {
    const fetchMe = async () => {
      const myId = localStorage.getItem('userId');
      const token = localStorage.getItem('token');
      
      if (!myId || !token) return;

      try {
        const res = await api.get<ApiResponse<User>>(`/user/${myId}`);
        if (res.data.code === 0) {
          setMe(res.data.data);
        }
      } catch (error) {
        console.error("Failed to fetch user info for navbar", error);
      }
    };

    fetchMe();
  }, []);

  return (
    <nav className="fixed top-0 left-0 right-0 h-16 bg-white border-b border-gray-200 z-50 px-4 shadow-sm">
      <div className="max-w-6xl mx-auto h-full flex items-center justify-between">
        
        {/* Logo / Brand */}
        <div className="flex items-center cursor-pointer" onClick={() => router.push('/')}>
          <span className="text-xl font-bold text-blue-600 tracking-tight">Lite Weibo</span>
        </div>

        {/* Right Actions */}
        <div className="flex items-center space-x-6">
          
          {/* 1. Search Icon */}
          <button 
            onClick={() => router.push('/search')}
            className="text-gray-500 hover:text-blue-600 transition-colors p-2 rounded-full hover:bg-gray-100"
            title="Search"
          >
            <Search size={24} />
          </button>

          {/* 3. House (Home) Icon */}
          <button 
            onClick={() => router.push('/')}
            className="text-gray-500 hover:text-blue-600 transition-colors p-2 rounded-full hover:bg-gray-100"
            title="Home"
          >
            <Home size={24} />
          </button>

          {/* 2. Avatar / User Profile */}
          {me ? (
            <div 
              onClick={() => router.push(`/user/${me.id}`)}
              className="cursor-pointer relative group"
              title="My Profile"
            >
              <img 
                src={me.avatarUrl || "/default-avatar.png"} 
                alt={me.username} 
                className="w-9 h-9 rounded-full object-cover border border-gray-200 group-hover:border-blue-400 transition-colors"
              />
            </div>
          ) : (
            <button
              onClick={() => router.push('/login')}
              className="flex items-center gap-2 text-sm font-medium text-gray-700 hover:text-blue-600"
            >
              <LogIn size={20} />
              <span>Login</span>
            </button>
          )}

        </div>
      </div>
    </nav>
  );
}