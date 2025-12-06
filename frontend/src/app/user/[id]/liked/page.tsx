// src/app/user/[id]/liked/page.tsx

import UserProfileClient from '@/components/UserProfileClient';

export default async function LikedPostsPage({ params }: { params: Promise<{ id: string }> }) {
    const resolvedParams = await params;
    return (
    <UserProfileClient 
      viewedUserId={resolvedParams.id} 
      activeTab="liked" 
    />
  );
}