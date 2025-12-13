'use client';

import { useState, useRef, ChangeEvent, DragEvent } from 'react';
import { PostVisibility, Post, CreatePostRequest, PostType } from '@/types';
import api from '@/lib/api';
import { Image as ImageIcon, Send, X, Globe, Lock, UploadCloud, Loader2 } from 'lucide-react';
import { toast } from 'react-hot-toast';
import { convertToJpegIfNeeded } from '@/lib/imageUtils';

interface CreatePostWidgetProps {
  onPostCreated: (newPost: Post) => void;
}

interface ImageFile {
  file: File;
  previewUrl: string;
}

export default function CreatePostWidget({ onPostCreated }: CreatePostWidgetProps) {
  const [content, setContent] = useState('');
  const [selectedFiles, setSelectedFiles] = useState<ImageFile[]>([]);
  const [visibility, setVisibility] = useState<PostVisibility>(PostVisibility.PUBLIC);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false); // New state for conversion loading
  const [isDragOver, setIsDragOver] = useState(false);
  
  const fileInputRef = useRef<HTMLInputElement>(null);

  // --- 1. 文件校验与预览处理 (Updated with Conversion) ---
  const processFiles = async (files: FileList | File[]) => {
    const maxFiles = 9;
    const maxSize = 10 * 1024 * 1024; // 10MB

    if (selectedFiles.length + files.length > maxFiles) {
      toast.error(`Max ${maxFiles} images allowed`);
      return;
    }

    setIsProcessing(true); // Start loading state

    try {
      const processedPromises = Array.from(files).map(async (file) => {
        if (!file.type.startsWith('image/')) {
          toast.error(`${file.name} is not an image`);
          return null;
        }
        
        // Check size BEFORE conversion (rough check)
        if (file.size > maxSize) {
          toast.error(`${file.name} is too large (>10MB)`);
          return null;
        }

        try {
          // Convert if necessary
          const finalFile = await convertToJpegIfNeeded(file);
          
          // Check size AFTER conversion (optional, but good practice)
          if (finalFile.size > maxSize) {
             toast.error(`${file.name} converted size is too large`);
             return null;
          }

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
        setSelectedFiles((prev) => [...prev, ...validFiles]);
      }
    } finally {
      setIsProcessing(false); // End loading state
    }
  };

  // --- 2. 独立上传图片逻辑 ---
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

  // --- 3. 提交总逻辑 (JSON) ---
  const handleSubmit = async () => {
    if (!content.trim() && selectedFiles.length === 0) {
      toast.error("Please enter content or add an image");
      return;
    }

    setIsSubmitting(true);

    try {
      let imageUrls: string[] = [];

      if (selectedFiles.length > 0) {
        try {
            const files = selectedFiles.map(f => f.file);
            imageUrls = await uploadImages(files);
        } catch (uploadError: any) {
            toast.error(uploadError.message || "Failed to upload images");
            setIsSubmitting(false);
            return; 
        }
      }

      const payload: CreatePostRequest = {
        content,
        images: imageUrls, 
        visibility,
        type: PostType.ORIGINAL
      };

      const res = await api.post('/posts', payload);

      if (res.data.code === 0) {
        toast.success("Post created!");
        
        selectedFiles.forEach(f => URL.revokeObjectURL(f.previewUrl));
        setContent('');
        setSelectedFiles([]);
        
        onPostCreated(res.data.data);
      } else {
        toast.error(res.data.message || "Failed to create post");
      }
    } catch (e) {
      console.error(e);
      toast.error("Network error");
    } finally {
      setIsSubmitting(false);
    }
  };

  // --- UI 事件处理 ---
  const handleFileSelect = (e: ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      processFiles(e.target.files);
    }
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleDragOver = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragOver(true);
  };

  const handleDragLeave = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragOver(false);
  };

  const handleDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragOver(false);
    if (e.dataTransfer.files?.length > 0) {
      processFiles(e.dataTransfer.files);
    }
  };

  const removeImage = (index: number) => {
    setSelectedFiles(prev => {
      const newFiles = [...prev];
      URL.revokeObjectURL(newFiles[index].previewUrl);
      newFiles.splice(index, 1);
      return newFiles;
    });
  };

  return (
    <div className="bg-white rounded-xl shadow p-4 mb-6">
      <input 
        type="file" 
        multiple 
        accept="image/*" 
        className="hidden" 
        ref={fileInputRef} 
        onChange={handleFileSelect} 
      />

      <div className="flex gap-4">
        <textarea
          className="w-full bg-gray-50 border-0 rounded-lg p-3 text-gray-900 focus:ring-2 focus:ring-blue-100 focus:bg-white transition-all resize-none h-24"
          placeholder="What's on your mind?"
          value={content}
          onChange={(e) => setContent(e.target.value)}
        />
      </div>

      {/* 图片预览 */}
      {(selectedFiles.length > 0 || isProcessing) && (
        <div className="flex gap-2 mt-3 overflow-x-auto pb-2 scrollbar-thin scrollbar-thumb-gray-200 items-center">
          {selectedFiles.map((img, idx) => (
            <div key={idx} className="relative w-20 h-20 flex-shrink-0 group">
              <img 
                src={img.previewUrl} 
                className="w-full h-full object-cover rounded-lg border border-gray-200" 
                alt="preview" 
              />
              <button 
                onClick={() => removeImage(idx)}
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

      {/* 底部操作栏 */}
      <div className="flex flex-col sm:flex-row items-center justify-between mt-4 pt-3 border-t border-gray-50 gap-3">
        
        <div className="flex items-center gap-2 w-full sm:w-auto">
          <button 
            onClick={() => fileInputRef.current?.click()}
            disabled={isProcessing}
            className="p-2 text-blue-500 hover:bg-blue-50 rounded-full transition-colors relative group disabled:opacity-50"
            title="Upload Image"
          >
            <ImageIcon size={20} />
          </button>
          
          <div className="relative flex items-center">
            <select 
              value={visibility}
              onChange={(e) => setVisibility(e.target.value as PostVisibility)}
              className="appearance-none bg-gray-50 text-xs text-gray-600 pl-8 pr-3 py-1.5 rounded-full border border-gray-200 focus:outline-none cursor-pointer hover:bg-gray-100"
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
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
          className={`flex-1 mx-2 h-10 border-2 border-dashed rounded-lg flex items-center justify-center text-xs text-gray-400 cursor-pointer transition-all duration-200 w-full sm:w-auto
            ${isDragOver ? 'border-blue-500 bg-blue-50 text-blue-600' : 'border-gray-200 hover:border-blue-300 hover:bg-gray-50'}
            ${isProcessing ? 'opacity-50 cursor-not-allowed' : ''}
          `}
        >
          <UploadCloud size={16} className="mr-2" />
          <span className="hidden sm:inline">Drop images here or click</span>
          <span className="sm:hidden">Upload</span>
        </div>

        <button 
          onClick={handleSubmit}
          disabled={isSubmitting || isProcessing || (!content.trim() && selectedFiles.length === 0)}
          className="bg-blue-600 text-white px-6 py-2 rounded-full font-medium text-sm hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 transition-all shadow-sm hover:shadow"
        >
          {(isSubmitting || isProcessing) ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}
          <span>Post</span>
        </button>
      </div>
    </div>
  );
}