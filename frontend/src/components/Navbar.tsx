'use client';

import Link from 'next/link';
import { useRouter, usePathname } from 'next/navigation';
import { Search, Home, LogIn } from 'lucide-react';
import { useEffect, useState } from 'react';
import api from '@/lib/api';
import { ApiResponse, User } from '@/types';

export default function Navbar() {
  const router = useRouter();
  const pathname = usePathname();
  const [me, setMe] = useState<User | null>(null);

  useEffect(() => {
    // 定义获取用户信息的函数
    const fetchMe = async () => {
      const myId = localStorage.getItem('userId');
      const token = localStorage.getItem('token');
      
      if (!myId || !token) {
        setMe(null); // 如果没有 token，清空状态
        return;
      }

      try {
        const res = await api.get<ApiResponse<User>>(`/user/${myId}`);
        if (res.data.code === 0) {
          setMe(res.data.data);
        }
      } catch (error) {
        console.error("Failed to fetch user info for navbar", error);
      }
    };

    // 1. 组件加载时获取一次
    fetchMe();

    // 2. 监听自定义事件 'user-profile-updated'
    // 当其他组件修改了用户信息时，触发此事件，Navbar 重新拉取数据
    const handleUserUpdate = () => {
      fetchMe();
    };
    
    window.addEventListener('user-profile-updated', handleUserUpdate);
    
    // 同时也监听 storage 事件（处理多标签页登出/登录的情况）
    window.addEventListener('storage', handleUserUpdate);

    // 3. 清理监听器
    return () => {
      window.removeEventListener('user-profile-updated', handleUserUpdate);
      window.removeEventListener('storage', handleUserUpdate);
    };
  }, []); // 依赖项保持为空

  // ... (getIconClass 和 return 部分保持不变，不需要改动)
  const getIconClass = (targetPath: string) => {
    const isActive = pathname === targetPath;
    const baseClass = "transition-all duration-200 p-2 rounded-full";
    if (isActive) {
      return `${baseClass} bg-blue-100 text-blue-700 shadow-sm`;
    } else {
      return `${baseClass} text-gray-500 hover:bg-gray-100 hover:text-blue-600`;
    }
  };

  return (
    <nav className="fixed top-0 left-0 right-0 h-16 bg-white border-b border-gray-200 z-50 px-4 shadow-sm">
      <div className="max-w-6xl mx-auto h-full flex items-center justify-between">
        <div className="flex items-center cursor-pointer" onClick={() => router.push('/')}>
          <span className="text-xl font-bold text-blue-600 tracking-tight">Lite Weibo</span>
        </div>

        <div className="flex items-center space-x-6">
          <button onClick={() => router.push('/search')} className={getIconClass('/search')} title="Search">
            <Search size={24} strokeWidth={pathname === '/search' ? 2.5 : 2} />
          </button>

          <button onClick={() => router.push('/')} className={getIconClass('/')} title="Home">
            <Home size={24} strokeWidth={pathname === '/' ? 2.5 : 2} />
          </button>

          {me ? (
            <div 
              onClick={() => router.push(`/user/${me.id}`)}
              className="cursor-pointer relative group flex items-center"
              title="My Profile"
            >
              <img 
                src={me.avatarUrl || "/default-avatar.png"} 
                alt={me.username} 
                className={`w-9 h-9 rounded-full object-cover transition-all duration-200 ${
                  pathname === `/user/${me.id}`
                    ? 'ring-2 ring-blue-600 ring-offset-2'
                    : 'border border-gray-200 group-hover:border-blue-400'
                }`}
              />
            </div>
          ) : (
            <button
              onClick={() => router.push('/login')}
              className={`flex items-center gap-2 text-sm font-medium px-3 py-2 rounded-md transition-colors ${
                pathname === '/login' 
                  ? 'bg-blue-50 text-blue-700' 
                  : 'text-gray-700 hover:text-blue-600 hover:bg-gray-50'
              }`}
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