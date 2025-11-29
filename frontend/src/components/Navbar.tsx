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

  // 辅助函数：生成图标的样式类
  // 更加明显的样式：选中时添加背景色 (bg-blue-100) 和更深的文字颜色
  const getIconClass = (targetPath: string) => {
    const isActive = pathname === targetPath;
    
    const baseClass = "transition-all duration-200 p-2 rounded-full";
    
    if (isActive) {
      // 高亮状态：蓝色背景 + 蓝色图标 + 稍微加粗
      return `${baseClass} bg-blue-100 text-blue-700 shadow-sm`;
    } else {
      // 默认状态：灰色图标 + 悬停灰色背景
      return `${baseClass} text-gray-500 hover:bg-gray-100 hover:text-blue-600`;
    }
  };

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
            className={getIconClass('/search')}
            title="Search"
          >
            <Search size={24} strokeWidth={pathname === '/search' ? 2.5 : 2} />
          </button>

          {/* 3. House (Home) Icon */}
          <button 
            onClick={() => router.push('/')}
            className={getIconClass('/')}
            title="Home"
          >
            <Home size={24} strokeWidth={pathname === '/' ? 2.5 : 2} />
          </button>

          {/* 2. Avatar / User Profile */}
          {me ? (
            <div 
              onClick={() => router.push(`/user/${me.id}`)}
              className="cursor-pointer relative group flex items-center"
              title="My Profile"
            >
              <img 
                src={me.avatarUrl || "/default-avatar.png"} 
                alt={me.username} 
                // 头像高亮：使用 Ring (外圈) 效果，非常显眼
                className={`w-9 h-9 rounded-full object-cover transition-all duration-200 ${
                  pathname === `/user/${me.id}`
                    ? 'ring-2 ring-blue-600 ring-offset-2' // 高亮：双层蓝圈
                    : 'border border-gray-200 group-hover:border-blue-400' // 默认：灰色边框
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