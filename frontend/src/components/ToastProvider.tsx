// components/ToastProvider.tsx
'use client';

import { Toaster } from 'react-hot-toast';

export default function ToastProvider() {
  return (
    <Toaster 
      position="top-center" 
      reverseOrder={false}
      toastOptions={{
        // 你可以在這裡定義全域預設樣式
        duration: 3000,
        style: {
          background: '#333',
          color: '#fff',
        },
      }}
    />
  );
}