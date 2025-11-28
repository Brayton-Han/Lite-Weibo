import UserProfileClient from '@/components/UserProfileClient';

export default async function UserFollowersPage({ params }: { params: Promise<{ id: string }> }) {
  const resolvedParams = await params;
  return (
    <UserProfileClient 
      viewedUserId={resolvedParams.id} 
      activeTab="friends" 
    />
  );
}