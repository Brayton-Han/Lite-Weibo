'use client';

import { useEffect, useState, useRef, useCallback, ChangeEvent } from 'react';
import api from '@/lib/api';
import { convertToJpegIfNeeded } from '@/lib/imageUtils';
import { ApiResponse, User, Post, LikedPostsResponse } from '@/types';
import { useRouter } from 'next/navigation';
import { toast } from 'react-hot-toast';
import { Layout, Users, Heart, UserPlus, UserMinus, UserCheck, Loader2, Camera, ThumbsUp } from 'lucide-react';
import PostCard from './PostCard'; 
import CreatePostWidget from './CreatePostWidget'; 
import Navbar from '@/components/Navbar';

interface UserProfileClientProps {
  viewedUserId: string; 
  // 更新 activeTab 类型定义
  activeTab: 'profile' | 'following' | 'followers' | 'friends' | 'liked';
}

const PAGE_SIZE = 10;

export default function UserProfileClient({ viewedUserId, activeTab }: UserProfileClientProps) {
  const router = useRouter();

  // --- Base Data ---
  const [me, setMe] = useState<User | null>(null);
  const [viewedUser, setViewedUser] = useState<User | null>(null);
  const [profileLoading, setProfileLoading] = useState(true);

  // --- Avatar Upload State ---
  const [isUploadingAvatar, setIsUploadingAvatar] = useState(false);
  const avatarInputRef = useRef<HTMLInputElement>(null);

  // --- Posts Pagination State (My Posts) ---
  const [posts, setPosts] = useState<Post[]>([]);
  const [postsLoading, setPostsLoading] = useState(false);
  const [postsLoadingMore, setPostsLoadingMore] = useState(false);
  const [postsHasMore, setPostsHasMore] = useState(true);

  // --- Liked Posts Pagination State (NEW) ---
  const [likedPosts, setLikedPosts] = useState<Post[]>([]);
  const [likedLoading, setLikedLoading] = useState(false);
  const [likedLoadingMore, setLikedLoadingMore] = useState(false);
  const [likedHasMore, setLikedHasMore] = useState(true);
  const [likedCursor, setLikedCursor] = useState<number | null>(null);

  // --- User List Pagination State ---
  const [userList, setUserList] = useState<User[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [listLoadingMore, setListLoadingMore] = useState(false);
  const [listHasMore, setListHasMore] = useState(true);

  const observerTarget = useRef<HTMLDivElement>(null);

  const isOwnProfile = String(me?.id) === String(viewedUser?.id);

  // 1. 初始化数据
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

  // 2. 获取个人帖子逻辑
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
        setPostsHasMore(newPosts.length >= PAGE_SIZE);

        if (isInit) setPosts(newPosts);
        else setPosts(prev => [...prev, ...newPosts]);
      }
    } catch (e) {
      toast.error("Failed to load posts");
    } finally {
      if (isInit) setPostsLoading(false);
      else setPostsLoadingMore(false);
    }
  }, [viewedUserId]);

  // 3. 获取 Liked 帖子逻辑 (NEW)
  const fetchLikedPosts = useCallback(async (isInit: boolean, cursor?: number) => {
    if (isInit) setLikedLoading(true);
    else setLikedLoadingMore(true);

    try {
      const params = new URLSearchParams();
      params.append('size', PAGE_SIZE.toString());
      // 如果有 cursor 则传参
      if (cursor !== undefined && cursor !== null) {
        params.append('cursor', cursor.toString());
      }

      const res = await api.get<ApiResponse<LikedPostsResponse>>(`/user/${viewedUserId}/liked?${params.toString()}`);

      if (res.data.code === 0) {
        const { posts: newPosts, nextCursor } = res.data.data;
        
        // 如果返回数量小于 PAGE_SIZE，说明没有更多了
        setLikedHasMore(newPosts.length >= PAGE_SIZE);
        setLikedCursor(nextCursor);

        if (isInit) setLikedPosts(newPosts);
        else setLikedPosts(prev => [...prev, ...newPosts]);
      }
    } catch (e) {
      toast.error("Failed to load liked posts");
    } finally {
      if (isInit) setLikedLoading(false);
      else setLikedLoadingMore(false);
    }
  }, [viewedUserId]);

  // 4. 获取用户列表逻辑
  const fetchUserList = useCallback(async (isInit: boolean, lastId?: number) => {
    let urlBase = '';
    if (activeTab === 'following') urlBase = `/user/${viewedUserId}/following`;
    else if (activeTab === 'followers') urlBase = `/user/${viewedUserId}/followers`;
    else if (activeTab === 'friends') urlBase = `/user/${viewedUserId}/friends`;
    
    if (!urlBase) return;

    if (isInit) setListLoading(true);
    else setListLoadingMore(true);

    try {
      const params = new URLSearchParams();
      if (activeTab === 'friends') {
        params.append('size', '10000');
      } else {
        params.append('size', PAGE_SIZE.toString());
        if (lastId) params.append('lastId', lastId.toString());
      }

      const res = await api.get<ApiResponse<User[]>>(`${urlBase}?${params.toString()}`);

      if (res.data.code === 0) {
        const newUsers = res.data.data || [];
        
        if (activeTab === 'friends') setListHasMore(false);
        else setListHasMore(newUsers.length >= PAGE_SIZE);

        if (isInit) setUserList(newUsers);
        else setUserList(prev => [...prev, ...newUsers]);
      }
    } catch (e) {
      toast.error("Failed to load list");
    } finally {
      if (isInit) setListLoading(false);
      else setListLoadingMore(false);
    }
  }, [activeTab, viewedUserId]);

  // 5. Tab 切换触发逻辑
  // Profile Tab
  useEffect(() => {
    if (activeTab !== "profile") return;
    if (me === null) return;
    setPosts([]);
    setPostsHasMore(true);
    fetchPosts(true);
  }, [activeTab, me, fetchPosts]);

  // Liked Tab (NEW)
  useEffect(() => {
    if (activeTab !== "liked") return;
    setLikedPosts([]);
    setLikedHasMore(true);
    setLikedCursor(null); // 重置 cursor
    fetchLikedPosts(true);
  }, [activeTab, fetchLikedPosts]);

  // User List Tabs
  useEffect(() => {
    if (activeTab === 'profile' || activeTab === 'liked') return;
    setUserList([]); 
    setListHasMore(true);
    fetchUserList(true);
  }, [activeTab, fetchUserList]);

  // 6. 加载更多逻辑
  const handleLoadMore = () => {
    if (activeTab === 'profile') {
      if (postsLoadingMore || !postsHasMore || posts.length === 0) return;
      fetchPosts(false, posts[posts.length - 1].id);
    } else if (activeTab === 'liked') {
      // 只有当有 cursor 且有更多数据时才加载
      if (likedLoadingMore || !likedHasMore || likedPosts.length === 0 || likedCursor === null) return;
      fetchLikedPosts(false, likedCursor);
    } else {
      if (listLoadingMore || !listHasMore || userList.length === 0) return;
      fetchUserList(false, Number(userList[userList.length - 1].id)); 
    }
  };

  // 7. 滚动监听
  useEffect(() => {
    const observer = new IntersectionObserver(
      entries => {
        if (entries[0].isIntersecting) {
            if (activeTab === 'profile' && postsHasMore && !postsLoading && !postsLoadingMore) handleLoadMore();
            else if (activeTab === 'liked' && likedHasMore && !likedLoading && !likedLoadingMore) handleLoadMore();
            else if (['following', 'followers', 'friends'].includes(activeTab) && listHasMore && !listLoading && !listLoadingMore) handleLoadMore();
        }
      },
      { threshold: 0.1 }
    );
    if (observerTarget.current) observer.observe(observerTarget.current);
    return () => { if (observerTarget.current) observer.unobserve(observerTarget.current); };
  }, [
    activeTab, 
    postsHasMore, postsLoading, postsLoadingMore, 
    likedHasMore, likedLoading, likedLoadingMore, // 监听 liked 状态
    listHasMore, listLoading, listLoadingMore
  ]);

  // --- Avatar Upload Logic ---
  const handleAvatarClick = () => {
    if (isOwnProfile && !isUploadingAvatar) {
      avatarInputRef.current?.click();
    }
  };

  const handleAvatarChange = async (e: ChangeEvent<HTMLInputElement>) => {
    const originalFile = e.target.files?.[0];
    if (!originalFile) return;

    if (!originalFile.type.startsWith('image/')) {
        toast.error("Please upload an image file");
        return;
    }
    
    setIsUploadingAvatar(true);

    try {
        const file = await convertToJpegIfNeeded(originalFile);

        if (file.size > 10 * 1024 * 1024) { 
          toast.error("Image must be smaller than 10MB");
          setIsUploadingAvatar(false);
          return;
        }

        const formData = new FormData();
        formData.append('file', file);

        const uploadRes = await api.post('/upload', formData, {
            headers: { 'Content-Type': 'multipart/form-data' }
        });

        if (uploadRes.data.code !== 0) {
            throw new Error(uploadRes.data.message || 'Upload failed');
        }

        const newAvatarUrl = uploadRes.data.data[0];

        const updatePayload = {
            avatarUrl: newAvatarUrl
        };

        const updateRes = await api.put('/set', updatePayload);

        if (updateRes.data.code === 0) {
            toast.success("Avatar updated!");
            
            setMe(prev => {
                if (!prev) return null;
                return { ...prev, avatarUrl: newAvatarUrl };
            });

            setViewedUser(prev => {
                if (!prev) return null;
                return { ...prev, avatarUrl: newAvatarUrl };
            });

            window.dispatchEvent(new Event('user-profile-updated'));
        } else {
            toast.error(updateRes.data.message || "Failed to update profile");
        }

    } catch (error: any) {
        console.error(error);
        toast.error(error.message || "Something went wrong");
    } finally {
        setIsUploadingAvatar(false);
        if (avatarInputRef.current) avatarInputRef.current.value = '';
    }
  };


  // --- Event Handlers ---
  const handleMyNav = (tab: typeof activeTab) => {
    if (!me) { toast.error("Please login first"); router.push('/login'); return; }
    const baseUrl = `/user/${me.id}`;
    if (tab === 'profile') router.push(baseUrl);
    else router.push(`${baseUrl}/${tab}`);
  };

  const handleFollowToggle = async () => {
    if (!viewedUser) return;
    const isFollowing = viewedUser.following;
    const url = `/follow/${viewedUserId}`;
    try {
      const res = isFollowing ? await api.delete(url) : await api.post(url);
      if (res.data.code === 0) {
        toast.success(isFollowing ? 'Unfollowed' : 'Followed');
        setViewedUser(prev => {
          if (!prev) return null;
          return {
            ...prev,
            following: !isFollowing,
            followerCount: prev.followerCount + (isFollowing ? -1 : 1),
          };
        });
      } else {
        toast.error(res.data.message);
      }
    } catch (error) { toast.error('Action failed'); }
  };

  const handleLogout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('userId');
    router.push('/login');
  };

  const navigateToSettings = () => { if (me) router.push('/set'); };

  const handlePostCreated = (newPost: Post) => {
    setPosts([newPost, ...posts]);
    setViewedUser(prev => prev ? ({ ...prev, postCount: prev.postCount + 1 }) : null);
  };

  const handlePostDeleted = (postId: number) => {
    setPosts(posts.filter(p => p.id !== postId));
    // 从 Liked 列表移除（如果当前正在查看）
    setLikedPosts(likedPosts.filter(p => p.id !== postId));
    setViewedUser(prev => prev ? ({ ...prev, postCount: Math.max(0, prev.postCount - 1) }) : null);
  };
  
  const getSidebarItemClass = (targetTab: string) => {
    const isActive = isOwnProfile && activeTab === targetTab;
    return `flex items-center w-full px-4 py-3 text-sm font-medium rounded-lg transition-colors duration-200 cursor-pointer mb-1 ${isActive ? 'bg-blue-50 text-blue-700 border-l-4 border-blue-600' : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'}`;
  };

  // --- Render Helpers ---

  const renderUserList = () => {
    if (listLoading) return <div className="p-10 text-center flex justify-center"><Loader2 className="animate-spin text-gray-500" /></div>;
    if (userList.length === 0) return <div className="p-10 text-center text-gray-500">No users found.</div>;
    return (
      <div className="divide-y divide-gray-100">
        {userList.map((item) => (
          <div key={item.id} className="flex items-center justify-between p-4 hover:bg-gray-50 transition-colors cursor-pointer" onClick={() => router.push(`/user/${item.id}`)}>
            <div className="flex items-center space-x-4">
              <img src={item.avatarUrl || "/default-avatar.png"} alt={item.username} className="w-12 h-12 rounded-full object-cover" />
              <div><h4 className="text-sm font-semibold text-gray-900">{item.username}</h4><p className="text-xs text-gray-500 line-clamp-1">{item.bio || 'No bio'}</p></div>
            </div>
            <div className="text-xs text-gray-400">View</div>
          </div>
        ))}
        <div ref={observerTarget} className="py-4 flex justify-center h-16">
           {listLoadingMore && <Loader2 className="animate-spin text-gray-400" size={20}/>}
           {!listHasMore && userList.length > 0 && <span className="text-xs text-gray-300">End of list</span>}
        </div>
      </div>
    );
  };

  // NEW: 渲染 Liked Posts 内容
  const renderLikedPosts = () => {
    return (
      <div className="bg-white rounded-xl shadow overflow-hidden min-h-[400px] animate-in fade-in duration-300">
        <div className="px-6 py-4 border-b border-gray-100">
          <h3 className="text-lg font-bold text-gray-900">Liked Posts</h3>
        </div>
        
        <div className="space-y-4 p-4 bg-gray-50">
          {likedLoading ? (
             <div className="p-10 text-center flex justify-center"><Loader2 className="animate-spin text-gray-500" /></div>
          ) : likedPosts.length === 0 ? (
            <div className="p-10 text-center text-gray-500">No liked posts yet.</div>
          ) : (
            <>
              {likedPosts.map(post => (
                <PostCard 
                  key={post.id} 
                  post={post} 
                  currentUserId={me?.id ? String(me.id) : null} 
                  onDelete={handlePostDeleted} 
                />
              ))}
              <div ref={observerTarget} className="py-4 flex justify-center h-16">
                 {likedLoadingMore && <Loader2 className="animate-spin text-gray-400" size={20}/>}
                 {!likedHasMore && <span className="text-xs text-gray-300">No more posts</span>}
              </div>
            </>
          )}
        </div>
      </div>
    );
  };

  const renderProfileContent = () => {
    return (
      <div className="space-y-6">
        {/* Hidden Input for Avatar Upload */}
        <input 
            type="file" 
            ref={avatarInputRef} 
            onChange={handleAvatarChange} 
            accept="image/*" 
            className="hidden" 
        />

        <div className="bg-white rounded-xl shadow overflow-hidden animate-in fade-in duration-300">
          <div className="h-32 bg-blue-600"></div>
          <div className="px-6 pb-6">
            {/* Header / Avatar Section */}
            <div className="relative flex justify-between items-end -mt-12 mb-6">
              <div 
                className={`relative group ${isOwnProfile ? 'cursor-pointer' : ''}`}
                onClick={handleAvatarClick}
              >
                {/* Avatar Image */}
                <img 
                  src={viewedUser!.avatarUrl || "/default-avatar.png"} 
                  alt={viewedUser!.username}
                  className={`w-24 h-24 rounded-full border-4 border-white bg-white object-cover transition-opacity ${isUploadingAvatar ? 'opacity-50' : ''}`}
                />
                
                {/* Loading Spinner Overlay */}
                {isUploadingAvatar && (
                    <div className="absolute inset-0 flex items-center justify-center z-20">
                        <Loader2 className="animate-spin text-blue-600 w-8 h-8" />
                    </div>
                )}

                {/* Hover Camera Icon (Only for Owner & Not Loading) */}
                {isOwnProfile && !isUploadingAvatar && (
                    <div className="absolute inset-0 rounded-full flex items-center justify-center bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity z-10 border-4 border-transparent">
                        <Camera className="text-white w-8 h-8" />
                    </div>
                )}
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
                    className={`flex items-center px-4 py-2 rounded text-sm font-medium transition-colors ${viewedUser!.following ? "bg-gray-100 text-gray-700 hover:bg-gray-200" : "bg-blue-600 text-white hover:bg-blue-700"}`}
                  >
                    {viewedUser!.following ? <><UserMinus size={16} className="mr-2"/> Unfollow</> : <><UserPlus size={16} className="mr-2"/> Follow</>}
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
                  <span className="text-lg font-bold text-gray-900">{viewedUser!.followCount}</span><span className="text-sm text-gray-500">Following</span>
                </div>
                <div className="cursor-pointer hover:text-blue-600 transition-colors flex items-baseline gap-1" onClick={() => router.push(`/user/${viewedUserId}/followers`)}>
                   <span className="text-lg font-bold text-gray-900">{viewedUser!.followerCount}</span><span className="text-sm text-gray-500">Followers</span>
                </div>
                <div className="cursor-pointer hover:text-blue-600 transition-colors flex items-baseline gap-1" onClick={() => router.push(`/user/${viewedUserId}/friends`)}>
                   <span className="text-lg font-bold text-gray-900">{viewedUser!.friendCount}</span><span className="text-sm text-gray-500">Friends</span>
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

        <div>
          <h3 className="text-lg font-bold text-gray-900 mb-4 px-1">Posts ({viewedUser?.postCount ?? 0})</h3>
          {postsLoading ? (
             <div className="p-10 text-center flex justify-center"><Loader2 className="animate-spin text-gray-500" /></div>
          ) : posts.length === 0 ? (
            <div className="bg-white rounded-xl p-8 text-center text-gray-500 shadow"><p>No posts yet.</p></div>
          ) : (
            <>
              {posts.map(post => (<PostCard key={post.id} post={post} currentUserId={me?.id ? String(me.id) : null} onDelete={handlePostDeleted}/>))}
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


  return (
    <>
      <Navbar />
      <div className="min-h-screen bg-gray-50 pt-20 pb-10">
        {profileLoading ? (
           <div className="p-10 text-center flex justify-center items-center h-64"><Loader2 className="animate-spin mr-2 text-blue-600" /> Loading profile...</div>
        ) : !viewedUser ? (
           <div className="p-10 text-center">User not found</div>
        ) : (
          <div className="max-w-6xl mx-auto px-4 flex flex-col md:flex-row gap-6">
            <aside className="w-full md:w-64 flex-shrink-0">
              <div className="bg-white rounded-xl shadow p-4 sticky top-24">
                <h2 className="px-4 pb-2 text-xs font-semibold text-gray-400 uppercase tracking-wider">My Account</h2>
                <nav className="space-y-1">
                  <button onClick={() => handleMyNav('profile')} className={getSidebarItemClass('profile')}><Layout size={18} className="mr-3" />My Profile</button>
                  <button onClick={() => handleMyNav('following')} className={getSidebarItemClass('following')}><Users size={18} className="mr-3" />My Following</button>
                  <button onClick={() => handleMyNav('followers')} className={getSidebarItemClass('followers')}><Heart size={18} className="mr-3" />My Followers</button>
                  <button onClick={() => handleMyNav('friends')} className={getSidebarItemClass('friends')}><UserCheck size={18} className="mr-3" />My Friends</button>
                  <button onClick={() => handleMyNav('liked')} className={getSidebarItemClass('liked')}><ThumbsUp size={18} className="mr-3" />Liked Posts</button>
                </nav>
              </div>
            </aside>
            <main className="w-full max-w-2xl">
              {/* 主区域内容切换逻辑 */}
              {activeTab === 'profile' ? renderProfileContent() : 
               activeTab === 'liked' ? renderLikedPosts() : (
                <div className="bg-white rounded-xl shadow overflow-hidden min-h-[400px] animate-in fade-in duration-300">
                  <div className="px-6 py-4 border-b border-gray-100">
                    <h3 className="text-lg font-bold text-gray-900">{activeTab === 'following' ? 'Following' : activeTab === 'followers' ? 'Followers' : 'Friends'}</h3>
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