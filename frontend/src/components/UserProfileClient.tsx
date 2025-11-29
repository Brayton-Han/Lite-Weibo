'use client';

import { useEffect, useState } from 'react';
import api from '@/lib/api';
import { ApiResponse, User, Post } from '@/types';
import { useRouter } from 'next/navigation';
import { toast } from 'react-hot-toast';
// 1. 引入 UserCheck 图标用于 Friends
import { Layout, Users, Heart, UserPlus, UserMinus, UserCheck } from 'lucide-react'; // [!code ++]
import PostCard from './PostCard'; 
import CreatePostWidget from './CreatePostWidget'; 
import Navbar from '@/components/Navbar'; 

interface UserProfileClientProps {
  viewedUserId: string; 
  // 2. 在类型定义中添加 'friends'
  activeTab: 'profile' | 'following' | 'followers' | 'friends'; // [!code ++]
}

export default function UserProfileClient({ viewedUserId, activeTab }: UserProfileClientProps) {
  const router = useRouter();

  const [me, setMe] = useState<User | null>(null);
  const [viewedUser, setViewedUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [listData, setListData] = useState<User[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [posts, setPosts] = useState<Post[]>([]);

  const isOwnProfile = String(me?.id) === String(viewedUser?.id);

  useEffect(() => {
    const initData = async () => {
      setLoading(true);
      try {
        const myId = localStorage.getItem('userId');
        const fetchViewedUser = api.get<ApiResponse<User>>(`/user/${viewedUserId}`);
        const fetchMe = myId ? api.get<ApiResponse<User>>(`/user/${myId}`) : Promise.resolve(null);
        const fetchPosts = api.get<ApiResponse<Post[]>>(`/user/${viewedUserId}/posts`);

        const [userRes, meRes, postsRes] = await Promise.allSettled([fetchViewedUser, fetchMe, fetchPosts]);

        if (userRes.status === 'fulfilled' && userRes.value.data.code === 0) {
          setViewedUser(userRes.value.data.data);
        } else {
          toast.error('Failed to load user profile');
        }

        if (meRes.status === 'fulfilled' && meRes.value) {
           const res = meRes.value; 
           if (res && res.data && res.data.code === 0) {
             setMe(res.data.data);
           }
        }

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

  // 3. 修改列表获取逻辑，增加 friends 处理
  useEffect(() => {
    if (activeTab === 'profile') return;

    const fetchList = async () => {
      setListLoading(true);
      try {
        // [!code ++] 根据 activeTab 动态决定 URL
        let url = '';
        if (activeTab === 'following') url = `/user/${viewedUserId}/following`;
        else if (activeTab === 'followers') url = `/user/${viewedUserId}/followers`;
        else if (activeTab === 'friends') url = `/user/${viewedUserId}/friends`; // 新增接口调用
          
        if (url) {
          const res = await api.get<ApiResponse<User[]>>(url);
          if (res.data.code === 0) {
            setListData(res.data.data);
          }
        }
      } catch (error) {
        toast.error('Failed to fetch list');
      } finally {
        setListLoading(false);
      }
    };

    if (viewedUserId) fetchList();
  }, [activeTab, viewedUserId]);

  // 4. 修改侧边栏点击处理
  const handleMyNav = (tab: 'profile' | 'following' | 'followers' | 'friends') => { // [!code ++]
    if (!me) {
      toast.error("Please login first");
      router.push('/login');
      return;
    }
    const baseUrl = `/user/${me.id}`;
    if (tab === 'profile') router.push(baseUrl);
    if (tab === 'following') router.push(`${baseUrl}/following`);
    if (tab === 'followers') router.push(`${baseUrl}/followers`);
    if (tab === 'friends') router.push(`${baseUrl}/friends`); // [!code ++]
  };

  const handleFollowToggle = async () => {
    if (!viewedUser) return;
    
    // 获取当前状态
    const isFollowing = (viewedUser as any).following; 
    const isFollowedByTarget = (viewedUser as any).followed; // 获取对方是否关注了我
    
    const url = `/follow/${viewedUserId}`;
    
    try {
      const res = isFollowing 
        ? await api.delete(url)
        : await api.post(url);

      if (res.data.code === 0) {
        toast.success(isFollowing ? 'Unfollowed' : 'Followed');
        
        setViewedUser(prev => {
          if (!prev) return null;
          
          // 计算好友数量的变化量
          // 只有当对方也关注了我(isFollowedByTarget为true)时，我的关注操作才会改变好友状态
          // isFollowing 为 true (代表正在取消关注) -> 减少好友
          // isFollowing 为 false (代表正在关注) -> 增加好友
          const friendChange = isFollowedByTarget ? (isFollowing ? -1 : 1) : 0;

          return {
            ...prev,
            following: !isFollowing,
            followerCount: prev.followerCount + (isFollowing ? -1 : 1),
            // [!code ++] 动态更新 friendCount
            friendCount: prev.friendCount + friendChange 
          } as any; // 保持原有类型断言风格
        });
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

  const handlePostCreated = (newPost: Post) => {
    setPosts([newPost, ...posts]);
  };

  const handlePostDeleted = (postId: number) => {
    setPosts(posts.filter(p => p.id !== postId));
  };

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

  const renderProfileContent = () => {
    return (
      <div className="space-y-6">
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
                {/* Following Count */}
                <div 
                  className="cursor-pointer hover:text-blue-600 transition-colors flex items-baseline gap-1" 
                  onClick={() => router.push(`/user/${viewedUserId}/following`)}
                >
                  <span className="text-lg font-bold text-gray-900">{viewedUser!.followCount}</span>
                  <span className="text-sm text-gray-500">Following</span>
                </div>
                {/* Followers Count */}
                <div 
                  className="cursor-pointer hover:text-blue-600 transition-colors flex items-baseline gap-1"
                  onClick={() => router.push(`/user/${viewedUserId}/followers`)}
                >
                  <span className="text-lg font-bold text-gray-900">{viewedUser!.followerCount}</span>
                  <span className="text-sm text-gray-500">Followers</span>
                </div>
                {/* Friends Count (New) */}
                {/* 5. [!code ++] 新增 Friends 数量显示 */}
                <div 
                  className="cursor-pointer hover:text-blue-600 transition-colors flex items-baseline gap-1"
                  onClick={() => router.push(`/user/${viewedUserId}/friends`)}
                >
                  <span className="text-lg font-bold text-gray-900">{viewedUser!.friendCount}</span>
                  <span className="text-sm text-gray-500">Friends</span>
                </div>
              </div>
            </div>

            <div className="mt-4 flex items-center gap-2 border-t border-gray-100 pt-4">
              <img src="/file.svg" className="w-3 h-3" alt="icon" />
              <p className="text-gray-600 text-sm">{viewedUser!.bio || "No bio available."}</p>
            </div>
          </div>
        </div>

        {isOwnProfile && (
          <CreatePostWidget onPostCreated={handlePostCreated} />
        )}

        <div>
          <h3 className="text-lg font-bold text-gray-900 mb-4 px-1">Posts ({posts.length})</h3>
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

  // 6. 辅助函数：获取列表标题
  const getListTitle = () => { // [!code ++]
    if (activeTab === 'following') return 'Following';
    if (activeTab === 'followers') return 'Followers';
    if (activeTab === 'friends') return 'Friends';
    return '';
  };

  return (
    <>
      <Navbar />

      <div className="min-h-screen bg-gray-50 pt-20 pb-10">
        
        {loading ? (
           <div className="p-10 text-center">Loading profile...</div>
        ) : !viewedUser ? (
           <div className="p-10 text-center">User not found</div>
        ) : (
          <div className="max-w-6xl mx-auto px-4 flex flex-col md:flex-row gap-6">
            
            <aside className="w-full md:w-64 flex-shrink-0">
              <div className="bg-white rounded-xl shadow p-4 sticky top-24">
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
                  {/* 7. [!code ++] 侧边栏增加 My Friends */}
                  <button onClick={() => handleMyNav('friends')} className={getSidebarItemClass('friends')}>
                    <UserCheck size={18} className="mr-3" />
                    My Friends
                  </button>
                </nav>
              </div>
            </aside>

            <main className="w-full max-w-2xl">
              {activeTab === 'profile' ? (
                renderProfileContent()
              ) : (
                <div className="bg-white rounded-xl shadow overflow-hidden min-h-[400px] animate-in fade-in duration-300">
                  <div className="px-6 py-4 border-b border-gray-100">
                    <h3 className="text-lg font-bold text-gray-900">
                      {getListTitle()} {/* [!code ++] 使用动态标题 */}
                    </h3>
                  </div>
                  {renderUserList()}
                </div>
              )}
            </main>
          </div>
        )}
      </div>
    </>
  );
}