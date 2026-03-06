import { Suspense } from 'react';
import Square from '@/components/Square';
import Navbar from '@/components/Navbar';

export default function FollowingPage() {
    return (
        <>
            <Suspense fallback={<div className="h-16" />}>
                <Navbar />
            </Suspense>

            <Square currentTab="following" />
        </>
    );
}