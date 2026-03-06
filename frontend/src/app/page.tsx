import { Suspense } from 'react';
import Square from '@/components/Square';
import Navbar from '@/components/Navbar';

export default function HomePage() {
    return (
        <>
            <Suspense fallback={<div className="h-16" />}>
                <Navbar />
            </Suspense>

            <Square currentTab="newest" />
        </>
    );
}