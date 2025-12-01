'use client';

import { useEffect, useState, useCallback, useRef } from 'react';
import { useRouter } from 'next/navigation';
import api from '@/lib/api';
import { ApiResponse, Post, User } from '@/types';
import PostCard from './PostCard'; 
import CreatePostWidget from './CreatePostWidget'; 
import { toast } from 'react-hot-toast';
import { Flame, Users, UserCheck, Loader2 } from 'lucide-react';

export type FeedType = 'newest' | 'friends' | 'following';

interface SquareProps {
  currentTab: FeedType;
}

const PAGE_SIZE = 10;

export default function Square({ currentTab }: SquareProps) {
  const router = useRouter();
  
  // --- State ---
  const [posts, setPosts] = useState<Post[]>([]);
  const [loading, setLoading] = useState(true); 
  const [loadingMore, setLoadingMore] = useState(false); 
  const [hasMore, setHasMore] = useState(true); 
  const [me, setMe] = useState<User | null>(null);

  // --- Refs ---
  const observerTarget = useRef<HTMLDivElement>(null);

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

  // --- Core: 获取帖子列表 ---
  // 修改点 2: 参数类型改为 number | string，以兼容 ID 和 时间戳 cursor
  const fetchPosts = useCallback(async (isInit: boolean, cursorParam?: number | string) => {
    if (isInit) setLoading(true);
    else setLoadingMore(true);

    try {
      let baseUrl = '/posts'; // newest 默认走 redis 接口
      if (currentTab === 'friends') baseUrl = '/posts/friends';
      if (currentTab === 'following') baseUrl = '/posts/following';

      const params = new URLSearchParams();
      params.append('size', PAGE_SIZE.toString());

      // 修改点 3: 根据 Tab 类型决定传 cursor 还是 lastId
      if (cursorParam) {
        if (currentTab === 'newest') {
          // Redis 模式：传 cursor (时间戳)
          params.append('cursor', cursorParam.toString());
        } else {
          // DB 模式 (关注/好友)：传 lastId
          params.append('lastId', cursorParam.toString());
        }
      }

      const res = await api.get<ApiResponse<Post[]>>(`${baseUrl}?${params.toString()}`);

      if (res.data.code === 0) {
        const newPosts = res.data.data || [];
        
        if (newPosts.length < PAGE_SIZE) {
          setHasMore(false);
        } else {
          setHasMore(true);
        }

        if (isInit) {
          setPosts(newPosts);
        } else {
          setPosts(prev => {
            const existingIds = new Set(prev.map(p => p.id));
            const uniqueNewPosts = newPosts.filter(p => !existingIds.has(p.id));
            return [...prev, ...uniqueNewPosts];
          });
        }
      } else {
        if (res.data.code === 401) { 
           toast.error("Please login to view this feed");
        }
      }
    } catch (error) {
      console.error(error);
      toast.error("Failed to load posts");
    } finally {
      if (isInit) setLoading(false);
      else setLoadingMore(false);
    }
  }, [currentTab]);

  // --- Effect: 初始化加载 ---
  useEffect(() => {
    const loadUser = async () => {
      const myId = localStorage.getItem('userId');
      if (myId) {
          try {
              const meRes = await api.get<ApiResponse<User>>(`/user/${myId}`);
              if (meRes.data.code === 0) setMe(meRes.data.data);
          } catch(e) { /* quiet fail */ }
      }
    };
    loadUser();

    setPosts([]); 
    setHasMore(true);
    fetchPosts(true); 
  }, [currentTab, fetchPosts]);

  // --- Handlers: Load More (逻辑封装) ---
  const handleLoadMore = () => {
    if (loadingMore || !hasMore || posts.length === 0) return;
    
    const lastPost = posts[posts.length - 1];

    // 修改点 4: 针对 newest 使用 createTime 转时间戳，其他使用 id
    if (currentTab === 'newest') {
      // 假设 Post 类型中有 createTime 字段 (如果是字符串需转 number)
      // 如果后端返回的是 long 类型时间戳，直接用；如果是 ISO String，需 new Date().getTime()
      // 这里为了稳健，尝试转为 timestamp
      const cursor = new Date(lastPost.createdAt).getTime(); 
      fetchPosts(false, cursor);
    } else {
      fetchPosts(false, lastPost.id);
    }
  };

  // --- Effect: Intersection Observer (触底监听) ---
  useEffect(() => {
    const observer = new IntersectionObserver(
      entries => {
        if (entries[0].isIntersecting && hasMore && !loadingMore && !loading) {
          handleLoadMore();
        }
      },
      { threshold: 0.1 } 
    );

    if (observerTarget.current) {
      observer.observe(observerTarget.current);
    }

    return () => {
      if (observerTarget.current) {
        observer.unobserve(observerTarget.current);
      }
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [posts, hasMore, loadingMore, loading]);


  // --- Handlers: Post Actions ---
  const handlePostCreated = (newPost: Post) => {
    setPosts([newPost, ...posts]);
  };

  const handlePostDeleted = (postId: number) => {
    setPosts(posts.filter(p => p.id !== postId));
  };

  return (
    <div className="min-h-screen bg-gray-50 pt-20 pb-10">
      <div className="max-w-6xl mx-auto px-4 flex flex-col md:flex-row gap-6">
        
        {/* --- Left Sidebar --- */}
        <aside className="w-full md:w-64 flex-shrink-0">
          <div className="bg-white rounded-xl shadow p-4 sticky top-24">
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

        {/* --- Main Content --- */}
        <main className="w-full max-w-2xl">
          
          {me && <CreatePostWidget onPostCreated={handlePostCreated} />}

          <div className="space-y-4">
            {/* 初始 Loading 状态 */}
            {loading ? (
              <div className="p-10 text-center text-gray-500 flex flex-col items-center">
                <Loader2 className="animate-spin mb-2" />
                Loading feed...
              </div>
            ) : posts.length === 0 ? (
              <div className="bg-white rounded-xl p-10 text-center text-gray-500 shadow">
                <p>No posts found here.</p>
                {currentTab !== 'newest' && <p className="text-sm mt-2">Try following more people!</p>}
              </div>
            ) : (
              <>
                {posts.map(post => (
                  <PostCard 
                    key={post.id} 
                    post={post} 
                    currentUserId={me?.id ? String(me.id) : null} 
                    onDelete={handlePostDeleted}
                  />
                ))}

                {/* --- 底部哨兵元素 / 加载状态 --- */}
                <div ref={observerTarget} className="py-6 text-center h-20 flex items-center justify-center">
                  {loadingMore && (
                    <div className="flex items-center text-gray-500 text-sm">
                      <Loader2 size={16} className="animate-spin mr-2" />
                      Loading more...
                    </div>
                  )}
                  
                  {!hasMore && posts.length > 0 && (
                    <p className="text-sm text-gray-400">You're all caught up!</p>
                  )}
                </div>
              </>
            )}
          </div>
        </main>
        
        {/* --- Right Column --- */}
        <div className="hidden lg:block w-64 flex-shrink-0">
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