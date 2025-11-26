'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import api from '@/lib/api';
import { ApiResponse, User, Gender } from '@/types';
import { toast } from 'react-hot-toast';
import { ArrowLeft } from 'lucide-react';

export default function SettingsPage() {
  const router = useRouter();
  const [loading, setLoading] = useState(true);
  const [user, setUser] = useState<User | null>(null);

  // 表单状态 (现在包含 avatarUrl)
  const [formData, setFormData] = useState<{
    username: string;
    gender: Gender | '';
    bio: string;
    birthday: string;
    avatarUrl: string; // 新增字段
  }>({
    username: '',
    gender: '',
    bio: '',
    birthday: '',
    avatarUrl: ''
  });

  // 初始化：加载当前用户信息
  useEffect(() => {
    const fetchMe = async () => {
      const userId = localStorage.getItem('userId');
      if (!userId) return;

      try {
        const res = await api.get(`/user/${userId}`);
        const u = res.data.data;
        setUser(u);

        setFormData({
          username: u.username || '',
          gender: u.gender || '',
          bio: u.bio || '',
          birthday: u.birthday || '',
          avatarUrl: u.avatarUrl || '' // 初始化头像链接
        });
      } catch (error) {
        console.error("Failed to fetch user", error);
        toast.error("Failed to load user data");
      } finally {
        setLoading(false);
      }
    };
    fetchMe();
  }, []);

  const handleInputChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>
  ) => {
    const { name, value } = e.target;
    setFormData(prev => ({
      ...prev,
      [name]: name === 'gender' ? (value as Gender) : value
    }));
  };

  const handleSave = async () => {
    if (!user) return;
    
    try {
      // 直接发送 JSON 数据，不再使用 FormData 上传文件
      const res = await api.put<ApiResponse<string>>('/set', formData);
      const response = res.data;

      if (response.code === 0) {
        toast.success(response.message || 'Profile updated successfully');
        // 保存成功后跳转回个人主页
        router.push(`/user/${user.id}`);
      } else {
        toast.error(response.message || 'Failed to update profile');
      }
    } catch (error) {
      console.error(error);
      toast.error('Failed to save profile');
    }
  };

  const handleCancel = () => {
    if (user) {
      router.push(`/user/${user.id}`);
    } else {
      router.back();
    }
  };

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <div className="text-gray-500">Loading settings...</div>
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
            
            {/* Avatar Preview & Input Section */}
            <div className="flex flex-col items-center justify-center mb-6 space-y-4">
              <div className="relative">
                <img 
                  // 如果输入框有值则显示输入的值，否则显示默认图
                  src={formData.avatarUrl || '/default-avatar.png'} 
                  alt="Avatar Preview" 
                  className="w-24 h-24 rounded-full object-cover border-4 border-gray-100"
                  onError={(e) => { 
                    // 如果输入的图片链接无效，回退到默认图
                    (e.target as HTMLImageElement).src = '/default-avatar.png'; 
                  }}
                />
              </div>
              
              {/* Avatar Url Input - 修改为文本输入框 */}
              <div className="w-full max-w-xs">
                <label className="block text-sm font-medium text-gray-700 mb-1 text-center">Avatar Image Name / URL</label>
                <input 
                  type="text" 
                  name="avatarUrl"
                  value={formData.avatarUrl} 
                  onChange={handleInputChange} 
                  className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 transition-shadow text-center" 
                  placeholder="e.g. avatar1.png or https://..."
                />
              </div>
            </div>

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
              className="px-6 py-2.5 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-gray-200 transition-colors"
            >
              Cancel
            </button>
            <button 
              onClick={handleSave} 
              className="px-6 py-2.5 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 transition-colors shadow-sm"
            >
              Save Changes
            </button>
          </div>
        </div>

      </div>
    </div>
  );
}