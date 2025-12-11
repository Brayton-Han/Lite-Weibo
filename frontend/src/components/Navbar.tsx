'use client';

import Link from 'next/link';
import { useRouter, usePathname } from 'next/navigation';
import { Search, Home, LogIn, Bell } from 'lucide-react'; // 1. 引入 Bell
import { useEffect, useState, useRef } from 'react';
import api from '@/lib/api';
import { ApiResponse, User } from '@/types';

// WebSocket 相关引入
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

export default function Navbar() {
  const router = useRouter();
  const pathname = usePathname();
  const [me, setMe] = useState<User | null>(null);
  
  // 2. 新增通知数量状态
  const [notificationCount, setNotificationCount] = useState(0);
  
  // 用于持有 stomp client 实例，防止重复创建
  const stompClientRef = useRef<Client | null>(null);

  useEffect(() => {
    // ... 原有的获取用户信息逻辑 ...
    const fetchMe = async () => {
      const myId = localStorage.getItem('userId');
      const token = localStorage.getItem('token');
      
      if (!myId || !token) {
        setMe(null);
        return;
      }

      try {
        const res = await api.get<ApiResponse<User>>(`/user/${myId}`);
        if (res.data.code === 0) {
          setMe(res.data.data);
        }
      } catch (error) {
        console.error("Failed to fetch user info for navbar", error);
      }
    };

    fetchMe();

    const handleUserUpdate = () => {
      fetchMe();
    };
    
    window.addEventListener('user-profile-updated', handleUserUpdate);
    window.addEventListener('storage', handleUserUpdate);

    return () => {
      window.removeEventListener('user-profile-updated', handleUserUpdate);
      window.removeEventListener('storage', handleUserUpdate);
    };
  }, []);

  // 3. WebSocket 连接逻辑 (当 me 存在时触发)
  useEffect(() => {
    // 如果没有用户信息，或者已经连接了，就不处理
    if (!me) return;

    // 获取 Token 用于鉴权 (假设后端 WebSocket 握手需要 Token)
    const token = localStorage.getItem('token');
    const wsUrl = `ws://localhost:8080/ws?token=${token}`;
    
    // 初始化 STOMP 客户端
    const client = new Client({
      brokerURL: wsUrl, 
      // connectHeaders is for the STOMP CONNECT frame, not the HTTP handshake.
      // You can keep it if you want double verification, but the handshake needs the URL param.
      connectHeaders: {
        Authorization: `Bearer ${token}`, 
      },
      debug: (str) => console.log(str),
      onConnect: () => {
        console.log("WebSocket Connected!");

        client.subscribe("/user/queue/follow", msg => {
          setNotificationCount(prev => prev + 1);
        });

        client.subscribe("/user/queue/like", msg => {
          setNotificationCount(prev => prev + 1);
        });

        client.subscribe("/user/queue/comment", msg => {
          setNotificationCount(prev => prev + 1);
        });
      },
    });
    client.activate();
    stompClientRef.current = client;

    // 清理函数：组件卸载或用户登出时断开连接
    return () => {
      if (stompClientRef.current) {
        stompClientRef.current.deactivate();
      }
    };
  }, [me]); // 依赖 me，确保只有登录后才连接

  const getIconClass = (targetPath: string) => {
    const isActive = pathname === targetPath;
    const baseClass = "transition-all duration-200 p-2 rounded-full relative"; // Added relative
    if (isActive) {
      return `${baseClass} bg-blue-100 text-blue-700 shadow-sm`;
    } else {
      return `${baseClass} text-gray-500 hover:bg-gray-100 hover:text-blue-600`;
    }
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

          <button onClick={() => router.push('/')} className={getIconClass('/')} title="Home">
            <Home size={24} strokeWidth={pathname === '/' ? 2.5 : 2} />
          </button>

          {/* 4. 新增：通知铃铛图标 */}
          {me && (
            <button 
              onClick={() => {
                 // 暂时不做跳转，或者你可以简单的清零： setNotificationCount(0);
                 console.log("Notification clicked");
              }} 
              className={getIconClass('/notifications')} // 假设路径，仅用于样式匹配
              title="Notifications"
            >
              <Bell size={24} strokeWidth={2} />
              
              {/* 小红点逻辑 */}
              {notificationCount > 0 && (
                <span className="absolute top-1 right-1 flex h-4 w-4 items-center justify-center rounded-full bg-red-500 text-[10px] font-bold text-white ring-2 ring-white">
                  {notificationCount > 99 ? '99+' : notificationCount}
                </span>
              )}
            </button>
          )}

          {me ? (
            <div 
              onClick={() => router.push(`/user/${me.id}`)}
              className="cursor-pointer relative group flex items-center"
              title="My Profile"
            >
              <img 
                src={me.avatarUrl || "/default-avatar.png"} 
                alt={me.username} 
                className={`w-9 h-9 rounded-full object-cover transition-all duration-200 ${
                  pathname === `/user/${me.id}`
                    ? 'ring-2 ring-blue-600 ring-offset-2'
                    : 'border border-gray-200 group-hover:border-blue-400'
                }`}
              />
            </div>
          ) : (
            <button
              onClick={() => router.push('/login')}
              className={`flex items-center gap-2 text-sm font-medium px-3 py-2 rounded-md transition-colors ${
                pathname === '/login' 
                  ? 'bg-blue-50 text-blue-700' 
                  : 'text-gray-700 hover:text-blue-600 hover:bg-gray-50'
              }`}
            >
              <LogIn size={20} />
              <span>Login</span>
            </button>
          )}
        </div>
      </div>
    </nav>
  );
}