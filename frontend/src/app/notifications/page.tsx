import { Suspense } from 'react';
import NotificationsClient from '@/components/NotificationsClient';

export default function NotificationsPage() {
    return (
        <Suspense fallback={<div>Loading...</div>}>
            <NotificationsClient />
        </Suspense>
    );
}