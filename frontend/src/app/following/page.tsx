import Square from '@/components/Square';
import Navbar from '@/components/Navbar';

export default function FollowingPage() {
  return (
    <>
      <Navbar />
      <Square currentTab="following" />
    </>
  );
}