import Square from '@/components/Square';
import Navbar from '@/components/Navbar';

export default function FriendsPage() {
  return (
    <>
      <Navbar />
      <Square currentTab="friends" />
    </>
  );
}