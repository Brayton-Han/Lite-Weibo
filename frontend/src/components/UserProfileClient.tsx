'use client';

import { useEffect, useState } from 'react';
import api from '@/lib/api';
import { ApiResponse, User, Post } from '@/types';
import { useRouter } from 'next/navigation';
import { toast } from 'react-hot-toast';
import { Layout, Users, Heart, UserPlus, UserMinus } from 'lucide-react';
import PostCard from './PostCard'; // 確保路徑正確
import CreatePostWidget from './CreatePostWidget'; // 確保路徑正確

interface UserProfileClientProps {
  viewedUserId: string; // 当前页面 URL 对应的 ID
  activeTab: 'profile' | 'following' | 'followers'; // 当前页面类型
}

export default function UserProfileClient({ viewedUserId, activeTab }: UserProfileClientProps) {
  const router = useRouter();

  // --- State: 当前登录用户 (Me) ---
  const [me, setMe] = useState<User | null>(null);
  
  // --- State: 当前查看的用户 (Viewed User) ---
  const [viewedUser, setViewedUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  
  // --- State: 列表数据 (Following/Followers) ---
  const [listData, setListData] = useState<User[]>([]);
  const [listLoading, setListLoading] = useState(false);

  // --- State: 帖子数据 (Posts) ---
  const [posts, setPosts] = useState<Post[]>([]);
  // 如果需要单独的 loading 状态可以使用，这里暂时复用整体 loading 或不做处理

  // 判断当前查看的是否是自己的主页
  const isOwnProfile = String(me?.id) === String(viewedUser?.id);

  // 1. 初始化：获取 Me, Viewed User 和 Posts
  useEffect(() => {
    const initData = async () => {
      setLoading(true);
      try {
        // 从 localStorage 获取当前登录用户的 ID
        const myId = localStorage.getItem('userId');

        // 并行请求：
        // 1. 获取“当前查看用户”的信息
        // 2. 获取“我”的信息 (如果已登录)
        // 3. 获取“当前查看用户”的帖子列表 [新增]
        const fetchViewedUser = api.get<ApiResponse<User>>(`/user/${viewedUserId}`);
        const fetchMe = myId ? api.get<ApiResponse<User>>(`/user/${myId}`) : Promise.resolve(null);
        const fetchPosts = api.get<ApiResponse<Post[]>>(`/user/${viewedUserId}/posts`);

        const [userRes, meRes, postsRes] = await Promise.allSettled([fetchViewedUser, fetchMe, fetchPosts]);

        // 处理 Viewed User
        if (userRes.status === 'fulfilled' && userRes.value.data.code === 0) {
          setViewedUser(userRes.value.data.data);
        } else {
          toast.error('Failed to load user profile');
        }

        // 处理 Me
        if (meRes.status === 'fulfilled' && meRes.value) {
           const res = meRes.value; 
           if (res && res.data && res.data.code === 0) {
             setMe(res.data.data);
           }
        }

        // 处理 Posts [新增]
        if (postsRes.status === 'fulfilled' && postsRes.value.data.code === 0) {
          setPosts(postsRes.value.data.data);
        }

      } catch (error) {
        console.error(error);
        toast.error('Network error');
      } finally {
        setLoading(false);
      }
    };

    initData();
  }, [viewedUserId]);

  // 2. 根据 Tab 加载列表数据 (Following/Followers)
  useEffect(() => {
    if (activeTab === 'profile') return;

    const fetchList = async () => {
      setListLoading(true);
      try {
        const url = activeTab === 'following' 
          ? `/user/${viewedUserId}/following` 
          : `/user/${viewedUserId}/followers`;
          
        const res = await api.get<ApiResponse<User[]>>(url);
        if (res.data.code === 0) {
          setListData(res.data.data);
        }
      } catch (error) {
        toast.error('Failed to fetch list');
      } finally {
        setListLoading(false);
      }
    };

    if (viewedUserId) fetchList();
  }, [activeTab, viewedUserId]);

  // --- Handlers: Sidebar Navigation ---
  const handleMyNav = (tab: 'profile' | 'following' | 'followers') => {
    if (!me) {
      toast.error("Please login first");
      router.push('/login');
      return;
    }
    const baseUrl = `/user/${me.id}`;
    if (tab === 'profile') router.push(baseUrl);
    if (tab === 'following') router.push(`${baseUrl}/following`);
    if (tab === 'followers') router.push(`${baseUrl}/followers`);
  };

  // --- Handlers: Profile Actions ---
  const handleFollowToggle = async () => {
    if (!viewedUser) return;
    const isFollowing = (viewedUser as any).following; 
    const url = `/follow/${viewedUserId}`;
    
    try {
      const res = isFollowing 
        ? await api.delete(url)
        : await api.post(url);

      if (res.data.code === 0) {
        toast.success(isFollowing ? 'Unfollowed' : 'Followed');
        setViewedUser(prev => prev ? ({
          ...prev,
          following: !isFollowing,
          followerCount: prev.followerCount + (isFollowing ? -1 : 1)
        } as any) : null);
      } else {
        toast.error(res.data.message);
      }
    } catch (error) {
      toast.error('Action failed');
    }
  };

  const handleLogout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('userId');
    router.push('/login');
  };

  const navigateToSettings = () => {
    if (me) router.push('/set');
  };

  // --- Handlers: Post Actions [新增] ---
  const handlePostCreated = (newPost: Post) => {
    setPosts([newPost, ...posts]);
  };

  const handlePostDeleted = (postId: number) => {
    setPosts(posts.filter(p => p.id !== postId));
  };

  // --- Render Helpers ---
  const getSidebarItemClass = (targetTab: string) => {
    const isActive = isOwnProfile && activeTab === targetTab;
    const baseClass = "flex items-center w-full px-4 py-3 text-sm font-medium rounded-lg transition-colors duration-200 cursor-pointer mb-1";
    return isActive 
      ? `${baseClass} bg-blue-50 text-blue-700 border-l-4 border-blue-600` 
      : `${baseClass} text-gray-600 hover:bg-gray-100 hover:text-gray-900`;
  };

  const renderUserList = () => {
    if (listLoading) return <div className="p-10 text-center text-gray-500">Loading list...</div>;
    if (listData.length === 0) return <div className="p-10 text-center text-gray-500">No users found.</div>;

    return (
      <div className="divide-y divide-gray-100">
        {listData.map((item) => (
          <div 
            key={item.id} 
            className="flex items-center justify-between p-4 hover:bg-gray-50 transition-colors cursor-pointer"
            onClick={() => router.push(`/user/${item.id}`)}
          >
            <div className="flex items-center space-x-4">
              <img 
                src={item.avatarUrl || "/default-avatar.png"} 
                alt={item.username} 
                className="w-12 h-12 rounded-full object-cover" 
              />
              <div>
                <h4 className="text-sm font-semibold text-gray-900">{item.username}</h4>
                <p className="text-xs text-gray-500 line-clamp-1">{item.bio || 'No bio'}</p>
              </div>
            </div>
            <div className="text-xs text-gray-400">View</div>
          </div>
        ))}
      </div>
    );
  };

  // --- Render Profile Content (包含发帖框和帖子列表) ---
  const renderProfileContent = () => {
    return (
      <div className="space-y-6">
        {/* 1. 用户信息卡片 */}
        <div className="bg-white rounded-xl shadow overflow-hidden animate-in fade-in duration-300">
          <div className="h-32 bg-blue-600"></div>
          <div className="px-6 pb-6">
            <div className="relative flex justify-between items-end -mt-12 mb-6">
              <div className="relative">
                <img 
                  src={viewedUser!.avatarUrl || "/default-avatar.png"} 
                  alt={viewedUser!.username}
                  className="w-24 h-24 rounded-full border-4 border-white bg-white object-cover"
                />
              </div>
              <div className="flex space-x-3">
                {isOwnProfile ? (
                  <>
                    <button onClick={navigateToSettings} className="px-4 py-2 text-sm text-gray-700 border border-gray-300 rounded hover:bg-gray-50">Edit Profile</button>
                    <button onClick={handleLogout} className="px-4 py-2 text-sm text-red-600 border border-red-200 rounded hover:bg-red-50">Log Out</button>
                  </>
                ) : (
                  <button 
                    onClick={handleFollowToggle}
                    className={`flex items-center px-4 py-2 rounded text-sm font-medium transition-colors ${
                      (viewedUser as any).following 
                        ? "bg-gray-100 text-gray-700 hover:bg-gray-200"
                        : "bg-blue-600 text-white hover:bg-blue-700"
                    }`}
                  >
                    {(viewedUser as any).following ? <><UserMinus size={16} className="mr-2"/> Unfollow</> : <><UserPlus size={16} className="mr-2"/> Follow</>}
                  </button>
                )}
              </div>
            </div>

            <div className="flex flex-col md:flex-row md:items-end justify-between gap-4">
              <div>
                <h1 className="text-2xl font-bold text-gray-900">{viewedUser!.username}</h1>
                <p className="text-sm text-gray-500">Joined: {viewedUser!.joinDate}</p>
              </div>
              
              <div className="flex items-center gap-6 pb-1">
                <div 
                  className="cursor-pointer hover:text-blue-600 transition-colors flex items-baseline gap-1" 
                  onClick={() => router.push(`/user/${viewedUserId}/following`)}
                >
                  <span className="text-lg font-bold text-gray-900">{viewedUser!.followCount}</span>
                  <span className="text-sm text-gray-500">Following</span>
                </div>
                <div 
                  className="cursor-pointer hover:text-blue-600 transition-colors flex items-baseline gap-1"
                  onClick={() => router.push(`/user/${viewedUserId}/followers`)}
                >
                  <span className="text-lg font-bold text-gray-900">{viewedUser!.followerCount}</span>
                  <span className="text-sm text-gray-500">Followers</span>
                </div>
              </div>
            </div>

            <div className="mt-4 flex items-center gap-2 border-t border-gray-100 pt-4">
              <img src="/file.svg" className="w-3 h-3" alt="icon" />
              <p className="text-gray-600 text-sm">{viewedUser!.bio || "No bio available."}</p>
            </div>
          </div>
        </div>

        {/* 2. 发帖框 (仅限本人) */}
        {isOwnProfile && (
          <CreatePostWidget onPostCreated={handlePostCreated} />
        )}

        {/* 3. 帖子列表 */}
        <div>
          <h3 className="text-lg font-bold text-gray-900 mb-4 px-1">Posts</h3>
          {posts.length === 0 ? (
            <div className="bg-white rounded-xl p-8 text-center text-gray-500 shadow">
              <p>No posts yet.</p>
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
      </div>
    );
  };

  // --- Main Render Logic ---
  if (loading) return <div className="p-10 text-center">Loading profile...</div>;
  if (!viewedUser) return <div className="p-10 text-center">User not found</div>;

  return (
    <div className="min-h-screen bg-gray-50 py-10">
      <div className="max-w-6xl mx-auto px-4 flex flex-col md:flex-row gap-6">
        
        {/* Sidebar */}
        <aside className="w-full md:w-64 flex-shrink-0">
          <div className="bg-white rounded-xl shadow p-4 sticky top-4">
            <h2 className="px-4 pb-2 text-xs font-semibold text-gray-400 uppercase tracking-wider">My Account</h2>
            <nav className="space-y-1">
              <button onClick={() => handleMyNav('profile')} className={getSidebarItemClass('profile')}>
                <Layout size={18} className="mr-3" />
                My Profile
              </button>
              <button onClick={() => handleMyNav('following')} className={getSidebarItemClass('following')}>
                <Users size={18} className="mr-3" />
                My Following
              </button>
              <button onClick={() => handleMyNav('followers')} className={getSidebarItemClass('followers')}>
                <Heart size={18} className="mr-3" />
                My Followers
              </button>
            </nav>
          </div>
        </aside>

        {/* Main Content */}
        <main className="w-full max-w-2xl">
          {activeTab === 'profile' ? (
            renderProfileContent()
          ) : (
            // List View Container
            <div className="bg-white rounded-xl shadow overflow-hidden min-h-[400px] animate-in fade-in duration-300">
              <div className="px-6 py-4 border-b border-gray-100">
                <h3 className="text-lg font-bold text-gray-900">
                  {activeTab === 'following' ? 'Following' : 'Followers'}
                </h3>
              </div>
              {renderUserList()}
            </div>
          )}
        </main>
      </div>
    </div>
  );
}