import { Suspense } from 'react';
import SearchResults from '@/components/SearchResults'; // 假设上面的组件放在 components 目录下
import { Loader2 } from 'lucide-react';

export default function SearchPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Loader2 className="animate-spin text-gray-400" size={32} />
      </div>
    }>
      <SearchResults />
    </Suspense>
  );
}