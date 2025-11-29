'use client';

import { Post, Comment } from '@/types';
import { Heart, MessageCircle, Share2, Trash2, AlertCircle, Send } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'react-hot-toast';
import api from '@/lib/api';
import Link from 'next/link';

interface PostCardProps {
  post: Post;
  currentUserId: string | null;
  onDelete: (postId: number) => void;
}

export default function PostCard({ post, currentUserId, onDelete }: PostCardProps) {
  // --- State: Like ---
  const [liked, setLiked] = useState(post.liked);
  const [likeCount, setLikeCount] = useState(post.likeCount);
  const [isLikeLoading, setIsLikeLoading] = useState(false);

  // --- State: Delete Post Modal ---
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);

  // --- State: Comments ---
  const [showCommentBox, setShowCommentBox] = useState(false);
  const [commentText, setCommentText] = useState('');
  const [isPostingComment, setIsPostingComment] = useState(false);
  const [commentCount, setCommentCount] = useState(post.commentCount);
  
  const [comments, setComments] = useState<Comment[]>([]);
  const [commentsLoading, setCommentsLoading] = useState(false);
  const [commentsLoaded, setCommentsLoaded] = useState(false);

  // --- State: Delete Comment Modal (新增) ---
  const [commentToDelete, setCommentToDelete] = useState<number | null>(null); // 存储待删除的评论ID，非null即显示弹窗
  const [isDeletingComment, setIsDeletingComment] = useState(false);

  const isAuthor = String(post.user?.id) === String(currentUserId);

  // --- Handlers: Like Logic ---
  const handleLike = async () => {
    if (!currentUserId) {
      toast.error("Please login to like posts");
      return;
    }
    if (isLikeLoading) return;

    const previousLiked = liked;
    const previousCount = likeCount;
    
    const newLiked = !liked;
    setLiked(newLiked);
    setLikeCount((prev) => (newLiked ? prev + 1 : prev - 1));
    setIsLikeLoading(true);

    try {
      if (newLiked) {
        await api.post(`/posts/${post.id}/like`);
      } else {
        await api.delete(`/posts/${post.id}/like`);
      }
    } catch (error) {
      setLiked(previousLiked);
      setLikeCount(previousCount);
      toast.error("Failed to update like status");
    } finally {
      setIsLikeLoading(false);
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
      if (res.data.code === 0) {
        setComments(res.data.data);
        setCommentsLoaded(true);
      }
    } catch (e) {
      toast.error("Failed to load comments");
    } finally {
      setCommentsLoading(false);
    }
  };

  const toggleCommentSection = () => {
    const newState = !showCommentBox;
    setShowCommentBox(newState);
    if (newState && !commentsLoaded) {
      loadComments();
    }
  };

  const handlePublishComment = async () => {
    if (!currentUserId) {
      toast.error("Please login to comment");
      return;
    }
    if (!commentText.trim()) {
      toast.error("Comment cannot be empty");
      return;
    }

    setIsPostingComment(true);
    try {
      const res = await api.post(`/posts/${post.id}/comments`, {
        content: commentText
      });

      if (res.data.code === 0) {
        toast.success("Comment published!");
        setCommentText('');
        setCommentCount(prev => prev + 1);
        
        const listRes = await api.get(`/posts/${post.id}/comments`);
        if(listRes.data.code === 0) {
            setComments(listRes.data.data);
            setCommentsLoaded(true);
        }
      } else {
        toast.error(res.data.message || "Failed to publish");
      }
    } catch (error) {
      toast.error("Network error");
    } finally {
      setIsPostingComment(false);
    }
  };

  // --- Handlers: Delete Comment Logic (修改) ---
  
  // 1. 点击删除图标，打开弹窗
  const openDeleteCommentModal = (commentId: number) => {
    setCommentToDelete(commentId);
  };

  // 2. 确认删除评论
  const confirmDeleteComment = async () => {
    if (commentToDelete === null) return;

    setIsDeletingComment(true);
    try {
      const res = await api.delete(`/comments/${commentToDelete}`);
      
      if (res.data.code === 0) {
        toast.success("Comment deleted");
        // 更新本地状态
        setComments((prev) => prev.filter((c) => c.id !== commentToDelete));
        setCommentCount((prev) => prev - 1);
        // 关闭弹窗
        setCommentToDelete(null); 
      } else {
        toast.error(res.data.message || "Failed to delete comment");
      }
    } catch (error) {
      toast.error("Network error");
    } finally {
      setIsDeletingComment(false);
    }
  };

  // --- Render Helpers ---
  const renderImages = () => {
    if (!post.images || post.images.length === 0) return null;
    const count = post.images.length;

    if (count === 1) {
      return (
        <div className="mt-3 rounded-lg overflow-hidden bg-gray-100 border border-gray-100">
          <img 
            src={post.images[0]} 
            alt="post-img" 
            className="w-full h-auto max-h-[600px] object-contain block hover:opacity-95 transition-opacity cursor-pointer" 
          />
        </div>
      );
    }

    let gridClass = 'grid gap-1 mt-3 rounded-lg overflow-hidden';
    if (count === 2) gridClass += ' grid-cols-2';
    else if (count >= 3) gridClass += ' grid-cols-3';
    
    return (
      <div className={gridClass}>
        {post.images.map((img, idx) => (
          <div key={idx} className={`relative ${count === 1 ? 'aspect-video' : 'aspect-square'}`}>
             <img src={img} alt="post-img" className="w-full h-full object-cover bg-gray-100 hover:opacity-95 transition-opacity cursor-pointer" />
          </div>
        ))}
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
               <img 
                 src={post.user.avatarUrl || "/default-avatar.png"} 
                 className="w-10 h-10 rounded-full object-cover cursor-pointer hover:opacity-90 transition-opacity" 
               />
            </Link>
            <div>
              <Link href={`/user/${post.user.id}`}>
                 <h4 className="font-semibold text-gray-900 hover:underline cursor-pointer">
                   {post.user.username}
                 </h4>
              </Link>
              <p className="text-xs text-gray-500">
                {new Date(post.createdAt).toLocaleDateString()} • {new Date(post.createdAt).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})}
              </p>
            </div>
          </div>
          
          {isAuthor && (
            <button 
              onClick={() => setShowDeleteModal(true)} 
              className="text-gray-400 hover:text-red-500 p-2 rounded-full hover:bg-red-50 transition-colors"
              title="Delete Post"
            >
              <Trash2 size={18} />
            </button>
          )}
        </div>

        {/* --- Content --- */}
        <div className="mt-3">
          <p className="text-gray-800 whitespace-pre-wrap leading-relaxed">{post.content}</p>
          {renderImages()}
        </div>

        {/* --- Footer Actions --- */}
        <div className="flex items-center justify-between mt-4 pt-3 border-t border-gray-100 text-gray-500">
          <button 
            onClick={handleLike}
            disabled={isLikeLoading}
            className={`flex items-center space-x-2 text-sm transition-colors group ${liked ? 'text-red-500' : 'hover:text-red-500'}`}
          >
            <Heart size={20} className={`transition-transform group-active:scale-125 ${liked ? 'fill-current' : ''}`} />
            <span>{likeCount > 0 ? likeCount : 'Like'}</span>
          </button>
          
          <button 
            onClick={toggleCommentSection}
            className={`flex items-center space-x-2 text-sm transition-colors ${showCommentBox ? 'text-blue-600' : 'hover:text-blue-600'}`}
          >
            <MessageCircle size={20} className={showCommentBox ? 'fill-blue-50' : ''} />
            <span>{commentCount > 0 ? commentCount : 'Comment'}</span>
          </button>

          <button className="flex items-center space-x-2 text-sm hover:text-green-500 transition-colors">
            <Share2 size={20} />
            <span>Share</span>
          </button>
        </div>

        {/* --- Comment Section --- */}
        {showCommentBox && (
          <div className="mt-4 border-t border-gray-50 pt-4 animate-in fade-in slide-in-from-top-2 duration-200">
            
            <div className="relative mb-6">
              <textarea
                value={commentText}
                onChange={(e) => setCommentText(e.target.value)}
                placeholder="Write a comment..."
                className="w-full bg-gray-50 border border-gray-200 rounded-lg p-3 text-sm focus:ring-2 focus:ring-blue-100 focus:bg-white focus:outline-none transition-all resize-none h-20"
                autoFocus
              />
              <div className="flex justify-end mt-2">
                <button
                  onClick={handlePublishComment}
                  disabled={isPostingComment || !commentText.trim()}
                  className="bg-blue-600 text-white px-4 py-1.5 rounded-full text-sm font-medium hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 transition-colors"
                >
                  {isPostingComment ? 'Sending...' : <><Send size={14} /> Publish</>}
                </button>
              </div>
            </div>

            <div className="space-y-4">
              {commentsLoading ? (
                <div className="text-center text-gray-400 text-sm py-2">Loading comments...</div>
              ) : comments.length > 0 ? (
                comments.map((comment) => {
                  const isCommentAuthor = String(comment.user.id) === String(currentUserId);

                  return (
                    <div key={comment.id} className="flex gap-3 animate-in fade-in duration-300 group">
                      <Link href={`/user/${comment.user.id}`}>
                        <img 
                          src={comment.user.avatarUrl || "/default-avatar.png"} 
                          alt={comment.user.username}
                          className="w-8 h-8 rounded-full object-cover cursor-pointer mt-1" 
                        />
                      </Link>
                      <div className="flex-1 bg-gray-50 rounded-lg p-3 relative">
                        <div className="flex justify-between items-baseline mb-1">
                          <Link href={`/user/${comment.user.id}`}>
                            <span className="font-semibold text-sm text-gray-900 hover:underline cursor-pointer">
                              {comment.user.username}
                            </span>
                          </Link>
                          
                          <div className="flex items-center gap-2">
                            <span className="text-xs text-gray-400">
                              {new Date(comment.createdAt).toLocaleString([], {
                                year: 'numeric',
                                month: '2-digit',
                                day: '2-digit',
                                hour: '2-digit',
                                minute: '2-digit',
                                hour12: false
                              })}
                            </span>
                            
                            {isCommentAuthor && (
                              <button
                                onClick={() => openDeleteCommentModal(comment.id)} // 修改：触发弹窗
                                className="text-gray-400 hover:text-red-500 transition-colors opacity-0 group-hover:opacity-100 focus:opacity-100"
                                title="Delete comment"
                              >
                                <Trash2 size={14} />
                              </button>
                            )}
                          </div>
                        </div>
                        <p className="text-sm text-gray-700 whitespace-pre-wrap">{comment.content}</p>
                      </div>
                    </div>
                  );
                })
              ) : (
                <div className="text-center text-gray-400 text-sm py-4">No comments yet.</div>
              )}
            </div>
            
          </div>
        )}
      </div>

      {/* --- Delete Post Modal --- */}
      {showDeleteModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 animate-in fade-in duration-200">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-sm overflow-hidden scale-100 animate-in zoom-in-95 duration-200">
            <div className="p-6 text-center">
              <div className="mx-auto flex items-center justify-center h-12 w-12 rounded-full bg-red-100 mb-4">
                <AlertCircle className="h-6 w-6 text-red-600" />
              </div>
              <h3 className="text-lg font-medium text-gray-900">Delete Post?</h3>
              <p className="text-sm text-gray-500 mt-2">
                Are you sure you want to delete this post? This action cannot be undone.
              </p>
            </div>
            <div className="bg-gray-50 px-4 py-3 sm:px-6 sm:flex sm:flex-row-reverse gap-2">
              <button
                type="button"
                onClick={confirmDeletePost}
                disabled={isDeleting}
                className="w-full inline-flex justify-center rounded-lg border border-transparent shadow-sm px-4 py-2 bg-red-600 text-base font-medium text-white hover:bg-red-700 focus:outline-none sm:ml-3 sm:w-auto sm:text-sm disabled:opacity-50"
              >
                {isDeleting ? 'Deleting...' : 'Delete'}
              </button>
              <button
                type="button"
                onClick={() => setShowDeleteModal(false)}
                className="mt-3 w-full inline-flex justify-center rounded-lg border border-gray-300 shadow-sm px-4 py-2 bg-white text-base font-medium text-gray-700 hover:bg-gray-50 focus:outline-none sm:mt-0 sm:ml-3 sm:w-auto sm:text-sm"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}

      {/* --- Delete Comment Modal (新增：样式与 Post Modal 一致) --- */}
      {commentToDelete !== null && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 animate-in fade-in duration-200">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-sm overflow-hidden scale-100 animate-in zoom-in-95 duration-200">
            <div className="p-6 text-center">
              <div className="mx-auto flex items-center justify-center h-12 w-12 rounded-full bg-red-100 mb-4">
                <AlertCircle className="h-6 w-6 text-red-600" />
              </div>
              <h3 className="text-lg font-medium text-gray-900">Delete Comment?</h3>
              <p className="text-sm text-gray-500 mt-2">
                Are you sure you want to delete this comment? This action cannot be undone.
              </p>
            </div>
            <div className="bg-gray-50 px-4 py-3 sm:px-6 sm:flex sm:flex-row-reverse gap-2">
              <button
                type="button"
                onClick={confirmDeleteComment}
                disabled={isDeletingComment}
                className="w-full inline-flex justify-center rounded-lg border border-transparent shadow-sm px-4 py-2 bg-red-600 text-base font-medium text-white hover:bg-red-700 focus:outline-none sm:ml-3 sm:w-auto sm:text-sm disabled:opacity-50"
              >
                {isDeletingComment ? 'Deleting...' : 'Delete'}
              </button>
              <button
                type="button"
                onClick={() => setCommentToDelete(null)}
                className="mt-3 w-full inline-flex justify-center rounded-lg border border-gray-300 shadow-sm px-4 py-2 bg-white text-base font-medium text-gray-700 hover:bg-gray-50 focus:outline-none sm:mt-0 sm:ml-3 sm:w-auto sm:text-sm"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}