'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import api from '@/lib/api';
import { ApiResponse, Post, User } from '@/types';
import PostCard from './PostCard'; // 确保路径正确
import CreatePostWidget from './CreatePostWidget'; // 确保路径正确
import { toast } from 'react-hot-toast';
import { Flame, Users, UserCheck } from 'lucide-react';

// 定义三种视图类型
export type FeedType = 'newest' | 'friends' | 'following';

interface SquareProps {
  currentTab: FeedType; // 当前处于哪个 Tab
}

export default function Square({ currentTab }: SquareProps) {
  const router = useRouter();
  
  // --- State ---
  const [posts, setPosts] = useState<Post[]>([]);
  const [loading, setLoading] = useState(true);
  const [me, setMe] = useState<User | null>(null);

  // --- Helpers: 侧边栏样式 ---
  const getSidebarItemClass = (tabName: FeedType) => {
    const isActive = currentTab === tabName;
    const baseClass = "flex items-center w-full px-4 py-3 text-sm font-medium rounded-lg transition-colors duration-200 cursor-pointer mb-1";
    return isActive 
      ? `${baseClass} bg-blue-50 text-blue-700 border-l-4 border-blue-600` 
      : `${baseClass} text-gray-600 hover:bg-gray-100 hover:text-gray-900`;
  };

  // --- Handlers: 导航跳转 ---
  const handleNav = (tab: FeedType) => {
    if (tab === 'newest') router.push('/');
    if (tab === 'friends') router.push('/friends');
    if (tab === 'following') router.push('/following');
  };

  // --- Effect: 加载数据 ---
  useEffect(() => {
    const fetchData = async () => {
      setLoading(true);
      try {
        // 1. 获取当前用户 (用于 PostCard 判断权限等)
        const myId = localStorage.getItem('userId');
        if (myId) {
            try {
                const meRes = await api.get<ApiResponse<User>>(`/user/${myId}`);
                if (meRes.data.code === 0) setMe(meRes.data.data);
            } catch(e) { /* quiet fail for me info */ }
        }

        // 2. 根据 Tab 决定 API URL (假设后端有对应接口)
        let url = '/posts'; // 默认所有最新帖子
        if (currentTab === 'friends') url = '/posts/friends'; // 互相关注
        if (currentTab === 'following') url = '/posts/following'; // 我关注的人

        const res = await api.get<ApiResponse<Post[]>>(url);
        
        if (res.data.code === 0) {
          setPosts(res.data.data);
        } else {
          // 如果未登录看 friends/following 可能报错，需处理
          if (res.data.code === 401) { 
             toast.error("Please login to view this feed");
          }
        }
      } catch (error) {
        console.error(error);
        toast.error("Failed to load posts");
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [currentTab]);

  // --- Handlers: Post Actions ---
  const handlePostCreated = (newPost: Post) => {
    // 只有在 Newest 列表或者符合当前列表逻辑时才添加到头部
    // 简单起见，直接添加到头部
    setPosts([newPost, ...posts]);
  };

  const handlePostDeleted = (postId: number) => {
    setPosts(posts.filter(p => p.id !== postId));
  };

  return (
    <div className="min-h-screen bg-gray-50 pt-20 pb-10"> {/* pt-20 为 Navbar 留出空间 */}
      <div className="max-w-6xl mx-auto px-4 flex flex-col md:flex-row gap-6">
        
        {/* --- Left Sidebar (Navigation) --- */}
        <aside className="w-full md:w-64 flex-shrink-0">
          <div className="bg-white rounded-xl shadow p-4 sticky top-24"> {/* top-24 考虑 Navbar 高度 */}
            <h2 className="px-4 pb-2 text-xs font-semibold text-gray-400 uppercase tracking-wider">Feeds</h2>
            <nav className="space-y-1">
              <button onClick={() => handleNav('newest')} className={getSidebarItemClass('newest')}>
                <Flame size={18} className="mr-3" />
                Newest Posts
              </button>
              <button onClick={() => handleNav('friends')} className={getSidebarItemClass('friends')}>
                <Users size={18} className="mr-3" />
                Friends Circle
              </button>
              <button onClick={() => handleNav('following')} className={getSidebarItemClass('following')}>
                <UserCheck size={18} className="mr-3" />
                All Follows
              </button>
            </nav>
          </div>
        </aside>

        {/* --- Main Content (Feed) --- */}
        <main className="w-full max-w-2xl">
          
          {/* 发帖框 (通常只在默认主页或关注页显示) */}
          {me && <CreatePostWidget onPostCreated={handlePostCreated} />}

          {/* 帖子列表 */}
          <div className="space-y-4">
            {loading ? (
              <div className="p-10 text-center text-gray-500">Loading feed...</div>
            ) : posts.length === 0 ? (
              <div className="bg-white rounded-xl p-10 text-center text-gray-500 shadow">
                <p>No posts found here.</p>
                {currentTab !== 'newest' && <p className="text-sm mt-2">Try following more people!</p>}
              </div>
            ) : (
              posts.map(post => (
                <PostCard 
                  key={post.id} 
                  post={post} 
                  currentUserId={me?.id ? String(me.id) : null} 
                  onDelete={handlePostDeleted}
                />
              ))
            )}
          </div>
        </main>
        
        {/* --- Right Column (Optional placeholder for balance) --- */}
        <div className="hidden lg:block w-64 flex-shrink-0">
           {/* 这里可以放 "推荐用户" 或 "热门话题" */}
           <div className="sticky top-24">
             <div className="bg-white rounded-xl shadow p-4">
                <p className="text-sm text-gray-400 text-center">Sponsored Content / Trending</p>
             </div>
           </div>
        </div>

      </div>
    </div>
  );
}