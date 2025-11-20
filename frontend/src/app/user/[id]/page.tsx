'use client';
import { useEffect, useState, use } from 'react';
import api from '@/lib/api';
import Navbar from '@/components/Navbar';
import { User } from '@/types';
import { UserCircle, UserPlus, UserMinus } from 'lucide-react';

export default function UserProfilePage({ params }: { params: Promise<{ id: string }> }) {
  // Next.js 15+ param unwrapping
  const resolvedParams = use(params);
  const userId = resolvedParams.id;

  const [user, setUser] = useState<User | null>(null);
  const [isFollowing, setIsFollowing] = useState(false); // 简单处理：实际应从后端获取是否已关注
  const [loading, setLoading] = useState(true);

  // 获取用户信息
  useEffect(() => {
    const fetchData = async () => {
      try {
        const userRes = await api.get(`/user/${userId}`);
        if (userRes.data.code === 0) {
          setUser(userRes.data.data);
        }
        
        // 检查是否关注（这里有个小技巧：获取"我"的关注列表看该ID是否在其中）
        // 注意：你的后端 getFollowingList 返回的是 List<String>，如果是 ID 字符串列表则可以直接对比
        // 如果是用户名列表，对比会比较麻烦。这里假设我们尝试先不判断，点击时直接调接口
        // 为了严谨，建议后端增加一个 endpoint: /is-following/{id} 
      } catch (err) {
        console.error(err);
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [userId]);

  const handleFollow = async () => {
    try {
      await api.post(`/follow/${userId}`);
      setIsFollowing(true);
      // 更新粉丝数显示
      if (user) setUser({ ...user, followerCount: user.followerCount + 1 });
    } catch (err) {
      alert('关注失败');
    }
  };

  const handleUnfollow = async () => {
    try {
      await api.delete(`/follow/${userId}`);
      setIsFollowing(false);
      if (user) setUser({ ...user, followerCount: user.followerCount - 1 });
    } catch (err) {
      alert('取关失败');
    }
  };

  if (loading) return <div className="p-10 text-center">加载中...</div>;
  if (!user) return <div className="p-10 text-center">用户不存在</div>;

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
                <p className="text-gray-600 mt-2">{user.bio || '这个人很懒，什么都没写'}</p>
              </div>
              
              {/* 关注/取关按钮 */}
              <div className="flex space-x-2">
                 {/* 注意：由于后端没提供"是否已关注"字段，这里初始状态可能不准，
                     实际开发建议后端 UserResponse 增加 `boolean followed` 字段 */}
                {!isFollowing ? (
                  <button 
                    onClick={handleFollow}
                    className="flex items-center bg-orange-500 text-white px-4 py-2 rounded hover:bg-orange-600"
                  >
                    <UserPlus size={16} className="mr-2"/> 关注
                  </button>
                ) : (
                  <button 
                    onClick={handleUnfollow}
                    className="flex items-center bg-gray-200 text-gray-700 px-4 py-2 rounded hover:bg-gray-300"
                  >
                    <UserMinus size={16} className="mr-2"/> 已关注
                  </button>
                )}
              </div>
            </div>
            
            <div className="flex space-x-8 mt-6">
              <div className="text-center">
                <div className="font-bold text-lg">{user.followCount}</div>
                <div className="text-gray-500 text-sm">关注</div>
              </div>
              <div className="text-center">
                <div className="font-bold text-lg">{user.followerCount}</div>
                <div className="text-gray-500 text-sm">粉丝</div>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}