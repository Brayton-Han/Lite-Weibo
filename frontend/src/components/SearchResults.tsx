'use client';

import { useEffect, useState, useCallback, useRef } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import api from '@/lib/api';
import { ApiResponse, Post } from '@/types';
import PostCard from './PostCard'; 
import { toast } from 'react-hot-toast';
import { Flame, Loader2, Search as SearchIcon } from 'lucide-react';

const PAGE_SIZE = 10;

export default function SearchResults() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const query = searchParams.get('q') || ''; // 获取 URL 中的搜索关键词
  
  // --- State ---
  const [posts, setPosts] = useState<Post[]>([]);
  const [loading, setLoading] = useState(true); 
  const [loadingMore, setLoadingMore] = useState(false); 
  const [hasMore, setHasMore] = useState(true); 
  const [currentUserId, setCurrentUserId] = useState<string | null>(null);

  // --- Refs ---
  const observerTarget = useRef<HTMLDivElement>(null);

  // --- Helpers: 侧边栏样式 (保持与 Square 一致) ---
  const activeSidebarClass = "flex items-center w-full px-4 py-3 text-sm font-medium rounded-lg transition-colors duration-200 cursor-pointer mb-1 bg-blue-50 text-blue-700 border-l-4 border-blue-600";

  // --- Core: 获取搜索结果 ---
  const fetchSearchResults = useCallback(async (isInit: boolean, lastIdParam?: number) => {
    if (!query) {
      setLoading(false);
      return;
    }

    if (isInit) setLoading(true);
    else setLoadingMore(true);

    try {
      // 假设后端搜索接口为 /posts/search
      // 参数: q=keyword, size=10, lastId=lastId
      const params = new URLSearchParams();
      params.append('q', query);
      params.append('size', PAGE_SIZE.toString());
      if (lastIdParam) {
        params.append('lastId', lastIdParam.toString());
      }

      const res = await api.get<ApiResponse<Post[]>>(`/posts/search?${params.toString()}`);

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
      }
    } catch (error) {
      console.error(error);
      toast.error("Failed to search posts");
    } finally {
      if (isInit) setLoading(false);
      else setLoadingMore(false);
    }
  }, [query]);

  // --- Effect: 初始化加载与用户ID获取 ---
  useEffect(() => {
    const myId = localStorage.getItem('userId');
    setCurrentUserId(myId);

    setPosts([]); 
    setHasMore(true);
    fetchSearchResults(true); 
  }, [query, fetchSearchResults]);

  // --- Handlers: Load More ---
  const handleLoadMore = () => {
    if (loadingMore || !hasMore || posts.length === 0) return;
    
    const lastPost = posts[posts.length - 1];
    const lastId = lastPost.id; 
    fetchSearchResults(false, lastId);
  };

  // --- Effect: Intersection Observer ---
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
  }, [posts, hasMore, loadingMore, loading]);

  const handlePostDeleted = (postId: number) => {
    setPosts(posts.filter(p => p.id !== postId));
  };

  return (
    <div className="min-h-screen bg-gray-50 pt-20 pb-10">
      <div className="max-w-6xl mx-auto px-4 flex flex-col md:flex-row gap-6">
        
        {/* --- Left Sidebar --- */}
        <aside className="w-full md:w-64 flex-shrink-0">
          <div className="bg-white rounded-xl shadow p-4 sticky top-24">
            <h2 className="px-4 pb-2 text-xs font-semibold text-gray-400 uppercase tracking-wider">Sort By</h2>
            <nav className="space-y-1">
              {/* 目前只有 Newest */}
              <button className={activeSidebarClass}>
                <Flame size={18} className="mr-3" />
                Newest
              </button>
            </nav>
            
            <div className="mt-6 px-4">
               <button 
                 onClick={() => router.push('/')}
                 className="text-sm text-gray-500 hover:text-gray-900 flex items-center gap-2"
               >
                 ← Back to Feed
               </button>
            </div>
          </div>
        </aside>

        {/* --- Main Content --- */}
        <main className="w-full max-w-2xl">
          
          {/* 搜索结果标题栏 */}
          <div className="mb-6 flex items-center gap-2 text-xl font-bold text-gray-800 px-1">
            <SearchIcon className="text-gray-400" />
            Results for "{query}"
          </div>

          <div className="space-y-4">
            {loading ? (
              <div className="p-10 text-center text-gray-500 flex flex-col items-center">
                <Loader2 className="animate-spin mb-2" />
                Searching...
              </div>
            ) : posts.length === 0 ? (
              <div className="bg-white rounded-xl p-10 text-center text-gray-500 shadow">
                <p className="text-lg font-medium text-gray-900">No results found</p>
                <p className="mt-1">Try searching for different keywords.</p>
              </div>
            ) : (
              <>
                {posts.map(post => (
                  <PostCard 
                    key={post.id} 
                    post={post} 
                    currentUserId={currentUserId} 
                    onDelete={handlePostDeleted}
                  />
                ))}

                {/* --- 底部加载状态 --- */}
                <div ref={observerTarget} className="py-6 text-center h-20 flex items-center justify-center">
                  {loadingMore && (
                    <div className="flex items-center text-gray-500 text-sm">
                      <Loader2 size={16} className="animate-spin mr-2" />
                      Loading more results...
                    </div>
                  )}
                  
                  {!hasMore && posts.length > 0 && (
                    <p className="text-sm text-gray-400">End of results</p>
                  )}
                </div>
              </>
            )}
          </div>
        </main>
        
        {/* --- Right Column (Placeholder) --- */}
        <div className="hidden lg:block w-64 flex-shrink-0">
           {/* 可以放相关搜索、推荐用户等，暂时留空或保持与 Square 一致 */}
        </div>

      </div>
    </div>
  );
}