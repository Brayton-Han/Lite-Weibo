'use client';

import { useEffect, useState } from 'react';
import api from '@/lib/api';
import { ApiResponse, User } from '@/types';
import { useRouter } from 'next/navigation';

export default function MePage() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    const fetchMe = async () => {
      try {
        // 泛型指定返回的数据 data 是 User 类型
        const res = await api.get<ApiResponse<User>>('/me');
        if (res.data.code === 0) {
          setUser(res.data.data);
        }
      } catch (error) {
        // 拦截器会处理跳转，这里主要处理加载状态
        console.error("Failed to fetch user info");
      } finally {
        setLoading(false);
      }
    };

    fetchMe();
  }, []);

  const handleLogout = () => {
    localStorage.removeItem('token');
    router.push('/login');
  };

  if (loading) return <div className="p-10 text-center">加载中...</div>;
  if (!user) return <div className="p-10 text-center">无法获取用户信息</div>;

  return (
    <div className="min-h-screen bg-gray-50 py-10">
      <div className="max-w-2xl mx-auto bg-white rounded-xl shadow overflow-hidden">
        {/* 头部背景 */}
        <div className="h-32 bg-blue-600"></div>
        
        <div className="px-6 pb-6">
          <div className="relative flex justify-between items-end -mt-12 mb-6">
            {/* 头像 */}
            <div className="relative">
              <img 
                src={user.avatarUrl || "https://via.placeholder.com/150"} 
                alt={user.username}
                className="w-24 h-24 rounded-full border-4 border-white bg-white object-cover"
              />
            </div>
            {/* 退出按钮 */}
            <button 
              onClick={handleLogout}
              className="px-4 py-2 text-sm text-red-600 border border-red-200 rounded hover:bg-red-50"
            >
              退出登录
            </button>
          </div>

          <div>
            <h1 className="text-2xl font-bold text-gray-900">{user.username}</h1>
            <p className="text-sm text-gray-500">ID: {user.id} · 加入时间: {user.joinDate}</p>
            
            <div className="mt-4 py-4 border-t border-b border-gray-100 grid grid-cols-2 gap-4 text-center">
              <div>
                <span className="block text-xl font-bold text-gray-900">{user.followCount}</span>
                <span className="text-sm text-gray-500">关注</span>
              </div>
              <div>
                <span className="block text-xl font-bold text-gray-900">{user.followerCount}</span>
                <span className="text-sm text-gray-500">粉丝</span>
              </div>
            </div>

            <div className="mt-4">
              <h3 className="font-medium text-gray-900">简介</h3>
              <p className="mt-1 text-gray-600">{user.bio || "这个人很懒，什么都没写。"}</p>
            </div>
            
            <div className="mt-4">
               <h3 className="font-medium text-gray-900">基本信息</h3>
               <p className="text-sm text-gray-600 mt-1">性别: {user.gender || '未知'}</p>
               {user.birthday && <p className="text-sm text-gray-600">生日: {user.birthday}</p>}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}