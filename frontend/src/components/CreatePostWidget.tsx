'use client';

import { useState } from 'react';
import { PostVisibility, Post, CreatePostRequest } from '@/types';
import api from '@/lib/api';
import { Image as ImageIcon, Send, X, Globe, Lock } from 'lucide-react';
import { toast } from 'react-hot-toast';

interface CreatePostWidgetProps {
  onPostCreated: (newPost: Post) => void;
}

export default function CreatePostWidget({ onPostCreated }: CreatePostWidgetProps) {
  const [content, setContent] = useState('');
  const [imageUrlInput, setImageUrlInput] = useState(''); // 临时存储输入的URL
  const [images, setImages] = useState<string[]>([]);
  const [visibility, setVisibility] = useState<PostVisibility>(PostVisibility.PUBLIC);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [showImgInput, setShowImgInput] = useState(false);

  const handleAddImage = () => {
    if (!imageUrlInput.trim()) return;
    if (images.length >= 9) {
      toast.error("Max 9 images allowed");
      return;
    }
    setImages([...images, imageUrlInput.trim()]);
    setImageUrlInput('');
    setShowImgInput(false);
  };

  const handleSubmit = async () => {
    if (!content.trim() && images.length === 0) {
      toast.error("Please enter content or add an image");
      return;
    }

    setIsSubmitting(true);
    const payload: CreatePostRequest = {
      content,
      images,
      visibility
    };

    try {
      const res = await api.post('/posts', payload);
      if (res.data.code === 0) {
        toast.success("Post created!");
        setContent('');
        setImages([]);
        onPostCreated(res.data.data); // 回调通知父组件
      } else {
        toast.error(res.data.message);
      }
    } catch (e) {
      toast.error("Failed to create post");
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="bg-white rounded-xl shadow p-4 mb-6">
      <div className="flex gap-4">
        <textarea
          className="w-full bg-gray-50 border-0 rounded-lg p-3 text-gray-900 focus:ring-2 focus:ring-blue-100 focus:bg-white transition-all resize-none h-24"
          placeholder="What's on your mind?"
          value={content}
          onChange={(e) => setContent(e.target.value)}
        />
      </div>

      {/* 图片预览区域 */}
      {images.length > 0 && (
        <div className="flex gap-2 mt-3 overflow-x-auto pb-2">
          {images.map((img, idx) => (
            <div key={idx} className="relative w-16 h-16 flex-shrink-0">
              <img src={img} className="w-full h-full object-cover rounded-md border border-gray-200" />
              <button 
                onClick={() => setImages(images.filter((_, i) => i !== idx))}
                className="absolute -top-1 -right-1 bg-gray-800 text-white rounded-full p-0.5 hover:bg-red-500"
              >
                <X size={12} />
              </button>
            </div>
          ))}
        </div>
      )}

      {/* 图片 URL 输入框 (模拟上传) */}
      {showImgInput && (
        <div className="mt-3 flex gap-2">
          <input 
            type="text" 
            value={imageUrlInput}
            onChange={(e) => setImageUrlInput(e.target.value)}
            placeholder="Paste image URL here..."
            className="flex-1 text-sm border border-gray-200 rounded px-2 py-1"
          />
          <button onClick={handleAddImage} className="text-xs bg-gray-100 px-3 rounded hover:bg-gray-200">Add</button>
        </div>
      )}

      <div className="flex items-center justify-between mt-4 pt-3 border-t border-gray-50">
        <div className="flex gap-2">
          <button 
            onClick={() => setShowImgInput(!showImgInput)}
            className="p-2 text-blue-500 hover:bg-blue-50 rounded-full transition-colors"
            title="Add Image URL"
          >
            <ImageIcon size={20} />
          </button>
          
          <div className="relative flex items-center">
            <select 
              value={visibility}
              onChange={(e) => setVisibility(e.target.value as PostVisibility)}
              className="appearance-none bg-gray-50 text-xs text-gray-600 pl-8 pr-3 py-1.5 rounded-full border border-gray-200 focus:outline-none cursor-pointer"
            >
              <option value={PostVisibility.PUBLIC}>Public</option>
              <option value={PostVisibility.FOLLOWERS}>Followers</option>
              <option value={PostVisibility.FRIENDS}>Friends</option>
              <option value={PostVisibility.PRIVATE}>Private</option>
            </select>
            <div className="absolute left-2.5 text-gray-400 pointer-events-none">
                {visibility === PostVisibility.PUBLIC ? <Globe size={14}/> : <Lock size={14}/>}
            </div>
          </div>
        </div>

        <button 
          onClick={handleSubmit}
          disabled={isSubmitting}
          className="bg-blue-600 text-white px-6 py-2 rounded-full font-medium text-sm hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
        >
          {isSubmitting ? 'Posting...' : <><Send size={16} /> Post</>}
        </button>
      </div>
    </div>
  );
}