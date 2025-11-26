import UserProfileClient from '@/components/UserProfileClient';

export default async function UserFollowingPage({ params }: { params: Promise<{ id: string }> }) {
  const resolvedParams = await params;
  return (
    <UserProfileClient 
      viewedUserId={resolvedParams.id} 
      activeTab="following" 
    />
  );
}