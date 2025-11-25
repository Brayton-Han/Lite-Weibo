'use client';

import { useEffect, useState, use } from 'react';
import api from '@/lib/api';
import Navbar from '@/components/Navbar';
import { User, ApiResponse } from '@/types';
import { UserCircle, UserPlus, UserMinus } from 'lucide-react';
import { toast } from 'react-hot-toast';

export default function UserProfilePage({ params }: { params: Promise<{ id: string }> }) {
  // Next.js 15+ param unwrapping
  const resolvedParams = use(params);
  const userId = resolvedParams.id;

  const [user, setUser] = useState<User | null>(null);
  const [isFollowing, setIsFollowing] = useState(false); 
  const [loading, setLoading] = useState(true);

  // Get user info
  useEffect(() => {
    const fetchData = async () => {
        const userRes = await api.get(`/user/${userId}`);
        if (userRes.data.code === 0) {
          setUser(userRes.data.data);
        } else {
          toast.error(userRes.data.message || "Failed to load user data");
        }
      
        setLoading(false);
    };
    fetchData();
  }, [userId]);

  const handleFollow = async () => {
      const res = await api.post<ApiResponse<string>>(`/follow/${userId}`);
      
      // 获取后端消息
      const serverMsg = res.data.message;

      if (res.data.code === 0) {
        setIsFollowing(true);
        // 更新粉丝数显示
        if (user) setUser({ ...user, followerCount: user.followerCount + 1 });
        
        // 使用 react-hot-toast 显示成功
        toast.success(serverMsg);
      } else {
        // 业务错误
        toast.error(serverMsg);
      }
  };

  const handleUnfollow = async () => {
      const res = await api.delete<ApiResponse<string>>(`/follow/${userId}`);
      
      const serverMsg = res.data.data || 'Unfollowed successfully';

      if (res.data.code === 0) {
        setIsFollowing(false);
        if (user) setUser({ ...user, followerCount: user.followerCount - 1 });
        
        toast.success(serverMsg);
      } else {
        toast.error(serverMsg);
      }
  };

  if (loading) return <div className="p-10 text-center">Loading...</div>;
  if (!user) return <div className="p-10 text-center">User not found</div>;

  return (
    <div className="min-h-screen bg-gray-50">
      <Navbar />
      <main className="max-w-4xl mx-auto p-4">
        <div className="bg-white rounded-lg shadow p-6 flex items-start space-x-6">
          {user.avatarUrl ? (
            <img src={user.avatarUrl} alt={user.username} className="w-24 h-24 rounded-full object-cover" />
          ) : (
            <UserCircle className="w-24 h-24 text-gray-300" />
          )}
          
          <div className="flex-1">
            <div className="flex justify-between items-start">
              <div>
                <h1 className="text-2xl font-bold">{user.username}</h1>
                {/* Translated Bio Placeholder */}
                <p className="text-gray-600 mt-2">{user.bio || 'No bio available.'}</p>
              </div>
              
              {/* Follow/Unfollow Buttons */}
              <div className="flex space-x-2">
                {!isFollowing ? (
                  <button 
                    onClick={handleFollow}
                    className="flex items-center bg-orange-500 text-white px-4 py-2 rounded hover:bg-orange-600 transition-colors"
                  >
                    <UserPlus size={16} className="mr-2"/> Follow
                  </button>
                ) : (
                  <button 
                    onClick={handleUnfollow}
                    className="flex items-center bg-gray-200 text-gray-700 px-4 py-2 rounded hover:bg-gray-300 transition-colors"
                  >
                    <UserMinus size={16} className="mr-2"/> Following
                  </button>
                )}
              </div>
            </div>
            
            {/* Stats Translated */}
            <div className="flex space-x-8 mt-6">
              <div className="text-center">
                <div className="font-bold text-lg">{user.followCount}</div>
                <div className="text-gray-500 text-sm">Following</div>
              </div>
              <div className="text-center">
                <div className="font-bold text-lg">{user.followerCount}</div>
                <div className="text-gray-500 text-sm">Followers</div>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}