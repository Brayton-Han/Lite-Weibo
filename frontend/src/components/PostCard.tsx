'use client';

import { Post, Comment, PostVisibility, PostType } from '@/types';
import { 
  Heart, MessageCircle, Trash2, AlertCircle, Send, 
  MoreHorizontal, Globe, Lock, Users, UserCheck, Eye, X, Edit,
  Repeat 
} from 'lucide-react';
import { useState, useRef, useEffect } from 'react';
import { toast } from 'react-hot-toast';
import api from '@/lib/api';
import Link from 'next/link';
import EditPostModal from './EditPostModel'; 
import RepostModal from './RepostModal';

interface PostCardProps {
  post: Post;
  currentUserId: string | null;
  onDelete: (postId: number) => void;
}

export default function PostCard({ post, currentUserId, onDelete }: PostCardProps) {
  // --- State: Content & Display ---
  const [displayContent, setDisplayContent] = useState(post.content);
  const [displayImages, setDisplayImages] = useState<string[]>(post.images || []);
  const [isEdited, setIsEdited] = useState(post.edited);

  // --- State: Like ---
  const [liked, setLiked] = useState(post.liked);
  const [likeCount, setLikeCount] = useState(post.likeCount);
  const [isLikeLoading, setIsLikeLoading] = useState(false);

  // --- State: Repost ---
  const [repostCount, setRepostCount] = useState(post.repostCount);
  const [showRepostModal, setShowRepostModal] = useState(false);

  // --- State: Delete Post Modal ---
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);

  // --- State: Edit Post Modal ---
  const [showEditModal, setShowEditModal] = useState(false);

  // --- State: Comments ---
  const [showCommentBox, setShowCommentBox] = useState(false);
  const [commentText, setCommentText] = useState('');
  const [isPostingComment, setIsPostingComment] = useState(false);
  const [commentCount, setCommentCount] = useState(post.commentCount);
  
  const [comments, setComments] = useState<Comment[]>([]);
  const [commentsLoading, setCommentsLoading] = useState(false);
  const [commentsLoaded, setCommentsLoaded] = useState(false);

  // --- State: Delete Comment Modal ---
  const [commentToDelete, setCommentToDelete] = useState<number | null>(null);
  const [isDeletingComment, setIsDeletingComment] = useState(false);

  // --- State: Menu & Visibility ---
  const [showMenu, setShowMenu] = useState(false);
  const [showVisibilityModal, setShowVisibilityModal] = useState(false);
  const [currentVisibility, setCurrentVisibility] = useState<PostVisibility>(post.visibility);
  const [tempVisibility, setTempVisibility] = useState<PostVisibility>(post.visibility);
  const [isUpdatingVisibility, setIsUpdatingVisibility] = useState(false);
  
  const menuRef = useRef<HTMLDivElement>(null);

  const isAuthor = String(post.user?.id) === String(currentUserId);

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setShowMenu(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  // --- Handlers: Like Logic ---
  const handleLike = async () => {
    if (!currentUserId) { toast.error("Please login to like posts"); return; }
    if (isLikeLoading) return;
    const previousLiked = liked;
    const previousCount = likeCount;
    const newLiked = !liked;
    setLiked(newLiked);
    setLikeCount((prev) => (newLiked ? prev + 1 : prev - 1));
    setIsLikeLoading(true);
    try {
      if (newLiked) await api.post(`/posts/${post.id}/like`);
      else await api.delete(`/posts/${post.id}/like`);
    } catch (error) {
      setLiked(previousLiked);
      setLikeCount(previousCount);
      toast.error("Failed to update like status");
    } finally {
      setIsLikeLoading(false);
    }
  };

  // --- Handlers: Repost Logic ---
  const handleRepostClick = () => {
    if (!currentUserId) {
      toast.error("Please login to repost");
      return;
    }
    setShowRepostModal(true);
  };

  const handleRepostSuccess = (newPost: Post) => {
    setRepostCount((prev) => prev + 1);
  };

  // --- Handlers: Edit Post Logic ---
  const openEditModal = () => {
    setShowEditModal(true);
    setShowMenu(false);
  };

  const handlePostUpdated = (updatedPost: Post) => {
    setDisplayContent(updatedPost.content);
    setDisplayImages(updatedPost.images || []);
    setCurrentVisibility(updatedPost.visibility);
    setIsEdited(true);
  };

  // --- Handlers: Visibility Logic ---
  const openVisibilityModal = () => {
    setTempVisibility(currentVisibility);
    setShowVisibilityModal(true);
    setShowMenu(false);
  };

  const handleUpdateVisibility = async () => {
    if (tempVisibility === currentVisibility) {
      setShowVisibilityModal(false);
      return;
    }
    setIsUpdatingVisibility(true);
    try {
      const res = await api.put(`/posts/${post.id}`, {
        ...post,
        content: displayContent,
        images: displayImages, 
        visibility: tempVisibility
      });
      if (res.data.code === 0) {
        setCurrentVisibility(tempVisibility);
        toast.success("Visibility updated");
        setShowVisibilityModal(false);
      } else {
        toast.error(res.data.message || "Failed to update");
      }
    } catch (error) {
      toast.error("Failed to update visibility");
    } finally {
      setIsUpdatingVisibility(false);
    }
  };

  // --- Handlers: Delete Post Logic ---
  const confirmDeletePost = async () => {
    setIsDeleting(true);
    try {
      const res = await api.delete(`/posts/${post.id}`);
      if (res.data.code === 0) {
        toast.success("Post deleted successfully");
        setShowDeleteModal(false);
        onDelete(post.id);
      } else {
        toast.error(res.data.message || "Failed to delete");
      }
    } catch (e) {
      toast.error("Network error");
    } finally {
      setIsDeleting(false);
    }
  };

  // --- Handlers: Comment Logic ---
  const loadComments = async () => {
    if (commentsLoaded) return;
    setCommentsLoading(true);
    try {
        const res = await api.get(`/posts/${post.id}/comments`);
        if (res.data.code === 0) { setComments(res.data.data); setCommentsLoaded(true); }
    } catch (e) { toast.error("Failed to load comments"); } 
    finally { setCommentsLoading(false); }
  };
  const toggleCommentSection = () => {
      const newState = !showCommentBox; setShowCommentBox(newState);
      if (newState && !commentsLoaded) loadComments();
  };
  const handlePublishComment = async () => {
      if (!currentUserId) { toast.error("Please login to comment"); return; }
      if (!commentText.trim()) { toast.error("Comment cannot be empty"); return; }
      setIsPostingComment(true);
      try {
        const res = await api.post(`/posts/${post.id}/comments`, { content: commentText });
        if (res.data.code === 0) {
          toast.success("Comment published!"); setCommentText(''); setCommentCount(prev => prev + 1);
          const listRes = await api.get(`/posts/${post.id}/comments`);
          if(listRes.data.code === 0) { setComments(listRes.data.data); setCommentsLoaded(true); }
        } else { toast.error(res.data.message || "Failed to publish"); }
      } catch (error) { toast.error("Network error"); } finally { setIsPostingComment(false); }
  };
  const openDeleteCommentModal = (cid: number) => setCommentToDelete(cid);
  const confirmDeleteComment = async () => {
      if (commentToDelete === null) return;
      setIsDeletingComment(true);
      try {
        const res = await api.delete(`/comments/${commentToDelete}`);
        if (res.data.code === 0) {
          toast.success("Comment deleted");
          setComments((prev) => prev.filter((c) => c.id !== commentToDelete));
          setCommentCount((prev) => prev - 1); setCommentToDelete(null); 
        } else { toast.error(res.data.message || "Failed to delete"); }
      } catch (error) { toast.error("Network error"); } finally { setIsDeletingComment(false); }
  };

  // --- Render Helpers ---
  const renderImages = (images: string[]) => {
    if (!images || images.length === 0) return null;
    const count = images.length;
    if (count === 1) {
      return (
        <div className="mt-3 rounded-lg overflow-hidden bg-gray-100 border border-gray-100">
          <img src={images[0]} alt="post-img" className="w-full h-auto max-h-[600px] object-contain block hover:opacity-95 transition-opacity cursor-pointer" />
        </div>
      );
    }
    let gridClass = 'grid gap-1 mt-3 rounded-lg overflow-hidden';
    if (count === 2) gridClass += ' grid-cols-2';
    else if (count >= 3) gridClass += ' grid-cols-3';
    return (
      <div className={gridClass}>
        {images.map((img, idx) => (
          <div key={idx} className={`relative ${count === 1 ? 'aspect-video' : 'aspect-square'}`}>
             <img src={img} alt="post-img" className="w-full h-full object-cover bg-gray-100 hover:opacity-95 transition-opacity cursor-pointer" />
          </div>
        ))}
      </div>
    );
  };

  const getVisibilityIcon = (v: PostVisibility) => {
    switch (v) {
      case PostVisibility.PUBLIC: return <Globe size={12} />;
      case PostVisibility.FRIENDS: return <Users size={12} />;
      case PostVisibility.FOLLOWERS: return <UserCheck size={12} />;
      case PostVisibility.PRIVATE: return <Lock size={12} />;
      default: return <Globe size={12} />;
    }
  };

  const getVisibilityLabel = (v: PostVisibility) => {
    switch (v) {
      case PostVisibility.PUBLIC: return "Public";
      case PostVisibility.FRIENDS: return "Friends Only";
      case PostVisibility.FOLLOWERS: return "Followers Only";
      case PostVisibility.PRIVATE: return "Private";
      default: return v;
    }
  };

  // --- NEW: Render Referenced Post (原贴渲染逻辑) ---
  const renderRefPost = () => {
    // 只有当存在 refPost 时才渲染
    if (!post.refPost) return null;

    const ref = post.refPost;

    return (
      <div className="mt-3 border border-gray-200 rounded-xl bg-gray-50 p-3 hover:bg-gray-100 transition-colors">
         {/* 原贴作者信息 */}
         <Link href={`/user/${ref.user.id}`} className="flex items-center gap-2 mb-2 group">
           <img 
              src={ref.user.avatarUrl || "/default-avatar.png"} 
              className="w-5 h-5 rounded-full object-cover" 
              alt="ref-avatar" 
           />
           <span className="font-semibold text-sm text-gray-900 group-hover:underline">
             {ref.user.username}
           </span>
           <span className="text-xs text-gray-500">
             @{ref.user.username} · {new Date(ref.createdAt).toLocaleDateString()}
           </span>
         </Link>
         
         {/* 原贴内容 */}
         <div className="text-sm text-gray-800 whitespace-pre-wrap leading-relaxed pl-7">
           {ref.content}
         </div>

         {/* 原贴图片 (复用 renderImages，但稍微调整容器样式) */}
         {ref.images && ref.images.length > 0 && (
           <div className="pl-7 scale-95 origin-top-left w-[105%]"> 
             {renderImages(ref.images)}
           </div>
         )}
      </div>
    );
  };

  return (
    <>
      <div className="bg-white rounded-xl shadow p-4 mb-4 relative transition-all duration-200">
        
        {/* --- Header --- */}
        <div className="flex justify-between items-start">
          <div className="flex gap-3">
            <Link href={`/user/${post.user.id}`}>
               <img src={post.user.avatarUrl || "/default-avatar.png"} className="w-10 h-10 rounded-full object-cover cursor-pointer hover:opacity-90 transition-opacity" />
            </Link>
            <div>
              <Link href={`/user/${post.user.id}`}>
                 <h4 className="font-semibold text-gray-900 hover:underline cursor-pointer">{post.user.username}</h4>
              </Link>
              <div className="flex items-center gap-2 text-xs text-gray-500 mt-0.5">
                <span>
                  {new Date(post.createdAt).toLocaleDateString()} 
                  <span className="mx-1">•</span> 
                  {new Date(post.createdAt).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})}
                  
                  {isEdited && (
                    <span className="ml-1.5 text-gray-400 italic font-normal" title={`Edited at ${new Date(post.updatedAt).toLocaleString()}`}>
                      (edited)
                    </span>
                  )}
                </span>

                <div className="flex items-center gap-1 bg-gray-100 px-2 py-0.5 rounded text-gray-500 select-none">
                  {getVisibilityIcon(currentVisibility)}
                  <span className="text-[11px] font-medium">{getVisibilityLabel(currentVisibility)}</span>
                </div>
              </div>
            </div>
          </div>
          
          {/* Action Menu */}
          {isAuthor && (
            <div className="relative" ref={menuRef}>
              <button 
                onClick={() => setShowMenu(!showMenu)}
                className="text-gray-400 hover:text-gray-600 p-2 rounded-full hover:bg-gray-100 transition-colors"
              >
                <MoreHorizontal size={20} />
              </button>

              {showMenu && (
                <div className="absolute right-0 mt-2 w-48 bg-white rounded-lg shadow-lg border border-gray-100 py-1 z-10 animate-in fade-in zoom-in-95 duration-100 origin-top-right">
                  <button
                    onClick={openEditModal}
                    className="w-full text-left px-4 py-2.5 text-sm text-gray-700 hover:bg-gray-50 flex items-center gap-2"
                  >
                    <Edit size={16} />
                    Edit Post
                  </button>
                  <button
                    onClick={openVisibilityModal}
                    className="w-full text-left px-4 py-2.5 text-sm text-gray-700 hover:bg-gray-50 flex items-center gap-2"
                  >
                    <Eye size={16} />
                    Edit Visibility
                  </button>
                  <div className="h-px bg-gray-100 my-1"></div>
                  <button
                    onClick={() => { setShowDeleteModal(true); setShowMenu(false); }}
                    className="w-full text-left px-4 py-2.5 text-sm text-red-600 hover:bg-red-50 flex items-center gap-2"
                  >
                    <Trash2 size={16} />
                    Delete Post
                  </button>
                </div>
              )}
            </div>
          )}
        </div>

        {/* --- Content --- */}
        <div className="mt-3">
          {/* 当前帖子的内容 */}
          {displayContent && (
             <p className="text-gray-800 whitespace-pre-wrap leading-relaxed">{displayContent}</p>
          )}
          
          {/* 当前帖子的图片 */}
          {renderImages(displayImages)}

          {/* NEW: 渲染被转发/引用的原贴 */}
          {renderRefPost()}
        </div>

        {/* --- Footer Actions --- */}
        <div className="flex items-center justify-between mt-4 pt-3 border-t border-gray-100 text-gray-500">
          {/* Like Button */}
          <button onClick={handleLike} disabled={isLikeLoading} className={`flex items-center space-x-2 text-sm transition-colors group ${liked ? 'text-red-500' : 'hover:text-red-500'}`}>
            <Heart size={20} className={`transition-transform group-active:scale-125 ${liked ? 'fill-current' : ''}`} />
            <span>{likeCount > 0 ? likeCount : 'Like'}</span>
          </button>
          
          {/* Comment Button */}
          <button onClick={toggleCommentSection} className={`flex items-center space-x-2 text-sm transition-colors ${showCommentBox ? 'text-blue-600' : 'hover:text-blue-600'}`}>
            <MessageCircle size={20} className={showCommentBox ? 'fill-blue-50' : ''} />
            <span>{commentCount > 0 ? commentCount : 'Comment'}</span>
          </button>

          {/* Repost Button (Only for ORIGINAL posts) */}
          {post.type === PostType.ORIGINAL && (
            <button 
              onClick={handleRepostClick} 
              className="flex items-center space-x-2 text-sm hover:text-green-500 transition-colors"
            >
              <Repeat size={20} />
              <span>{repostCount > 0 ? repostCount : 'Repost'}</span>
            </button>
          )}
        </div>

        {/* --- Comment Section --- */}
        {showCommentBox && (
          <div className="mt-4 border-t border-gray-50 pt-4 animate-in fade-in slide-in-from-top-2 duration-200">
             <div className="relative mb-6">
              <textarea value={commentText} onChange={(e) => setCommentText(e.target.value)} placeholder="Write a comment..." className="w-full bg-gray-50 border border-gray-200 rounded-lg p-3 text-sm focus:ring-2 focus:ring-blue-100 focus:bg-white focus:outline-none transition-all resize-none h-20" />
              <div className="flex justify-end mt-2">
                <button onClick={handlePublishComment} disabled={isPostingComment || !commentText.trim()} className="bg-blue-600 text-white px-4 py-1.5 rounded-full text-sm font-medium hover:bg-blue-700 disabled:opacity-50 flex items-center gap-2">
                  {isPostingComment ? 'Sending...' : <><Send size={14} /> Publish</>}
                </button>
              </div>
            </div>
            <div className="space-y-4">
              {commentsLoading ? <div className="text-center text-gray-400 text-sm">Loading comments...</div> : comments.length > 0 ? comments.map(c => (
                <div key={c.id} className="flex gap-3 animate-in fade-in duration-300 group">
                    <Link href={`/user/${c.user.id}`}><img src={c.user.avatarUrl || "/default-avatar.png"} className="w-8 h-8 rounded-full object-cover mt-1" /></Link>
                    <div className="flex-1 bg-gray-50 rounded-lg p-3 relative">
                       <div className="flex justify-between items-baseline mb-1">
                          <Link href={`/user/${c.user.id}`}><span className="font-semibold text-sm text-gray-900 hover:underline">{c.user.username}</span></Link>
                          <div className="flex items-center gap-2">
                             <span className="text-xs text-gray-400">{new Date(c.createdAt).toLocaleDateString()}</span>
                             {String(c.user.id) === String(currentUserId) && <button onClick={() => openDeleteCommentModal(c.id)} className="text-gray-400 hover:text-red-500 opacity-0 group-hover:opacity-100"><Trash2 size={14} /></button>}
                          </div>
                       </div>
                       <p className="text-sm text-gray-700 whitespace-pre-wrap">{c.content}</p>
                    </div>
                </div>
              )) : <div className="text-center text-gray-400 text-sm py-4">No comments yet.</div>}
            </div>
          </div>
        )}
      </div>

      {/* --- Modals --- */}
      <EditPostModal 
        isOpen={showEditModal}
        onClose={() => setShowEditModal(false)}
        post={{
          ...post,
          content: displayContent,
          images: displayImages,
          visibility: currentVisibility
        }}
        onPostUpdated={handlePostUpdated}
      />
      <RepostModal
        isOpen={showRepostModal}
        onClose={() => setShowRepostModal(false)}
        post={post}
        onPostCreated={handleRepostSuccess}
      />
      {showVisibilityModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 animate-in fade-in duration-200">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-sm overflow-hidden scale-100 animate-in zoom-in-95 duration-200">
            <div className="flex justify-between items-center p-4 border-b border-gray-100">
              <h3 className="text-lg font-medium text-gray-900">Post Visibility</h3>
              <button onClick={() => setShowVisibilityModal(false)} className="text-gray-400 hover:text-gray-600">
                <X size={20} />
              </button>
            </div>
            <div className="p-4 space-y-2">
              <p className="text-sm text-gray-500 mb-3">Who can see this post?</p>
              {[
                { type: PostVisibility.PUBLIC, icon: Globe, label: 'Public', desc: 'Anyone can see this' },
                { type: PostVisibility.FRIENDS, icon: Users, label: 'Friends', desc: 'Only your friends can see this' },
                { type: PostVisibility.FOLLOWERS, icon: UserCheck, label: 'Followers', desc: 'Only your followers can see this' },
                { type: PostVisibility.PRIVATE, icon: Lock, label: 'Private', desc: 'Only you can see this' },
              ].map((item) => (
                <button
                  key={item.type}
                  onClick={() => setTempVisibility(item.type)}
                  className={`w-full flex items-center gap-3 p-3 rounded-xl border transition-all text-left ${tempVisibility === item.type ? 'border-blue-500 bg-blue-50 ring-1 ring-blue-500' : 'border-gray-200 hover:border-gray-300 hover:bg-gray-50'}`}
                >
                  <div className={`p-2 rounded-full ${tempVisibility === item.type ? 'bg-blue-100 text-blue-600' : 'bg-gray-100 text-gray-600'}`}><item.icon size={20} /></div>
                  <div><div className={`font-medium text-sm ${tempVisibility === item.type ? 'text-blue-900' : 'text-gray-900'}`}>{item.label}</div><div className="text-xs text-gray-500">{item.desc}</div></div>
                </button>
              ))}
            </div>
            <div className="p-4 bg-gray-50 flex justify-end gap-3">
               <button onClick={() => setShowVisibilityModal(false)} className="px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-100 rounded-lg transition-colors">Cancel</button>
               <button onClick={handleUpdateVisibility} disabled={isUpdatingVisibility} className="px-4 py-2 text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 rounded-lg transition-colors disabled:opacity-50">{isUpdatingVisibility ? 'Saving...' : 'Save Changes'}</button>
            </div>
          </div>
        </div>
      )}
      {showDeleteModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 animate-in fade-in duration-200">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-sm overflow-hidden scale-100 animate-in zoom-in-95 duration-200">
            <div className="p-6 text-center">
              <div className="mx-auto flex items-center justify-center h-12 w-12 rounded-full bg-red-100 mb-4"><AlertCircle className="h-6 w-6 text-red-600" /></div>
              <h3 className="text-lg font-medium text-gray-900">Delete Post?</h3>
              <p className="text-sm text-gray-500 mt-2">Are you sure you want to delete this post? This action cannot be undone.</p>
            </div>
            <div className="bg-gray-50 px-4 py-3 sm:px-6 sm:flex sm:flex-row-reverse gap-2">
              <button type="button" onClick={confirmDeletePost} disabled={isDeleting} className="w-full inline-flex justify-center rounded-lg border border-transparent shadow-sm px-4 py-2 bg-red-600 text-base font-medium text-white hover:bg-red-700 focus:outline-none sm:ml-3 sm:w-auto sm:text-sm disabled:opacity-50">{isDeleting ? 'Deleting...' : 'Delete'}</button>
              <button type="button" onClick={() => setShowDeleteModal(false)} className="mt-3 w-full inline-flex justify-center rounded-lg border border-gray-300 shadow-sm px-4 py-2 bg-white text-base font-medium text-gray-700 hover:bg-gray-50 focus:outline-none sm:mt-0 sm:ml-3 sm:w-auto sm:text-sm">Cancel</button>
            </div>
          </div>
        </div>
      )}
      {commentToDelete !== null && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 animate-in fade-in duration-200">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-sm overflow-hidden scale-100 animate-in zoom-in-95 duration-200">
            <div className="p-6 text-center">
              <div className="mx-auto flex items-center justify-center h-12 w-12 rounded-full bg-red-100 mb-4"><AlertCircle className="h-6 w-6 text-red-600" /></div>
              <h3 className="text-lg font-medium text-gray-900">Delete Comment?</h3>
              <p className="text-sm text-gray-500 mt-2">Are you sure you want to delete this comment? This action cannot be undone.</p>
            </div>
            <div className="bg-gray-50 px-4 py-3 sm:px-6 sm:flex sm:flex-row-reverse gap-2">
              <button type="button" onClick={confirmDeleteComment} disabled={isDeletingComment} className="w-full inline-flex justify-center rounded-lg border border-transparent shadow-sm px-4 py-2 bg-red-600 text-base font-medium text-white hover:bg-red-700 focus:outline-none sm:ml-3 sm:w-auto sm:text-sm disabled:opacity-50">{isDeletingComment ? 'Deleting...' : 'Delete'}</button>
              <button type="button" onClick={() => setCommentToDelete(null)} className="mt-3 w-full inline-flex justify-center rounded-lg border border-gray-300 shadow-sm px-4 py-2 bg-white text-base font-medium text-gray-700 hover:bg-gray-50 focus:outline-none sm:mt-0 sm:ml-3 sm:w-auto sm:text-sm">Cancel</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}