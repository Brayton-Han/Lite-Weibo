'use client';

import { useEffect, useState } from 'react';
import api from '@/lib/api';
import { ApiResponse, User, Gender } from '@/types'; // 直接导入 User 和 Gender
import { Loader2, Users, FileText, Heart, Mars, Venus, User as UserIcon } from 'lucide-react';
import Link from 'next/link';
import { toast } from 'react-hot-toast';

export default function UsersPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchUsers = async () => {
      try {
        // 假设获取所有用户的接口是 /users
        const res = await api.get<ApiResponse<User[]>>('/users'); 
        if (res.data.code === 0) {
          setUsers(res.data.data || []);
        } else {
          toast.error(res.data.message || 'Failed to load users');
        }
      } catch (error) {
        console.error(error);
        toast.error('Network error');
      } finally {
        setLoading(false);
      }
    };

    fetchUsers();
  }, []);

  // 根据 Gender 枚举渲染图标
  const getGenderIcon = (gender: Gender | null) => {
    switch (gender) {
      case 'MALE':
        return <Mars size={16} className="text-blue-500" />;
      case 'FEMALE':
        return <Venus size={16} className="text-pink-500" />;
      case 'NON_BINARY':
      case 'OTHER':
        return <UserIcon size={16} className="text-purple-500" />;
      default:
        // null 或未定义时显示保密/未知
        return <span className="text-[10px] text-gray-400 border border-gray-200 px-1 rounded">Secret</span>;
    }
  };

  return (
    <div className="min-h-screen bg-gray-50 pt-20 pb-10">
      <div className="max-w-4xl mx-auto px-4">
        
        {/* 页面标题 */}
        <div className="flex items-center gap-3 mb-6">
          <div className="p-2 bg-purple-100 text-purple-600 rounded-lg">
            <Users size={24} />
          </div>
          <h1 className="text-2xl font-bold text-gray-800">Community Users</h1>
        </div>

        {loading ? (
          <div className="flex flex-col items-center justify-center py-20 text-gray-500">
            <Loader2 className="animate-spin mb-3" size={32} />
            <p>Loading community...</p>
          </div>
        ) : users.length === 0 ? (
          <div className="text-center py-20 text-gray-500 bg-white rounded-xl shadow-sm">
            No users found.
          </div>
        ) : (
          // Grid 布局：一行3个 (md以上)
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            {users.map((user) => (
              <Link 
                key={user.id} 
                href={`/user/${user.id}`}
                className="group block"
              >
                <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-6 flex flex-col items-center hover:shadow-md hover:border-blue-200 transition-all duration-200 cursor-pointer relative overflow-hidden h-full">
                  
                  {/* 顶部装饰背景 (Gradient) */}
                  <div className="absolute top-0 left-0 right-0 h-16 bg-gradient-to-br from-blue-50 to-purple-50 opacity-50 group-hover:opacity-100 transition-opacity" />

                  {/* 头像 */}
                  <div className="relative z-10 -mt-2 mb-3">
                    <img 
                      src={user.avatarUrl || "/default-avatar.png"} 
                      alt={user.username} 
                      className="w-20 h-20 rounded-full object-cover border-4 border-white shadow-sm group-hover:scale-105 transition-transform duration-200 bg-gray-100" 
                    />
                  </div>

                  {/* 用户名与性别 */}
                  <div className="flex items-center gap-2 mb-1 z-10 w-full justify-center">
                    <h3 className="font-bold text-gray-900 text-lg truncate max-w-[140px] group-hover:text-blue-600 transition-colors">
                      {user.username}
                    </h3>
                    {getGenderIcon(user.gender)}
                  </div>

                  {/* 用户简介 Bio (截断显示) */}
                  <p className="text-xs text-gray-500 mb-4 z-10 text-center line-clamp-2 h-8 w-full px-2">
                    {user.bio || "No bio available."}
                  </p>

                  {/* 统计数据 (粉丝数 & 发文数) */}
                  <div className="flex items-center gap-4 w-full justify-center border-t border-gray-50 pt-4 z-10 mt-auto">
                    <div className="text-center px-2">
                      <div className="flex items-center justify-center gap-1 text-gray-400 mb-0.5">
                        <Heart size={14} />
                        <span className="text-[10px] font-medium uppercase tracking-wide">Followers</span>
                      </div>
                      <span className="font-bold text-gray-700 text-sm">{user.followerCount}</span>
                    </div>
                    <div className="w-px h-8 bg-gray-100"></div>
                    <div className="text-center px-2">
                       <div className="flex items-center justify-center gap-1 text-gray-400 mb-0.5">
                        <FileText size={14} />
                        <span className="text-[10px] font-medium uppercase tracking-wide">Posts</span>
                      </div>
                      <span className="font-bold text-gray-700 text-sm">{user.postCount}</span>
                    </div>
                  </div>

                </div>
              </Link>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}