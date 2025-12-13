'use client';

import { useState, useRef, useEffect } from 'react';
import { Post, CreatePostRequest, PostType, PostVisibility } from '@/types';
import api from '@/lib/api';
import { X, Loader2, Send, Quote, Globe, Users, Lock, UserCheck, ChevronDown } from 'lucide-react';
import { toast } from 'react-hot-toast';

interface RepostModalProps {
  isOpen: boolean;
  onClose: () => void;
  post: Post; // 当前被点击转发的帖子
  onPostCreated: (newPost: Post) => void; // 回调刷新列表
}

export default function RepostModal({ isOpen, onClose, post, onPostCreated }: RepostModalProps) {
  const [content, setContent] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  
  // --- Visibility State ---
  const [visibility, setVisibility] = useState<PostVisibility>(PostVisibility.PUBLIC);
  const [showVisibilityMenu, setShowVisibilityMenu] = useState(false);
  const visibilityMenuRef = useRef<HTMLDivElement>(null);
  
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // 这里的 originPost 指向最原始的帖子（防止无限嵌套）
  // 如果 post.refPost 存在，说明 post 已经是转发/引用贴，源头是 post.refPost
  // 否则 post 本身就是源头
  const originPost = post.refPost || post;

  useEffect(() => {
    if (isOpen) {
      // 打开时清空内容，状态复位
      setContent('');
      setVisibility(PostVisibility.PUBLIC); 
      setShowVisibilityMenu(false);

      // 自动聚焦
      setTimeout(() => {
        if (textareaRef.current) {
          textareaRef.current.focus();
        }
      }, 100);
    }
  }, [isOpen, post]);

  // 点击外部关闭可见性菜单
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (visibilityMenuRef.current && !visibilityMenuRef.current.contains(event.target as Node)) {
        setShowVisibilityMenu(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  const handleSubmit = async () => {
    setIsSubmitting(true);
    try {
      // --- 核心逻辑：判断是 REPOST 还是 QUOTE ---
      // 没文字 -> REPOST (纯转发)
      // 有文字 -> QUOTE (引用/评论转发)
      const isQuote = content.trim().length > 0;
      const postType = isQuote ? PostType.QUOTE : PostType.REPOST;

      const payload: CreatePostRequest = {
        content: content, // 如果是 REPOST，这里为空字符串，后端应允许
        images: [], 
        visibility: visibility, // 使用用户选择的可见性
        type: postType,
        refPostId: originPost.id // 始终关联到源帖子
      };

      const res = await api.post('/posts', payload);

      if (res.data.code === 0) {
        toast.success(isQuote ? "Quote posted!" : "Reposted successfully!");
        onPostCreated(res.data.data);
        onClose();
      } else {
        toast.error(res.data.message || "Failed to repost");
      }
    } catch (e) {
      console.error(e);
      toast.error("Network error");
    } finally {
      setIsSubmitting(false);
    }
  };

  if (!isOpen) return null;

  // --- Render Helpers for Visibility ---
  const visibilityOptions = [
    { value: PostVisibility.PUBLIC, label: 'Public', icon: Globe },
    { value: PostVisibility.FRIENDS, label: 'Friends', icon: Users },
    { value: PostVisibility.FOLLOWERS, label: 'Followers', icon: UserCheck },
    { value: PostVisibility.PRIVATE, label: 'Private', icon: Lock },
  ];

  const currentVisOption = visibilityOptions.find(o => o.value === visibility) || visibilityOptions[0];
  const VisIcon = currentVisOption.icon;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4 animate-in fade-in duration-200">
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-lg flex flex-col scale-100 animate-in zoom-in-95 duration-200 overflow-visible">
        
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-gray-100">
          <h2 className="text-lg font-semibold text-gray-800 flex items-center gap-2">
            <Quote size={20} className="text-blue-500"/> Repost
          </h2>
          <button onClick={onClose} className="p-2 hover:bg-gray-100 rounded-full transition-colors text-gray-500">
            <X size={20} />
          </button>
        </div>

        {/* Body */}
        <div className="p-4">
          <textarea
            ref={textareaRef}
            className="w-full bg-gray-50 border-0 rounded-lg p-3 text-gray-900 focus:ring-2 focus:ring-blue-100 focus:bg-white transition-all resize-none h-24 placeholder:text-gray-400"
            placeholder="Add a comment... (Leave empty to just repost)"
            value={content}
            onChange={(e) => setContent(e.target.value)}
          />

          {/* 源帖子预览卡片 */}
          <div className="mt-4 border border-gray-200 rounded-lg bg-white p-3 flex gap-3 select-none relative overflow-hidden">
            {/* 灰色左边条，增加引用的感觉 */}
            <div className="absolute left-0 top-0 bottom-0 w-1 bg-gray-300"></div>
            
            {originPost.images && originPost.images.length > 0 && (
              <img 
                src={originPost.images[0]} 
                className="w-16 h-16 object-cover rounded-md flex-shrink-0 bg-gray-200 ml-2" 
                alt="ref-preview"
              />
            )}
            <div className={`flex-1 min-w-0 overflow-hidden flex flex-col justify-center ${(!originPost.images || originPost.images.length === 0) ? 'ml-2' : ''}`}>
              <div className="font-semibold text-sm text-gray-900 truncate">
                @{originPost.user.username}
              </div>
              <p className="text-xs text-gray-600 line-clamp-2 mt-0.5">
                {originPost.content || "Shared a post"}
              </p>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-gray-50 flex items-center justify-between">
            {/* Left: Visibility Selector */}
            <div className="relative" ref={visibilityMenuRef}>
              <button 
                onClick={() => setShowVisibilityMenu(!showVisibilityMenu)}
                className="flex items-center gap-2 text-sm text-blue-600 bg-blue-50 hover:bg-blue-100 px-3 py-1.5 rounded-full transition-colors font-medium"
              >
                <VisIcon size={14} />
                <span>{currentVisOption.label}</span>
                <ChevronDown size={14} className={`transition-transform duration-200 ${showVisibilityMenu ? 'rotate-180' : ''}`}/>
              </button>

              {showVisibilityMenu && (
                <div className="absolute left-0 bottom-full mb-2 w-40 bg-white rounded-lg shadow-xl border border-gray-100 py-1 z-20 animate-in fade-in zoom-in-95 duration-100">
                  {visibilityOptions.map((opt) => (
                    <button
                      key={opt.value}
                      onClick={() => { setVisibility(opt.value); setShowVisibilityMenu(false); }}
                      className={`w-full text-left px-3 py-2 text-sm flex items-center gap-2 hover:bg-gray-50 ${visibility === opt.value ? 'text-blue-600 font-medium bg-blue-50' : 'text-gray-700'}`}
                    >
                      <opt.icon size={14} />
                      {opt.label}
                    </button>
                  ))}
                </div>
              )}
            </div>

            {/* Right: Action Buttons */}
            <div className="flex gap-3">
              <button onClick={onClose} className="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-full transition-colors">
                  Cancel
              </button>
              <button 
                  onClick={handleSubmit}
                  disabled={isSubmitting}
                  className="bg-blue-600 text-white px-6 py-2 rounded-full font-medium text-sm hover:bg-blue-700 disabled:opacity-50 flex items-center gap-2 transition-all shadow-sm"
              >
                  {isSubmitting ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}
                  <span>{content.trim() ? 'Quote' : 'Repost'}</span>
              </button>
            </div>
        </div>
      </div>
    </div>
  );
}