'use client';

import Link from 'next/link';
import { useRouter, usePathname, useSearchParams } from 'next/navigation';
import { Search, Home, LogIn, Bell, Heart, MessageCircle, UserPlus, X, Users } from 'lucide-react';
import { useEffect, useState, useRef } from 'react';
import api from '@/lib/api';
import { ApiResponse, User, NotificationCounts } from '@/types'; // 使用 index.ts 中的类型
import { Client } from '@stomp/stompjs';

export default function Navbar() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  
  // --- State ---
  const [me, setMe] = useState<User | null>(null);
  const [isSearchOpen, setIsSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const [hasNewPost, setHasNewPost] = useState(false);

  // 通知计数
  const [notifications, setNotifications] = useState<NotificationCounts>({
    follow: 0,
    like: 0,
    comment: 0
  });
  
  const totalNotifications = notifications.follow + notifications.like + notifications.comment;
  
  // --- Refs ---
  const stompClientRef = useRef<Client | null>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const searchContainerRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  // --- Init Data ---
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
      // 下拉菜单关闭逻辑
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsDropdownOpen(false);
      }
      // 搜索框收起逻辑
      if (searchContainerRef.current && !searchContainerRef.current.contains(event.target as Node)) {
        if (searchQuery === '') {
          setIsSearchOpen(false);
        }
      }
    };
    document.addEventListener('mousedown', handleClickOutside);

    return () => {
      window.removeEventListener('user-profile-updated', handleUserUpdate);
      window.removeEventListener('storage', handleUserUpdate);
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [searchQuery]);

  // --- WebSocket Logic ---
  useEffect(() => {
    if (!me) return;
    const token = localStorage.getItem('token');
    // 注意：这里硬编码了 localhost，生产环境可能需要换成环境变量
    const wsUrl = `ws://localhost:8080/ws?token=${token}`;
    
    const client = new Client({
      brokerURL: wsUrl, 
      connectHeaders: { Authorization: `Bearer ${token}` },
      onConnect: () => {
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
          setHasNewPost(true);
        });
      },
    });
    client.activate();
    stompClientRef.current = client;
    return () => { stompClientRef.current?.deactivate(); }
  }, [me]); 

  // --- Search Handlers ---
  useEffect(() => {
    if (pathname === '/search') {
      const q = searchParams.get('q');
      if (q) {
        setSearchQuery(q);
        setIsSearchOpen(true);
      }
    }
  }, [pathname, searchParams]);

  const handleSearchSubmit = (e?: React.FormEvent) => {
    e?.preventDefault();
    if (!searchQuery.trim()) {
        setIsSearchOpen(true);
        searchInputRef.current?.focus();
        return;
    }
    router.push(`/search?q=${encodeURIComponent(searchQuery)}`);
  };

  const toggleSearch = () => {
    if (isSearchOpen && searchQuery.trim()) {
        handleSearchSubmit();
    } else {
        setIsSearchOpen(!isSearchOpen);
        if (!isSearchOpen) {
            setTimeout(() => searchInputRef.current?.focus(), 50);
        }
    }
  };

  const clearSearch = () => {
    setSearchQuery('');
    searchInputRef.current?.focus();
  };

  // --- Notification Handlers ---
  const handleNotificationClick = async (type: keyof NotificationCounts) => {
    setIsDropdownOpen(false);
    router.push(`/notifications?type=${type}`);
    if (notifications[type] > 0) {
      setNotifications(prev => ({ ...prev, [type]: 0 }));
      try {
        await api.post(`/notification/mark-read?type=${type}`);
      } catch (error) {
        console.error(`Failed to mark ${type} notifications as read`, error);
      }
    }
  };

  const handleHomeClick = () => {
    setHasNewPost(false); 
    router.push('/');
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
        
        {/* --- Left Side: Logo + Users + Search --- */}
        <div className="flex items-center gap-2">
          {/* 1. Logo */}
          <div className="flex items-center cursor-pointer mr-2" onClick={() => router.push('/')}>
            <span className="text-xl font-bold text-blue-600 tracking-tight">Lite Weibo</span>
          </div>

          {/* 2. Users Icon (位于搜索框左侧) */}
          <button 
            onClick={() => router.push('/users')} 
            className={getIconClass('/users')}
            title="Community"
          >
             <Users size={24} strokeWidth={pathname === '/users' ? 2.5 : 2} />
          </button>

          {/* 3. Search Bar (Expandable) */}
          <div 
            ref={searchContainerRef}
            className={`flex items-center rounded-full transition-all duration-300 ${
              isSearchOpen ? 'bg-gray-100 pl-3 pr-2 py-1' : 'bg-transparent'
            }`}
          >
             <button 
                onClick={toggleSearch} 
                className={`text-gray-500 hover:text-blue-600 transition-colors ${!isSearchOpen ? 'p-2 hover:bg-gray-100 rounded-full' : ''}`}
             >
               <Search size={isSearchOpen ? 18 : 24} strokeWidth={2} />
             </button>

             <form onSubmit={handleSearchSubmit} className="flex items-center">
               <input
                 ref={searchInputRef}
                 type="text"
                 value={searchQuery}
                 onChange={(e) => setSearchQuery(e.target.value)}
                 placeholder="Search posts..."
                 className={`bg-transparent border-none outline-none text-sm text-gray-700 transition-all duration-300 ease-in-out placeholder:text-gray-400 ${
                   isSearchOpen ? 'w-48 sm:w-64 ml-2' : 'w-0 ml-0'
                 }`}
                 style={{ opacity: isSearchOpen ? 1 : 0 }}
               />
             </form>

             {isSearchOpen && (
               <button 
                 onClick={searchQuery ? clearSearch : () => setIsSearchOpen(false)}
                 className="ml-1 text-gray-400 hover:text-gray-600 p-1 rounded-full hover:bg-gray-200"
               >
                 <X size={14} />
               </button>
             )}
          </div>
        </div>

        {/* --- Right Side: Home, Notifications, Profile --- */}
        <div className="flex items-center space-x-6">
          <button 
            onClick={handleHomeClick} 
            className={getIconClass('/')} 
            title="Home"
          >
            <div className="relative">
              <Home size={24} strokeWidth={pathname === '/' ? 2.5 : 2} />
              {hasNewPost && (
                <span className="absolute top-0 right-0 block h-2.5 w-2.5 rounded-full bg-red-500 ring-2 ring-white transform translate-x-1/2 -translate-y-1/2">
                </span>
              )}
            </div>
          </button>

          {/* Notifications Dropdown */}
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

              {isDropdownOpen && (
                <div className="absolute top-full right-0 mt-2 w-64 bg-white rounded-xl shadow-lg border border-gray-100 py-2 animate-in fade-in zoom-in-95 duration-200 origin-top-right z-50">
                  <div className="px-4 py-2 border-b border-gray-50 mb-1">
                    <span className="text-sm font-semibold text-gray-700">Notifications</span>
                  </div>

                  <div className="flex items-center justify-between px-4 py-3 hover:bg-gray-50 cursor-pointer transition-colors" onClick={() => handleNotificationClick('follow')}>
                    <div className="flex items-center gap-3"><div className="p-2 bg-blue-50 text-blue-600 rounded-full"><UserPlus size={18} /></div><span className="text-sm text-gray-600 font-medium">New Followers</span></div>
                    {notifications.follow > 0 && <span className="bg-blue-600 text-white text-xs font-bold px-2 py-0.5 rounded-full min-w-[20px] text-center">{notifications.follow}</span>}
                  </div>

                  <div className="flex items-center justify-between px-4 py-3 hover:bg-gray-50 cursor-pointer transition-colors" onClick={() => handleNotificationClick('like')}>
                    <div className="flex items-center gap-3"><div className="p-2 bg-pink-50 text-pink-500 rounded-full"><Heart size={18} /></div><span className="text-sm text-gray-600 font-medium">Likes</span></div>
                    {notifications.like > 0 && <span className="bg-pink-500 text-white text-xs font-bold px-2 py-0.5 rounded-full min-w-[20px] text-center">{notifications.like}</span>}
                  </div>

                  <div className="flex items-center justify-between px-4 py-3 hover:bg-gray-50 cursor-pointer transition-colors" onClick={() => handleNotificationClick('comment')}>
                    <div className="flex items-center gap-3"><div className="p-2 bg-green-50 text-green-600 rounded-full"><MessageCircle size={18} /></div><span className="text-sm text-gray-600 font-medium">Comments</span></div>
                    {notifications.comment > 0 && <span className="bg-green-600 text-white text-xs font-bold px-2 py-0.5 rounded-full min-w-[20px] text-center">{notifications.comment}</span>}
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
                className={`w-9 h-9 rounded-full object-cover bg-gray-100 ${pathname === `/user/${me.id}` ? 'ring-2 ring-blue-600 ring-offset-2' : 'border border-gray-200'}`}
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