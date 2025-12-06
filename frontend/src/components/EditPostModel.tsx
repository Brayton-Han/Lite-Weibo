'use client';

import { useState, useRef, useEffect, ChangeEvent, DragEvent } from 'react';
import { PostVisibility, Post, UpdatePostRequest } from '@/types';
import api from '@/lib/api';
import { Image as ImageIcon, X, Globe, Lock, UploadCloud, Loader2, Save } from 'lucide-react';
import { toast } from 'react-hot-toast';
import { convertToJpegIfNeeded } from '@/lib/imageUtils';

interface EditPostModalProps {
  isOpen: boolean;
  onClose: () => void;
  post: Post;
  onPostUpdated: (updatedPost: Post) => void;
}

interface ImageFile {
  file: File;
  previewUrl: string;
}

export default function EditPostModal({ isOpen, onClose, post, onPostUpdated }: EditPostModalProps) {
  const [content, setContent] = useState('');
  // 分开管理：原有的远程图片URL 和 新选择的本地文件
  const [existingImages, setExistingImages] = useState<string[]>([]);
  const [newFiles, setNewFiles] = useState<ImageFile[]>([]);
  
  const [visibility, setVisibility] = useState<PostVisibility>(PostVisibility.PUBLIC);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [isDragOver, setIsDragOver] = useState(false);
  
  const fileInputRef = useRef<HTMLInputElement>(null);

  // --- 1. 初始化数据 ---
  useEffect(() => {
    if (isOpen && post) {
      setContent(post.content);
      setVisibility(post.visibility);
      setExistingImages(post.images || []);
      setNewFiles([]); // 重置新文件
      setIsSubmitting(false);
      setIsProcessing(false);
    }
  }, [isOpen, post]);

  // --- 2. 文件处理逻辑 (复用 CreatePostWidget) ---
  const processFiles = async (files: FileList | File[]) => {
    const maxFiles = 9;
    const maxSize = 10 * 1024 * 1024; // 10MB
    const currentTotal = existingImages.length + newFiles.length;

    if (currentTotal + files.length > maxFiles) {
      toast.error(`Max ${maxFiles} images allowed`);
      return;
    }

    setIsProcessing(true);

    try {
      const processedPromises = Array.from(files).map(async (file) => {
        if (!file.type.startsWith('image/')) {
          toast.error(`${file.name} is not an image`);
          return null;
        }
        
        if (file.size > maxSize) {
          toast.error(`${file.name} is too large (>10MB)`);
          return null;
        }

        try {
          const finalFile = await convertToJpegIfNeeded(file);
          return {
            file: finalFile,
            previewUrl: URL.createObjectURL(finalFile)
          } as ImageFile;
        } catch (e) {
          console.error("Conversion failed", e);
          return null;
        }
      });

      const results = await Promise.all(processedPromises);
      const validFiles = results.filter((f): f is ImageFile => f !== null);

      if (validFiles.length > 0) {
        setNewFiles((prev) => [...prev, ...validFiles]);
      }
    } finally {
      setIsProcessing(false);
    }
  };

  // --- 3. 上传新图片逻辑 ---
  const uploadImages = async (files: File[]): Promise<string[]> => {
    if (files.length === 0) return [];
    
    const formData = new FormData();
    files.forEach(file => {
      formData.append('file', file); 
    });

    const res = await api.post('/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });

    if (res.data.code === 0) {
      return res.data.data;
    } else {
      throw new Error(res.data.message || 'Image upload failed');
    }
  };

  // --- 4. 提交更新逻辑 (UpdatePostRequest) ---
  const handleSubmit = async () => {
    if (!content.trim() && existingImages.length === 0 && newFiles.length === 0) {
      toast.error("Content or images cannot be empty");
      return;
    }

    setIsSubmitting(true);

    try {
      let newlyUploadedUrls: string[] = [];

      // 1. 如果有新文件，先上传
      if (newFiles.length > 0) {
        try {
            const files = newFiles.map(f => f.file);
            newlyUploadedUrls = await uploadImages(files);
        } catch (uploadError: any) {
            toast.error(uploadError.message || "Failed to upload new images");
            setIsSubmitting(false);
            return; 
        }
      }

      // 2. 合并：保留的旧图片 + 新上传的图片
      const finalImages = [...existingImages, ...newlyUploadedUrls];

      // 3. 构造 UpdatePostRequest
      const payload: UpdatePostRequest = {
        content,
        images: finalImages, 
        visibility
      };

      // 4. 发送更新请求
      // 假设后端接口为 PUT /posts/{id}
      const res = await api.put(`/posts/${post.id}`, payload);

      if (res.data.code === 0) {
        toast.success("Post updated!");
        
        // 清理资源
        newFiles.forEach(f => URL.revokeObjectURL(f.previewUrl));
        
        onPostUpdated(res.data.data); // 更新父组件状态
        onClose(); // 关闭模态框
      } else {
        toast.error(res.data.message || "Failed to update post");
      }
    } catch (e) {
      console.error(e);
      toast.error("Network error");
    } finally {
      setIsSubmitting(false);
    }
  };

  // --- UI Helpers ---
  const removeExistingImage = (index: number) => {
    setExistingImages(prev => prev.filter((_, i) => i !== index));
  };

  const removeNewFile = (index: number) => {
    setNewFiles(prev => {
      const newArr = [...prev];
      URL.revokeObjectURL(newArr[index].previewUrl);
      newArr.splice(index, 1);
      return newArr;
    });
  };

  const handleDragEvents = (e: DragEvent<HTMLDivElement>, isOver: boolean) => {
    e.preventDefault();
    setIsDragOver(isOver);
    if (!isOver && e.dataTransfer.files?.length > 0) {
      processFiles(e.dataTransfer.files);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4">
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-2xl max-h-[90vh] overflow-y-auto flex flex-col">
        
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-gray-100">
          <h2 className="text-lg font-semibold text-gray-800">Edit Post</h2>
          <button onClick={onClose} className="p-2 hover:bg-gray-100 rounded-full transition-colors">
            <X size={20} className="text-gray-500" />
          </button>
        </div>

        {/* Body */}
        <div className="p-4 flex-1">
          <textarea
            className="w-full bg-gray-50 border-0 rounded-lg p-3 text-gray-900 focus:ring-2 focus:ring-blue-100 focus:bg-white transition-all resize-none h-32"
            placeholder="Edit your content..."
            value={content}
            onChange={(e) => setContent(e.target.value)}
          />

          {/* 图片预览区域 (混合显示旧图和新图) */}
          {(existingImages.length > 0 || newFiles.length > 0 || isProcessing) && (
            <div className="flex gap-2 mt-3 overflow-x-auto pb-2 scrollbar-thin scrollbar-thumb-gray-200 items-center">
              
              {/* 1. 现有的远程图片 */}
              {existingImages.map((url, idx) => (
                <div key={`existing-${idx}`} className="relative w-20 h-20 flex-shrink-0 group">
                  <img 
                    src={url} 
                    className="w-full h-full object-cover rounded-lg border border-gray-200" 
                    alt="existing" 
                  />
                  <button 
                    onClick={() => removeExistingImage(idx)}
                    className="absolute -top-1.5 -right-1.5 bg-gray-800 text-white rounded-full p-1 shadow-md hover:bg-red-500 transition-colors opacity-90"
                  >
                    <X size={12} />
                  </button>
                </div>
              ))}

              {/* 2. 新上传的本地预览 */}
              {newFiles.map((img, idx) => (
                <div key={`new-${idx}`} className="relative w-20 h-20 flex-shrink-0 group">
                  <img 
                    src={img.previewUrl} 
                    className="w-full h-full object-cover rounded-lg border border-green-200 ring-2 ring-green-100" 
                    alt="new preview" 
                  />
                  <div className="absolute bottom-0 left-0 right-0 bg-green-500/80 text-white text-[10px] text-center py-0.5 rounded-b-lg">New</div>
                  <button 
                    onClick={() => removeNewFile(idx)}
                    className="absolute -top-1.5 -right-1.5 bg-gray-800 text-white rounded-full p-1 shadow-md hover:bg-red-500 transition-colors opacity-90"
                  >
                    <X size={12} />
                  </button>
                </div>
              ))}

              {isProcessing && (
                 <div className="w-20 h-20 flex-shrink-0 flex items-center justify-center bg-gray-50 rounded-lg border border-gray-200">
                   <Loader2 className="animate-spin text-blue-500" size={24} />
                 </div>
              )}
            </div>
          )}
        </div>

        {/* Footer / Controls */}
        <div className="p-4 border-t border-gray-50 bg-gray-50/50 rounded-b-xl flex flex-col sm:flex-row items-center justify-between gap-3">
          
          <input 
            type="file" 
            multiple 
            accept="image/*" 
            className="hidden" 
            ref={fileInputRef} 
            onChange={(e) => {
              if (e.target.files && e.target.files.length > 0) processFiles(e.target.files);
              e.target.value = '';
            }} 
          />

          <div className="flex items-center gap-2 w-full sm:w-auto">
            <button 
              onClick={() => fileInputRef.current?.click()}
              disabled={isProcessing}
              className="p-2 text-blue-500 hover:bg-blue-100 rounded-full transition-colors relative group disabled:opacity-50"
              title="Add more images"
            >
              <ImageIcon size={20} />
            </button>
            
            <div className="relative flex items-center">
              <select 
                value={visibility}
                onChange={(e) => setVisibility(e.target.value as PostVisibility)}
                className="appearance-none bg-white text-xs text-gray-600 pl-8 pr-3 py-1.5 rounded-full border border-gray-200 focus:outline-none cursor-pointer hover:bg-gray-50 shadow-sm"
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

          {/* 拖拽区域 */}
          <div 
            onClick={() => !isProcessing && fileInputRef.current?.click()}
            onDragOver={(e) => handleDragEvents(e, true)}
            onDragLeave={(e) => handleDragEvents(e, false)}
            onDrop={(e) => handleDragEvents(e, false)}
            className={`flex-1 mx-0 sm:mx-2 h-10 border-2 border-dashed rounded-lg flex items-center justify-center text-xs text-gray-400 cursor-pointer transition-all duration-200 w-full sm:w-auto
              ${isDragOver ? 'border-blue-500 bg-blue-50 text-blue-600' : 'border-gray-300 hover:border-blue-300 hover:bg-white'}
              ${isProcessing ? 'opacity-50 cursor-not-allowed' : ''}
            `}
          >
            <UploadCloud size={16} className="mr-2" />
            <span className="hidden sm:inline">Add images</span>
            <span className="sm:hidden">Add</span>
          </div>

          <div className="flex gap-2 w-full sm:w-auto justify-end">
            <button
                onClick={onClose}
                className="px-4 py-2 text-sm text-gray-600 hover:bg-gray-200 rounded-full transition-colors"
            >
                Cancel
            </button>
            <button 
                onClick={handleSubmit}
                disabled={isSubmitting || isProcessing}
                className="bg-blue-600 text-white px-6 py-2 rounded-full font-medium text-sm hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 transition-all shadow-sm"
            >
                {(isSubmitting || isProcessing) ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />}
                <span>Save</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}