'use client';

import { useEffect, useState, useRef, useCallback } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import api from '@/lib/api';
import { ApiResponse, NotificationCounts, NotificationItem } from '@/types';
import { toast } from 'react-hot-toast';
import { Loader2, Heart, MessageCircle, UserPlus } from 'lucide-react';
import Navbar from '@/components/Navbar';
import Link from 'next/link';

const PAGE_SIZE = 10;

const isImageUrl = (url?: string) => {
  if (!url) return false;
  return /\.(jpeg|jpg|gif|png|webp|bmp|svg)$/i.test(url);
};

// 1. 新增：日期格式化辅助函数
const formatDateTime = (dateStr?: string) => {
  if (!dateStr) return '';
  const date = new Date(dateStr);
  if (isNaN(date.getTime())) return dateStr; // 如果解析失败，返回原字符串

  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const hour = String(date.getHours()).padStart(2, '0');
  const minute = String(date.getMinutes()).padStart(2, '0');

  return `${year}-${month}-${day} ${hour}:${minute}`;
};

export default function NotificationsClient() {
  const router = useRouter();
  const searchParams = useSearchParams();
  
  const typeParam = searchParams.get('type') as 'follow' | 'like' | 'comment' | null;
  const [activeTab, setActiveTab] = useState<'follow' | 'like' | 'comment'>(typeParam || 'like');

  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(true);

  const [unreadCounts, setUnreadCounts] = useState<NotificationCounts>({
    follow: 0,
    like: 0,
    comment: 0
  });

  const observerTarget = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const fetchUnreadCounts = async () => {
      try {
        const res = await api.get<ApiResponse<NotificationCounts>>(`/notification/unread-count`);
        if (res.data.code === 0 && res.data.data) {
          setUnreadCounts(res.data.data);
        }
      } catch (error) {
        console.error("Failed to fetch unread counts", error);
      }
    };
    fetchUnreadCounts();
  }, []);

  useEffect(() => {
    if (typeParam && ['follow', 'like', 'comment'].includes(typeParam)) {
      setActiveTab(typeParam);
    }
  }, [typeParam]);

  const fetchNotifications = useCallback(async (isInit: boolean, lastId?: number) => {
    if (isInit) setLoading(true);
    else setLoadingMore(true);

    try {
      const params = new URLSearchParams();
      params.append('type', activeTab.toUpperCase());
      params.append('size', PAGE_SIZE.toString());
      if (lastId) params.append('lastId', lastId.toString());

      const res = await api.get<ApiResponse<NotificationItem[]>>(`/notification/list?${params.toString()}`);
      
      if (res.data.code === 0) {
        const newItems = res.data.data || [];
        setHasMore(newItems.length >= PAGE_SIZE);
        if (isInit) setNotifications(newItems);
        else setNotifications(prev => [...prev, ...newItems]);
      }
    } catch (e) {
      console.error(e);
      toast.error("Failed to load notifications");
    } finally {
      if (isInit) setLoading(false);
      else setLoadingMore(false);
    }
  }, [activeTab]);

  useEffect(() => {
    setNotifications([]);
    setHasMore(true);
    fetchNotifications(true);
  }, [activeTab, fetchNotifications]);

  const handleLoadMore = () => {
    if (loadingMore || !hasMore || notifications.length === 0) return;
    fetchNotifications(false, notifications[notifications.length - 1].id);
  };

  useEffect(() => {
    const observer = new IntersectionObserver(
      entries => {
        if (entries[0].isIntersecting && hasMore && !loading && !loadingMore) {
          handleLoadMore();
        }
      },
      { threshold: 0.1 }
    );
    if (observerTarget.current) observer.observe(observerTarget.current);
    return () => { if (observerTarget.current) observer.unobserve(observerTarget.current); };
  }, [hasMore, loading, loadingMore, notifications]);

  const handleTabClick = async (tab: 'follow' | 'like' | 'comment') => {
    setActiveTab(tab);
    router.push(`/notifications?type=${tab}`);

    if (unreadCounts[tab] > 0) {
      setUnreadCounts(prev => ({ ...prev, [tab]: 0 }));
      try {
        await api.post(`/notification/mark-read?type=${tab}`);
      } catch (error) {
        console.error(`Failed to mark ${tab} as read`, error);
      }
    }
  };

  const getSidebarItemClass = (tabName: string) => {
    const isActive = activeTab === tabName;
    return `relative flex items-center w-full px-4 py-3 text-sm font-medium rounded-lg transition-colors duration-200 cursor-pointer mb-1 ${
      isActive 
        ? 'bg-blue-50 text-blue-700 border-l-4 border-blue-600' 
        : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
    }`;
  };

  // 渲染 Follow Item
  const renderFollowItem = (item: NotificationItem) => (
    <div key={item.id} className="flex items-center justify-between p-4 hover:bg-gray-50 transition-colors border-b border-gray-100 last:border-0">
      <div className="flex items-center space-x-4 cursor-pointer" onClick={() => router.push(`/user/${item.sender.id}`)}>
        <img src={item.sender.avatarUrl || "/default-avatar.png"} alt={item.sender.username} className="w-12 h-12 rounded-full object-cover border border-gray-200" />
        <div>
          <h4 className="text-sm font-semibold text-gray-900">
            {item.sender.username} 
            <span className="font-normal text-gray-500 ml-1">started following you.</span>
          </h4>
          {/* 2. 修改：使用 formatDateTime */}
          <p className="text-xs text-gray-400 mt-1">{formatDateTime(item.createdAt)}</p>
        </div>
      </div>
      <button 
        onClick={(e) => { e.stopPropagation(); router.push(`/user/${item.sender.id}`); }}
        className="px-3 py-1.5 text-xs font-medium text-blue-600 bg-blue-50 rounded-full hover:bg-blue-100"
      >
        View
      </button>
    </div>
  );

  // 渲染 Like Item
  const renderLikeItem = (item: NotificationItem) => (
    <div key={item.id} className="flex items-center justify-between p-4 hover:bg-gray-50 transition-colors border-b border-gray-100 last:border-0">
      <div className="flex items-start flex-1 mr-4">
        <div className="flex-shrink-0 mr-3 mt-1 cursor-pointer" onClick={() => router.push(`/user/${item.sender.id}`)}>
          <div className="relative">
             <img 
               src={item.sender.avatarUrl || "/default-avatar.png"} 
               alt={item.sender.username} 
               className="w-10 h-10 rounded-full object-cover border border-gray-200" 
             />
             <div className="absolute -bottom-1 -right-1 bg-white rounded-full p-0.5 shadow-sm">
                <Heart size={12} className="text-pink-500 fill-current" />
             </div>
          </div>
        </div>
        
        <div>
          <div className="flex items-center space-x-1 mb-0.5">
            <span 
                className="text-sm font-bold text-gray-900 cursor-pointer hover:underline" 
                onClick={() => router.push(`/user/${item.sender.id}`)}
            >
              {item.sender.username}
            </span>
            <span className="text-sm text-gray-500">liked your post.</span>
          </div>
          {/* 3. 修改：使用 formatDateTime */}
          <p className="text-xs text-gray-400">{formatDateTime(item.createdAt)}</p>
        </div>
      </div>

      {item.postId && item.postPreview && (
        <Link href={`/post/${item.postId}`} className="flex-shrink-0">
           {isImageUrl(item.postPreview) ? (
             <img src={item.postPreview} alt="Post preview" className="w-12 h-12 object-cover rounded-md border border-gray-200" />
           ) : (
             <div className="w-12 h-12 bg-gray-100 rounded-md border border-gray-200 flex items-center justify-center p-1.5 overflow-hidden">
                <p className="text-[9px] text-gray-500 leading-tight line-clamp-3 break-all text-center">{item.postPreview}</p>
             </div>
           )}
        </Link>
      )}
    </div>
  );

  // 渲染 Comment Item
  const renderCommentItem = (item: NotificationItem) => (
    <div key={item.id} className="flex items-center justify-between p-4 hover:bg-gray-50 transition-colors border-b border-gray-100 last:border-0">
      <div className="flex items-start flex-1 mr-4">
        <div className="flex-shrink-0 mr-3 mt-1 cursor-pointer" onClick={() => router.push(`/user/${item.sender.id}`)}>
          <div className="relative">
             <img 
               src={item.sender.avatarUrl || "/default-avatar.png"} 
               alt={item.sender.username} 
               className="w-10 h-10 rounded-full object-cover border border-gray-200" 
             />
             <div className="absolute -bottom-1 -right-1 bg-white rounded-full p-0.5 shadow-sm">
                <MessageCircle size={12} className="text-green-500 fill-current" />
             </div>
          </div>
        </div>

        <div>
          <div className="flex items-center space-x-1 mb-0.5">
            <span 
               className="text-sm font-bold text-gray-900 cursor-pointer hover:underline" 
               onClick={() => router.push(`/user/${item.sender.id}`)}
            >
              {item.sender.username}
            </span>
            <span className="text-sm text-gray-500">commented:</span>
          </div>
          
          <Link href={`/post/${item.postId}`} className="block">
            <span className="text-sm text-gray-800 line-clamp-2 hover:text-blue-600 transition-colors">
              "{item.commentContent}"
            </span>
          </Link>
          {/* 4. 修改：使用 formatDateTime */}
          <p className="text-xs text-gray-400 mt-1">{formatDateTime(item.createdAt)}</p>
        </div>
      </div>

      {item.postId && item.postPreview && (
        <Link href={`/post/${item.postId}`} className="flex-shrink-0 ml-2">
           {isImageUrl(item.postPreview) ? (
             <img src={item.postPreview} alt="Post preview" className="w-12 h-12 object-cover rounded-md border border-gray-200" />
           ) : (
             <div className="w-12 h-12 bg-gray-100 rounded-md border border-gray-200 flex items-center justify-center p-1.5 overflow-hidden">
                <p className="text-[9px] text-gray-500 leading-tight line-clamp-3 break-all text-center">{item.postPreview}</p>
             </div>
           )}
        </Link>
      )}
    </div>
  );

  const renderList = () => {
    if (loading) return <div className="p-20 text-center flex justify-center"><Loader2 className="animate-spin text-gray-400" size={32} /></div>;
    
    if (notifications.length === 0) {
      return (
        <div className="flex flex-col items-center justify-center py-20 text-gray-400">
          <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mb-4">
             {activeTab === 'follow' ? <UserPlus size={32}/> : activeTab === 'like' ? <Heart size={32}/> : <MessageCircle size={32}/>}
          </div>
          <p>No notifications yet.</p>
        </div>
      );
    }

    return (
      <div className="bg-white rounded-xl shadow overflow-hidden min-h-[500px] animate-in fade-in duration-300">
        <div className="px-6 py-4 border-b border-gray-100 bg-white sticky top-0 z-10">
          <h3 className="text-lg font-bold text-gray-900 capitalize">{activeTab} Notifications</h3>
        </div>

        <div className="divide-y divide-gray-100">
          {notifications.map(item => {
            if (activeTab === 'follow') return renderFollowItem(item);
            if (activeTab === 'like') return renderLikeItem(item);
            if (activeTab === 'comment') return renderCommentItem(item);
            return null;
          })}
        </div>

        <div ref={observerTarget} className="py-6 flex justify-center w-full">
           {loadingMore && <Loader2 className="animate-spin text-gray-400" size={24}/>}
           {!hasMore && notifications.length > 0 && <span className="text-xs text-gray-300">End of list</span>}
        </div>
      </div>
    );
  };

  return (
    <>
      <Navbar />
      <div className="min-h-screen bg-gray-50 pt-20 pb-10">
        <div className="max-w-6xl mx-auto px-4 flex flex-col md:flex-row gap-6">
          
          <aside className="w-full md:w-64 flex-shrink-0">
            <div className="bg-white rounded-xl shadow p-4 sticky top-24">
              <h2 className="px-4 pb-2 text-xs font-semibold text-gray-400 uppercase tracking-wider mb-2">
                Notifications
              </h2>
              <nav className="space-y-1">
                <button onClick={() => handleTabClick('follow')} className={getSidebarItemClass('follow')}>
                  <UserPlus size={18} className="mr-3 text-blue-500" />
                  <span className="flex-1 text-left">New Followers</span>
                  {unreadCounts.follow > 0 && (
                    <span className="bg-blue-600 text-white text-xs font-bold px-2 py-0.5 rounded-full ml-2">
                      {unreadCounts.follow > 99 ? '99+' : unreadCounts.follow}
                    </span>
                  )}
                </button>
                
                <button onClick={() => handleTabClick('like')} className={getSidebarItemClass('like')}>
                  <Heart size={18} className="mr-3 text-pink-500" />
                  <span className="flex-1 text-left">Likes</span>
                  {unreadCounts.like > 0 && (
                    <span className="bg-pink-500 text-white text-xs font-bold px-2 py-0.5 rounded-full ml-2">
                      {unreadCounts.like > 99 ? '99+' : unreadCounts.like}
                    </span>
                  )}
                </button>
                
                <button onClick={() => handleTabClick('comment')} className={getSidebarItemClass('comment')}>
                  <MessageCircle size={18} className="mr-3 text-green-500" />
                  <span className="flex-1 text-left">Comments</span>
                  {unreadCounts.comment > 0 && (
                    <span className="bg-green-600 text-white text-xs font-bold px-2 py-0.5 rounded-full ml-2">
                      {unreadCounts.comment > 99 ? '99+' : unreadCounts.comment}
                    </span>
                  )}
                </button>
              </nav>
            </div>
          </aside>

          <main className="w-full max-w-2xl">
            {renderList()}
          </main>
          
          <aside className="hidden lg:block w-64 flex-shrink-0"></aside>
        </div>
      </div>
    </>
  );
}