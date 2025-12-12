'use client';

import Link from 'next/link';
import { useRouter, usePathname } from 'next/navigation';
import { Search, Home, LogIn, Bell, Heart, MessageCircle, UserPlus } from 'lucide-react'; 
import { useEffect, useState, useRef } from 'react';
import api from '@/lib/api';
import { ApiResponse, User } from '@/types';
import { Client } from '@stomp/stompjs';
import { NotificationCounts } from '@/types';


export default function Navbar() {
  const router = useRouter();
  const pathname = usePathname();
  const [me, setMe] = useState<User | null>(null);
  
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const [hasNewPost, setHasNewPost] = useState(false);

  // 通知数量状态
  const [notifications, setNotifications] = useState<NotificationCounts>({
    follow: 0,
    like: 0,
    comment: 0
  });
  
  const totalNotifications = notifications.follow + notifications.like + notifications.comment;
  const stompClientRef = useRef<Client | null>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  // 初始化数据
  useEffect(() => {
    const fetchInitialData = async () => {
      const myId = localStorage.getItem('userId');
      const token = localStorage.getItem('token');
      
      if (!myId || !token) {
        setMe(null);
        setNotifications({ follow: 0, like: 0, comment: 0 });
        return;
      }

      try {
        const [userRes, notifRes] = await Promise.all([
          api.get<ApiResponse<User>>(`/user/${myId}`),
          api.get<ApiResponse<NotificationCounts>>(`/notification/unread-count`) 
        ]);

        if (userRes.data.code === 0) setMe(userRes.data.data);
        if (notifRes.data.code === 0 && notifRes.data.data) setNotifications(notifRes.data.data);
      } catch (error) {
        console.error("Failed to fetch initial navbar data", error);
      }
    };

    fetchInitialData();

    const handleUserUpdate = () => fetchInitialData();
    window.addEventListener('user-profile-updated', handleUserUpdate);
    window.addEventListener('storage', handleUserUpdate);

    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);

    return () => {
      window.removeEventListener('user-profile-updated', handleUserUpdate);
      window.removeEventListener('storage', handleUserUpdate);
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, []);

  // WebSocket 逻辑
  useEffect(() => {
    if (!me) return;
    const token = localStorage.getItem('token');
    const wsUrl = `ws://localhost:8080/ws?token=${token}`;
    
    const client = new Client({
      brokerURL: wsUrl, 
      connectHeaders: { Authorization: `Bearer ${token}` },
      onConnect: () => {
        // 收到消息 -> 增加对应计数
        client.subscribe("/user/queue/follow", () => {
          setNotifications(prev => ({ ...prev, follow: prev.follow + 1 }));
        });
        client.subscribe("/user/queue/like", () => {
          setNotifications(prev => ({ ...prev, like: prev.like + 1 }));
        });
        client.subscribe("/user/queue/comment", () => {
          setNotifications(prev => ({ ...prev, comment: prev.comment + 1 }));
        });
        client.subscribe("/user/queue/new-post", () => {
          // 只有当用户不在首页，或者虽然在首页但想提示有新内容时才显示
          // 最简单的逻辑：收到消息就显示红点
          setHasNewPost(true);
        });
      },
    });
    client.activate();
    stompClientRef.current = client;
    return () => {stompClientRef.current?.deactivate();}
  }, [me]); 

  // --- 新增：处理通知点击 ---
  const handleNotificationClick = async (type: keyof NotificationCounts) => {
    // 1. 无论是否有未读消息，都要关闭下拉菜单并跳转
    setIsDropdownOpen(false);
    router.push(`/notifications?type=${type}`);

    // 2. 只有当对应类型的未读数量 > 0 时，才执行标记已读的逻辑
    if (notifications[type] > 0) {
      
      // 乐观更新：前端先立即把红点去掉，让用户感觉反应很快
      setNotifications(prev => ({ ...prev, [type]: 0 }));

      try {
        // 发送请求给后端
        await api.post(`/notification/mark-read?type=${type}`);
      } catch (error) {
        console.error(`Failed to mark ${type} notifications as read`, error);
        // 如果后端报错，这里是否要把红点加回去取决于你的策略，通常不需要，因为用户已经看过了
      }
    }
  };

  const handleHomeClick = () => {
    setHasNewPost(false); // 消除红点
    router.push('/');
    // 如果已经在首页，这里通常可以触发一个列表刷新，
    // 或者利用 window.scrollTo(0,0) 等逻辑，视你首页实现而定
  };

  const getIconClass = (targetPath: string) => {
    const isActive = pathname === targetPath;
    const baseClass = "transition-all duration-200 p-2 rounded-full relative"; 
    return isActive 
      ? `${baseClass} bg-blue-100 text-blue-700 shadow-sm` 
      : `${baseClass} text-gray-500 hover:bg-gray-100 hover:text-blue-600`;
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

          <button 
            onClick={handleHomeClick} 
            className={getIconClass('/')} 
            title="Home"
          >
            <div className="relative">
              <Home size={24} strokeWidth={pathname === '/' ? 2.5 : 2} />
              
              {/* 红点显示逻辑 */}
              {hasNewPost && (
                <span className="absolute top-0 right-0 block h-2.5 w-2.5 rounded-full bg-red-500 ring-2 ring-white transform translate-x-1/2 -translate-y-1/2">
                </span>
              )}
            </div>
          </button>

          {/* 通知区域 */}
          {me && (
            <div className="relative" ref={dropdownRef}>
              <button 
                onClick={() => setIsDropdownOpen(!isDropdownOpen)} 
                className={`${getIconClass('/notifications')} ${isDropdownOpen ? 'bg-gray-100 text-blue-600' : ''}`}
                title="Notifications"
              >
                <Bell size={24} strokeWidth={2} />
                {totalNotifications > 0 && (
                  <span className="absolute top-1 right-1 flex h-4 w-4 items-center justify-center rounded-full bg-red-500 text-[10px] font-bold text-white ring-2 ring-white">
                    {totalNotifications > 99 ? '99+' : totalNotifications}
                  </span>
                )}
              </button>

              {/* 下拉菜单 */}
              {isDropdownOpen && (
                <div className="absolute top-full right-0 mt-2 w-64 bg-white rounded-xl shadow-lg border border-gray-100 py-2 animate-in fade-in zoom-in-95 duration-200 origin-top-right">
                  <div className="px-4 py-2 border-b border-gray-50 mb-1">
                    <span className="text-sm font-semibold text-gray-700">Notifications</span>
                  </div>

                  {/* Follows Row */}
                  <div 
                    className="flex items-center justify-between px-4 py-3 hover:bg-gray-50 cursor-pointer transition-colors"
                    onClick={() => handleNotificationClick('follow')} 
                  >
                    <div className="flex items-center gap-3">
                      <div className="p-2 bg-blue-50 text-blue-600 rounded-full">
                        <UserPlus size={18} />
                      </div>
                      <span className="text-sm text-gray-600 font-medium">New Followers</span>
                    </div>
                    {notifications.follow > 0 && (
                      <span className="bg-blue-600 text-white text-xs font-bold px-2 py-0.5 rounded-full min-w-[20px] text-center">
                        {notifications.follow}
                      </span>
                    )}
                  </div>

                  {/* Likes Row */}
                  <div 
                    className="flex items-center justify-between px-4 py-3 hover:bg-gray-50 cursor-pointer transition-colors"
                    onClick={() => handleNotificationClick('like')}
                  >
                    <div className="flex items-center gap-3">
                      <div className="p-2 bg-pink-50 text-pink-500 rounded-full">
                        <Heart size={18} />
                      </div>
                      <span className="text-sm text-gray-600 font-medium">Likes</span>
                    </div>
                    {notifications.like > 0 && (
                      <span className="bg-pink-500 text-white text-xs font-bold px-2 py-0.5 rounded-full min-w-[20px] text-center">
                        {notifications.like}
                      </span>
                    )}
                  </div>

                  {/* Comments Row */}
                  <div 
                    className="flex items-center justify-between px-4 py-3 hover:bg-gray-50 cursor-pointer transition-colors"
                    onClick={() => handleNotificationClick('comment')}
                  >
                    <div className="flex items-center gap-3">
                      <div className="p-2 bg-green-50 text-green-600 rounded-full">
                        <MessageCircle size={18} />
                      </div>
                      <span className="text-sm text-gray-600 font-medium">Comments</span>
                    </div>
                    {notifications.comment > 0 && (
                      <span className="bg-green-600 text-white text-xs font-bold px-2 py-0.5 rounded-full min-w-[20px] text-center">
                        {notifications.comment}
                      </span>
                    )}
                  </div>
                </div>
              )}
            </div>
          )}

          {me ? (
            <div 
              onClick={() => router.push(`/user/${me.id}`)}
              className="cursor-pointer relative group flex items-center"
            >
              <img 
                src={me.avatarUrl || "/default-avatar.png"} 
                alt={me.username} 
                className={`w-9 h-9 rounded-full object-cover ${pathname === `/user/${me.id}` ? 'ring-2 ring-blue-600 ring-offset-2' : 'border border-gray-200'}`}
              />
            </div>
          ) : (
            <button onClick={() => router.push('/login')} className="flex items-center gap-2 text-sm font-medium px-3 py-2 text-gray-700 hover:bg-gray-50 rounded-md">
              <LogIn size={20} /> Login
            </button>
          )}
        </div>
      </div>
    </nav>
  );
}