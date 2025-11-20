'use client';
import Link from 'next/link';
import { useRouter } from 'next/navigation';

export default function Navbar() {
  const router = useRouter();

  const handleLogout = () => {
    localStorage.removeItem('token');
    router.push('/login');
  };

  return (
    <nav className="bg-white shadow mb-8">
      <div className="max-w-4xl mx-auto px-4">
        <div className="flex justify-between h-16">
          <div className="flex items-center">
            <Link href="/" className="text-xl font-bold text-orange-600">
              MicroWeibo
            </Link>
          </div>
          <div className="flex items-center space-x-4">
            <Link href="/me" className="text-gray-600 hover:text-gray-900">
              我的主页
            </Link>
            <button
              onClick={handleLogout}
              className="text-sm text-red-500 hover:text-red-700"
            >
              退出
            </button>
          </div>
        </div>
      </div>
    </nav>
  );
}