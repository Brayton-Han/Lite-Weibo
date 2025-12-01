'use client';

import { useEffect, useState, useRef, useCallback } from 'react';
import api from '@/lib/api';
import { ApiResponse, User, Post } from '@/types';
import { useRouter } from 'next/navigation';
import { toast } from 'react-hot-toast';
import { Layout, Users, Heart, UserPlus, UserMinus, UserCheck, Loader2 } from 'lucide-react';
import PostCard from './PostCard'; 
import CreatePostWidget from './CreatePostWidget'; 
import Navbar from '@/components/Navbar'; 

interface UserProfileClientProps {
  viewedUserId: string; 
  activeTab: 'profile' | 'following' | 'followers' | 'friends';
}

const PAGE_SIZE = 10;

export default function UserProfileClient({ viewedUserId, activeTab }: UserProfileClientProps) {
  const router = useRouter();

  // --- Base Data ---
  const [me, setMe] = useState<User | null>(null);
  const [viewedUser, setViewedUser] = useState<User | null>(null);
  const [profileLoading, setProfileLoading] = useState(true); // 仅控制用户信息头部的加载

  // --- Posts Pagination State (Profile Tab) ---
  const [posts, setPosts] = useState<Post[]>([]);
  const [postsLoading, setPostsLoading] = useState(false); // 初始加载
  const [postsLoadingMore, setPostsLoadingMore] = useState(false); // 追加加载
  const [postsHasMore, setPostsHasMore] = useState(true);

  // --- User List Pagination State (Following/Followers/Friends Tabs) ---
  const [userList, setUserList] = useState<User[]>([]);
  const [listLoading, setListLoading] = useState(false); // 初始加载
  const [listLoadingMore, setListLoadingMore] = useState(false); // 追加加载
  const [listHasMore, setListHasMore] = useState(true);

  // --- Ref for Infinite Scroll ---
  const observerTarget = useRef<HTMLDivElement>(null);

  const isOwnProfile = String(me?.id) === String(viewedUser?.id);

  // 1. 初始化：获取 Me 和 ViewedUser 基本信息 (不含列表)
  useEffect(() => {
    const initBaseData = async () => {
      setProfileLoading(true);
      try {
        const myId = localStorage.getItem('userId');
        const fetchViewedUser = api.get<ApiResponse<User>>(`/user/${viewedUserId}`);
        const fetchMe = myId ? api.get<ApiResponse<User>>(`/user/${myId}`) : Promise.resolve(null);

        const [userRes, meRes] = await Promise.allSettled([fetchViewedUser, fetchMe]);

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
      } catch (error) {
        console.error(error);
      } finally {
        setProfileLoading(false);
      }
    };
    initBaseData();
  }, [viewedUserId]);

  // 2. Fetch Posts Logic (Cursor Pagination)
  const fetchPosts = useCallback(async (isInit: boolean, lastId?: number) => {
    if (isInit) setPostsLoading(true);
    else setPostsLoadingMore(true);

    try {
      const params = new URLSearchParams();
      params.append('size', PAGE_SIZE.toString());
      if (lastId) params.append('lastId', lastId.toString());

      const res = await api.get<ApiResponse<Post[]>>(`/user/${viewedUserId}/posts?${params.toString()}`);
      
      if (res.data.code === 0) {
        const newPosts = res.data.data || [];
        if (newPosts.length < PAGE_SIZE) setPostsHasMore(false);
        else setPostsHasMore(true);

        if (isInit) {
          setPosts(newPosts);
        } else {
          setPosts(prev => [...prev, ...newPosts]);
        }
      }
    } catch (e) {
      toast.error("Failed to load posts");
    } finally {
      if (isInit) setPostsLoading(false);
      else setPostsLoadingMore(false);
    }
  }, [viewedUserId]);

  // 3. Fetch User List Logic
  const fetchUserList = useCallback(async (isInit: boolean, lastId?: number) => {
    // 确定 URL
    let urlBase = '';
    if (activeTab === 'following') urlBase = `/user/${viewedUserId}/following`;
    else if (activeTab === 'followers') urlBase = `/user/${viewedUserId}/followers`;
    else if (activeTab === 'friends') urlBase = `/user/${viewedUserId}/friends`;
    
    if (!urlBase) return;

    if (isInit) setListLoading(true);
    else setListLoadingMore(true);

    try {
      const params = new URLSearchParams();

      // --- 修改开始：针对 Friends 列表的特殊处理 ---
      if (activeTab === 'friends') {
        // 如果是好友列表，设定一个很大的 size 以便一次性拉取所有数据
        // (前提是后端允许较大的 page size，或者后端在不传 size 时默认返回全部)
        params.append('size', '10000');
        // Friends 列表不传 lastId，因为是一次性获取
      } else {
        // 其他列表 (Following/Followers) 保持原有的分页逻辑
        params.append('size', PAGE_SIZE.toString());
        if (lastId) params.append('lastId', lastId.toString());
      }
      // --- 修改结束 ---

      const res = await api.get<ApiResponse<User[]>>(`${urlBase}?${params.toString()}`);

      if (res.data.code === 0) {
        const newUsers = res.data.data || [];

        // --- 修改开始：处理 HasMore ---
        if (activeTab === 'friends') {
          // 好友列表一次性加载完，不再需要“加载更多”
          setListHasMore(false);
        } else {
          // 其他列表保持原逻辑
          if (newUsers.length < PAGE_SIZE) setListHasMore(false);
          else setListHasMore(true);
        }
        // --- 修改结束 ---

        if (isInit) {
          setUserList(newUsers);
        } else {
          setUserList(prev => [...prev, ...newUsers]);
        }
      }
    } catch (e) {
      toast.error("Failed to load list");
    } finally {
      if (isInit) setListLoading(false);
      else setListLoadingMore(false);
    }
  }, [activeTab, viewedUserId]);

  // 4. 当 activeTab 是 profile 且 me 已加载后，再加载帖子
  useEffect(() => {
    if (activeTab !== "profile") return;
  if (me === null) return; // 等待登录用户信息先加载完成

    setPosts([]);
    setPostsHasMore(true);
    // 这里才是第一次真正加载 posts
    fetchPosts(true);
  }, [activeTab, me, fetchPosts]);

  // 添加在 fetchUserList 定义之后
  useEffect(() => {
    // 如果是 profile，由另一个 effect 处理；如果是列表页，在这里触发
    if (activeTab === 'profile') return;

    // 清空之前的列表，避免闪烁上一页的数据
    setUserList([]); 
    setListHasMore(true);
  
    // 触发初始加载
    fetchUserList(true);
  }, [activeTab, fetchUserList]);

  // 5. Unified Load More Handler
  const handleLoadMore = () => {
    if (activeTab === 'profile') {
      // Load more posts
      if (postsLoadingMore || !postsHasMore || posts.length === 0) return;
      const lastPost = posts[posts.length - 1];
      fetchPosts(false, lastPost.id);
    } else {
      // Load more users
      if (listLoadingMore || !listHasMore || userList.length === 0) return;
      const lastUser = userList[userList.length - 1];
      // 假设 User ID 是 number，如果是 string 需要根据后端要求传
      fetchUserList(false, Number(lastUser.id)); 
    }
  };

  // 6. Intersection Observer
  useEffect(() => {
    const observer = new IntersectionObserver(
      entries => {
        const isTargetIntersecting = entries[0].isIntersecting;
        
        if (activeTab === 'profile') {
          if (isTargetIntersecting && postsHasMore && !postsLoading && !postsLoadingMore) {
            handleLoadMore();
          }
        } else {
          if (isTargetIntersecting && listHasMore && !listLoading && !listLoadingMore) {
            handleLoadMore();
          }
        }
      },
      { threshold: 0.1 }
    );

    if (observerTarget.current) observer.observe(observerTarget.current);

    return () => {
      if (observerTarget.current) observer.unobserve(observerTarget.current);
    };
    // 依赖项需包含数据长度或状态变化
  }, [
    activeTab, 
    postsHasMore, postsLoading, postsLoadingMore, posts.length,
    listHasMore, listLoading, listLoadingMore, userList.length
  ]);


  // --- Event Handlers ---
  const handleMyNav = (tab: 'profile' | 'following' | 'followers' | 'friends') => {
    if (!me) {
      toast.error("Please login first");
      router.push('/login');
      return;
    }
    const baseUrl = `/user/${me.id}`;
    if (tab === 'profile') router.push(baseUrl);
    else router.push(`${baseUrl}/${tab}`);
  };

  const handleFollowToggle = async () => {
    if (!viewedUser) return;
    const isFollowing = (viewedUser as any).following; 
    const isFollowedByTarget = (viewedUser as any).followed;
    const url = `/follow/${viewedUserId}`;
    
    try {
      const res = isFollowing ? await api.delete(url) : await api.post(url);
      if (res.data.code === 0) {
        toast.success(isFollowing ? 'Unfollowed' : 'Followed');
        setViewedUser(prev => {
          if (!prev) return null;
          const friendChange = isFollowedByTarget ? (isFollowing ? -1 : 1) : 0;
          return {
            ...prev,
            following: !isFollowing,
            followerCount: prev.followerCount + (isFollowing ? -1 : 1),
            friendCount: prev.friendCount + friendChange 
          } as any;
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
    // 1. 更新列表
    setPosts([newPost, ...posts]);
  
    // 2. 更新总数 (+1)
    setViewedUser(prev => {
      if (!prev) return null;
      return {
        ...prev,
        postCount: (prev.postCount || 0) + 1
      };
    });
  };

  const handlePostDeleted = (postId: number) => {
    // 1. 更新列表
    setPosts(posts.filter(p => p.id !== postId));

    // 2. 更新总数 (-1)
    setViewedUser(prev => {
      if (!prev) return null;
      return {
        ...prev,
        postCount: Math.max(0, (prev.postCount || 0) - 1)
      };
    });
  };
  
  const getSidebarItemClass = (targetTab: string) => {
    const isActive = isOwnProfile && activeTab === targetTab;
    const baseClass = "flex items-center w-full px-4 py-3 text-sm font-medium rounded-lg transition-colors duration-200 cursor-pointer mb-1";
    return isActive 
      ? `${baseClass} bg-blue-50 text-blue-700 border-l-4 border-blue-600` 
      : `${baseClass} text-gray-600 hover:bg-gray-100 hover:text-gray-900`;
  };

  const getListTitle = () => {
    if (activeTab === 'following') return 'Following';
    if (activeTab === 'followers') return 'Followers';
    if (activeTab === 'friends') return 'Friends';
    return '';
  };

  // --- Render Helpers ---

  const renderUserList = () => {
    if (listLoading) return <div className="p-10 text-center flex justify-center"><Loader2 className="animate-spin text-gray-500" /></div>;
    if (userList.length === 0) return <div className="p-10 text-center text-gray-500">No users found.</div>;

    return (
      <div className="divide-y divide-gray-100">
        {userList.map((item) => (
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
        {/* User List Sentinel */}
        <div ref={observerTarget} className="py-4 flex justify-center h-16">
           {listLoadingMore && <Loader2 className="animate-spin text-gray-400" size={20}/>}
           {!listHasMore && userList.length > 0 && <span className="text-xs text-gray-300">End of list</span>}
        </div>
      </div>
    );
  };

  const renderProfileContent = () => {
    return (
      <div className="space-y-6">
        <div className="bg-white rounded-xl shadow overflow-hidden animate-in fade-in duration-300">
          <div className="h-32 bg-blue-600"></div>
          <div className="px-6 pb-6">
            {/* Header / Avatar Section */}
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

            {/* User Stats & Info */}
            <div className="flex flex-col md:flex-row md:items-end justify-between gap-4">
              <div>
                <h1 className="text-2xl font-bold text-gray-900">{viewedUser!.username}</h1>
                <p className="text-sm text-gray-500">Joined: {viewedUser!.joinDate}</p>
              </div>
              
              <div className="flex items-center gap-6 pb-1">
                <div className="cursor-pointer hover:text-blue-600 transition-colors flex items-baseline gap-1" onClick={() => router.push(`/user/${viewedUserId}/following`)}>
                  <span className="text-lg font-bold text-gray-900">{viewedUser!.followCount}</span>
                  <span className="text-sm text-gray-500">Following</span>
                </div>
                <div className="cursor-pointer hover:text-blue-600 transition-colors flex items-baseline gap-1" onClick={() => router.push(`/user/${viewedUserId}/followers`)}>
                  <span className="text-lg font-bold text-gray-900">{viewedUser!.followerCount}</span>
                  <span className="text-sm text-gray-500">Followers</span>
                </div>
                <div className="cursor-pointer hover:text-blue-600 transition-colors flex items-baseline gap-1" onClick={() => router.push(`/user/${viewedUserId}/friends`)}>
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

        {isOwnProfile && <CreatePostWidget onPostCreated={handlePostCreated} />}

        {/* Posts List */}
        <div>
          <h3 className="text-lg font-bold text-gray-900 mb-4 px-1">
            Posts ({viewedUser?.postCount ?? 0})
          </h3>
          {postsLoading ? (
             <div className="p-10 text-center flex justify-center"><Loader2 className="animate-spin text-gray-500" /></div>
          ) : posts.length === 0 ? (
            <div className="bg-white rounded-xl p-8 text-center text-gray-500 shadow">
              <p>No posts yet.</p>
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
              {/* Post List Sentinel */}
              <div ref={observerTarget} className="py-4 flex justify-center h-16">
                 {postsLoadingMore && <Loader2 className="animate-spin text-gray-400" size={20}/>}
                 {!postsHasMore && <span className="text-xs text-gray-300">No more posts</span>}
              </div>
            </>
          )}
        </div>
      </div>
    );
  };


  // --- Main Render ---

  return (
    <>
      <Navbar />

      <div className="min-h-screen bg-gray-50 pt-20 pb-10">
        
        {profileLoading ? (
           <div className="p-10 text-center flex justify-center items-center h-64">
             <Loader2 className="animate-spin mr-2 text-blue-600" /> Loading profile...
           </div>
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
                      {getListTitle()}
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