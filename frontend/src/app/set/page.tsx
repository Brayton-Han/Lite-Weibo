'use client';

import { useState, useEffect, useRef, ChangeEvent } from 'react';
import { useRouter } from 'next/navigation';
import api from '@/lib/api';
import { ApiResponse, User, Gender } from '@/types';
import { toast } from 'react-hot-toast';
import { ArrowLeft, Camera, Loader2, Save } from 'lucide-react';
import { convertToJpegIfNeeded } from '@/lib/imageUtils';



export default function SettingsPage() {
  const router = useRouter();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [loading, setLoading] = useState(true); // 页面初始加载
  const [isSaving, setIsSaving] = useState(false); // 保存按钮 Loading
  const [user, setUser] = useState<User | null>(null);

  // 表单状态
  const [formData, setFormData] = useState<{
    username: string;
    gender: Gender | '';
    bio: string;
    birthday: string;
    avatarUrl: string;
  }>({
    username: '',
    gender: '',
    bio: '',
    birthday: '',
    avatarUrl: ''
  });

  // 图片上传相关的临时状态
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string>(''); // 本地预览 URL (Blob)

  // 1. 初始化：加载当前用户信息
  useEffect(() => {
    const fetchMe = async () => {
      const userId = localStorage.getItem('userId');
      if (!userId) {
        toast.error("Please login first");
        router.push('/login');
        return;
      }

      try {
        const res = await api.get(`/user/${userId}`);
        const u = res.data.data;
        setUser(u);

        setFormData({
          username: u.username || '',
          gender: u.gender || '',
          bio: u.bio || '',
          birthday: u.birthday || '',
          avatarUrl: u.avatarUrl || '' 
        });
      } catch (error) {
        console.error("Failed to fetch user", error);
        toast.error("Failed to load user data");
      } finally {
        setLoading(false);
      }
    };
    fetchMe();
  }, [router]);

  // 2. 表单输入处理
  const handleInputChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>
  ) => {
    const { name, value } = e.target;
    setFormData(prev => ({
      ...prev,
      [name]: name === 'gender' ? (value as Gender) : value
    }));
  };

  // 3. 处理图片选择 (生成本地预览，暂不上传)
  const handleFileChange = async (e: ChangeEvent<HTMLInputElement>) => {
    const originalFile = e.target.files?.[0];
    if (!originalFile) return;

    // Type check before conversion
    if (!originalFile.type.startsWith('image/')) {
        toast.error("Please select an image file");
        return;
    }

    try {
      // Process/Convert image
      const file = await convertToJpegIfNeeded(originalFile);

      // Check size AFTER conversion
      if (file.size > 10 * 1024 * 1024) { // 10MB
          toast.error("Image size must be less than 10MB");
          return;
      }

      // Cleanup old preview if exists
      if (previewUrl) URL.revokeObjectURL(previewUrl);

      // 生成本地预览
      const objectUrl = URL.createObjectURL(file);
      setPreviewUrl(objectUrl);
      setSelectedFile(file); // Store the converted file for later upload

    } catch (error) {
      console.error(error);
      toast.error("Failed to process image");
    } finally {
      // 清空 input 允许重复选择
      e.target.value = '';
    }
  };

  // 4. 独立上传图片函数 (返回后端 URL)
  const uploadAvatarImage = async (file: File): Promise<string> => {
    const formData = new FormData();
    formData.append('file', file); // 假设后端接受字段名为 'file'

    const res = await api.post('/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });

    if (res.data.code === 0 && res.data.data && res.data.data.length > 0) {
      // 假设返回结构 { code: 0, data: ["https://bucket.../img.jpg"] }
      return res.data.data[0];
    } else {
      throw new Error(res.data.message || "Image upload failed");
    }
  };

  // 5. 保存总逻辑
  const handleSave = async () => {
  if (!user) return;
  if (!formData.username.trim()) {
      toast.error("Username is required");
      return;
  }
  
  setIsSaving(true);

  try {
    let finalAvatarUrl = formData.avatarUrl;

    // 1. 上传图片 (这部分逻辑没问题)
    if (selectedFile) {
      try {
        finalAvatarUrl = await uploadAvatarImage(selectedFile);
      } catch (uploadError: any) {
        toast.error(uploadError.message || "Failed to upload avatar");
        setIsSaving(false);
        return; // 图片上传失败则终止
      }
    }

    // 2. 关键修复：清洗数据 (Payload Sanitization)
    // 将空字符串转换为 null，避免后端解析 Date/Enum 报错
    const updatePayload = {
        username: formData.username,
        bio: formData.bio,
        avatarUrl: finalAvatarUrl,
        // 如果是空字符串，传 null (或者 undefined，取决于你的 axios 配置和后端需求)
        gender: formData.gender === '' ? null : formData.gender,
        birthday: formData.birthday === '' ? null : formData.birthday
    };

    // 或者，如果你只想发送有值的字段 (更安全)：
    // const cleanPayload: any = { ...updatePayload };
    // if (!cleanPayload.gender) delete cleanPayload.gender;
    // if (!cleanPayload.birthday) delete cleanPayload.birthday;

    const res = await api.put<ApiResponse<string>>('/set', updatePayload);
    const response = res.data;

    if (response.code === 0) {
      toast.success(response.message || 'Profile updated successfully');
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      
      // 触发全局事件，确保 Navbar 等组件也能更新头像 (就像 UserProfileClient 做的那样)
      window.dispatchEvent(new Event('user-profile-updated'));

      router.push(`/user/${user.id}`);
    } else {
      toast.error(response.message || 'Failed to update profile');
    }
  } catch (error) {
    console.error(error);
    toast.error('Failed to save profile');
  } finally {
    setIsSaving(false);
  }
};

  const handleCancel = () => {
    if (user) router.push(`/user/${user.id}`);
    else router.back();
  };

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 className="animate-spin text-blue-600 w-8 h-8" />
        <span className="ml-2 text-gray-500">Loading settings...</span>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50 py-10">
      <div className="max-w-2xl mx-auto px-4">
        
        {/* Header with Back Button */}
        <div className="mb-6 flex items-center">
          <button 
            onClick={handleCancel}
            className="mr-4 p-2 rounded-full hover:bg-gray-200 text-gray-600 transition-colors"
          >
            <ArrowLeft size={24} />
          </button>
          <h1 className="text-2xl font-bold text-gray-900">Edit Profile</h1>
        </div>

        {/* Form Card */}
        <div className="bg-white rounded-xl shadow overflow-hidden animate-in fade-in duration-300">
          <div className="p-6 space-y-6">
            
            {/* --- Avatar Upload Section --- */}
            <div className="flex flex-col items-center justify-center mb-6 space-y-4">
              <div 
                className="relative group cursor-pointer"
                onClick={() => fileInputRef.current?.click()}
              >
                {/* 优先显示本地预览，其次显示表单中的URL，最后默认图 */}
                <img 
                  src={previewUrl || formData.avatarUrl || '/default-avatar.png'} 
                  alt="Avatar Preview" 
                  className="w-24 h-24 rounded-full object-cover border-4 border-gray-100 shadow-sm transition-opacity group-hover:opacity-90"
                  onError={(e) => { (e.target as HTMLImageElement).src = '/default-avatar.png'; }}
                />
                
                {/* Hover Overlay */}
                <div className="absolute inset-0 flex items-center justify-center bg-black/40 rounded-full opacity-0 group-hover:opacity-100 transition-opacity">
                    <Camera className="text-white w-8 h-8" />
                </div>
              </div>
              
              <input 
                  type="file" 
                  ref={fileInputRef} 
                  onChange={handleFileChange} 
                  accept="image/*" 
                  className="hidden" 
              />
            </div>
            {/* ----------------------------- */}

            {/* Username */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Username</label>
              <input 
                type="text" 
                name="username"
                value={formData.username} 
                onChange={handleInputChange} 
                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 transition-shadow" 
                placeholder="Enter your username"
              />
            </div>

            {/* Gender */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Gender</label>
              <select 
                name="gender"
                value={formData.gender} 
                onChange={handleInputChange} 
                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 transition-shadow bg-white"
              >
                <option value="">Select Gender</option>
                <option value="MALE">Male</option>
                <option value="FEMALE">Female</option>
                <option value="NON_BINARY">Non-binary</option>
                <option value="OTHER">Other</option>
              </select>
            </div>

            {/* Birthday */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Birthday</label>
              <input 
                type="date" 
                name="birthday"
                value={formData.birthday} 
                onChange={handleInputChange} 
                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 transition-shadow" 
              />
            </div>

            {/* Bio */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Bio</label>
              <textarea 
                name="bio"
                rows={4}
                value={formData.bio} 
                onChange={handleInputChange} 
                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 transition-shadow resize-none" 
                placeholder="Tell us a little about yourself..."
              />
            </div>

          </div>

          {/* Action Buttons */}
          <div className="px-6 py-4 bg-gray-50 border-t border-gray-100 flex justify-end space-x-3">
            <button 
              onClick={handleCancel} 
              disabled={isSaving}
              className="px-6 py-2.5 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-gray-200 transition-colors disabled:opacity-50"
            >
              Cancel
            </button>
            <button 
              onClick={handleSave} 
              disabled={isSaving}
              className="px-6 py-2.5 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 transition-colors shadow-sm disabled:opacity-50 flex items-center gap-2"
            >
              {isSaving ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />}
              <span>{isSaving ? 'Saving...' : 'Save Changes'}</span>
            </button>
          </div>
        </div>

      </div>
    </div>
  );
}