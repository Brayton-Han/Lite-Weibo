'use client';

import { useEffect, useState } from 'react';
import api from '@/lib/api';
import { ApiResponse, User, Gender } from '@/types';
import { useRouter } from 'next/navigation';
import { toast } from 'react-hot-toast';

export default function MePage() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  
  // 编辑模式状态
  const [isEditing, setIsEditing] = useState(false);
  // 定义表单状态
  const [editForm, setEditForm] = useState<{
    username: string;
    gender: Gender | ''; // 允许是枚举值，或者是空字符串(用于select默认选项)
    bio: string;
    birthday: string;
  }>({
    username: '',
    gender: '',
    bio: '',
    birthday: ''
  });

  const router = useRouter();

  useEffect(() => {
    const fetchMe = async () => {
        const res = await api.get<ApiResponse<User>>('/me');
        if (res.data.code === 0) {
          setUser(res.data.data);
        } else {
          toast.error(res.data.message || 'Failed to load user info');
        }
      
        setLoading(false);
    };

    fetchMe();
  }, []);

  const handleLogout = () => {
    localStorage.removeItem('token');
    router.push('/login');
  };

  // 打开编辑框并初始化数据
  const openEditModal = () => {
    if (user) {
      setEditForm({
        username: user.username || '',
        // 如果 user.gender 是 null，就转成空字符串给 select 用
        gender: user.gender || '', 
        bio: user.bio || '',
        birthday: user.birthday || ''
      });
      setIsEditing(true);
    }
  };

  // 处理表单输入
  const handleInputChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>
  ) => {
    const { name, value } = e.target;
    
    setEditForm(prev => ({
      ...prev,
      // 如果正在修改 gender，将字符串值断言为 Gender 类型
      [name]: name === 'gender' ? (value as Gender) : value
    }));
  };

  // 保存用户信息
  const handleSaveProfile = async () => {
      const res = await api.put<ApiResponse<string>>('/me', editForm);
      
      // 获取后端返回的消息
      const serverMsg = res.data.message || 'Operation completed';

      if (res.data.code === 0) {
        setUser(prev => {
          if (!prev) return null;
          return { 
            ...prev, 
            ...editForm,
            // 保持之前的类型处理逻辑：空字符串转 null，否则转 Gender
            gender: editForm.gender === '' ? null : (editForm.gender as any) 
          };
        });
        
        setIsEditing(false);
        
        // 成功：弹出后端返回的信息
        toast.success(serverMsg); 
        
      } else {
        // 业务失败：弹出后端返回的错误信息
        toast.error(serverMsg);
      }
  };

  if (loading) return <div className="p-10 text-center">Loading...</div>;
  if (!user) return <div className="p-10 text-center">Failed to load user info</div>;

  return (
    <div className="min-h-screen bg-gray-50 py-10">
      <div className="max-w-2xl mx-auto bg-white rounded-xl shadow overflow-hidden">
        {/* Header Background */}
        <div className="h-32 bg-blue-600"></div>
        
        <div className="px-6 pb-6">
          <div className="relative flex justify-between items-end -mt-12 mb-6">
            {/* Avatar */}
            <div className="relative">
              <img 
                src={user.avatarUrl || "/IMG_6358.jpg"} 
                alt={user.username}
                className="w-24 h-24 rounded-full border-4 border-white bg-white object-cover"
              />
            </div>
            {/* Buttons Group */}
            <div className="flex space-x-3">
              <button 
                onClick={openEditModal}
                className="px-4 py-2 text-sm text-gray-700 border border-gray-300 rounded hover:bg-gray-50 font-medium"
              >
                Edit Profile
              </button>
              <button 
                onClick={handleLogout}
                className="px-4 py-2 text-sm text-red-600 border border-red-200 rounded hover:bg-red-50 font-medium"
              >
                Log Out
              </button>
            </div>
          </div>

          <div>
            <h1 className="text-2xl font-bold text-gray-900">{user.username}</h1>
            {/* 1. Removed ID, Translated 'Joined' */}
            <p className="text-sm text-gray-500">Joined: {user.joinDate}</p>
            
            {/* Translated Stats */}
            <div className="mt-4 py-4 border-t border-b border-gray-100 grid grid-cols-2 gap-4 text-center">
              <div>
                <span className="block text-xl font-bold text-gray-900">{user.followCount}</span>
                <span className="text-sm text-gray-500">Following</span>
              </div>
              <div>
                <span className="block text-xl font-bold text-gray-900">{user.followerCount}</span>
                <span className="text-sm text-gray-500">Followers</span>
              </div>
            </div>

            {/* Translated Bio */}
            <div className="mt-4">
              <h3 className="font-medium text-gray-900">Bio</h3>
              <p className="mt-1 text-gray-600">{user.bio || "No bio available."}</p>
            </div>
            
            {/* 3. Removed 'Basic Info' section completely */}
          </div>
        </div>
      </div>

      {/* 4. Edit Profile Modal */}
      {isEditing && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 px-4">
          <div className="bg-white rounded-lg shadow-xl w-full max-w-md overflow-hidden">
            <div className="px-6 py-4 border-b border-gray-200 flex justify-between items-center">
              <h3 className="text-lg font-semibold text-gray-900">Edit Profile</h3>
              <button onClick={() => setIsEditing(false)} className="text-gray-400 hover:text-gray-600">
                ✕
              </button>
            </div>
            
            <div className="p-6 space-y-4">
              {/* Username */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Username</label>
                <input
                  type="text"
                  name="username"
                  value={editForm.username}
                  onChange={handleInputChange}
                  className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>

              {/* Gender */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Gender</label>
                <select
                  name="gender"
                  value={editForm.gender}
                  onChange={handleInputChange}
                  className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
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
                  value={editForm.birthday}
                  onChange={handleInputChange}
                  className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>

              {/* Bio */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Bio</label>
                <textarea
                  name="bio"
                  rows={3}
                  value={editForm.bio}
                  onChange={handleInputChange}
                  className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
            </div>

            <div className="px-6 py-4 bg-gray-50 border-t border-gray-200 flex justify-end space-x-3">
              <button
                onClick={() => setIsEditing(false)}
                className="px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-100 rounded-md"
              >
                Cancel
              </button>
              <button
                onClick={handleSaveProfile}
                className="px-4 py-2 text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 rounded-md"
              >
                Save Changes
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}