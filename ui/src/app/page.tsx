'use client';

import { useState } from 'react';
import axios from 'axios';
import FileUpload from '@/components/FileUpload';
import FileDownload from '@/components/FileDownload';
import InviteCode from '@/components/InviteCode';
import NearbyDevices from '@/components/NearbyDevices';
import IncomingOffers from '@/components/IncomingOffers';
import TransfersPanel from '@/components/TransfersPanel';

export interface Share {
  port: number;
  token: string;
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unit = -1;
  do {
    value /= 1024;
    unit++;
  } while (value >= 1024 && unit < units.length - 1);
  return `${value.toFixed(1)} ${units[unit]}`;
}

export default function Home() {
  const [uploadedFile, setUploadedFile] = useState<File | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadPercent, setUploadPercent] = useState(0);
  const [isDownloading, setIsDownloading] = useState(false);
  const [share, setShare] = useState<Share | null>(null);
  const [activeTab, setActiveTab] = useState<'send' | 'receive' | 'nearby' | 'transfers'>('send');

  const handleFileUpload = async (file: File) => {
    setUploadedFile(file);
    setShare(null);
    setIsUploading(true);
    setUploadPercent(0);

    try {
      const formData = new FormData();
      formData.append('file', file);

      const response = await axios.post('/api/upload', formData, {
        onUploadProgress: (e) => {
          if (e.total) setUploadPercent(Math.round((e.loaded / e.total) * 100));
        },
      });

      setShare({ port: response.data.port, token: response.data.token });
    } catch (error) {
      console.error('Error uploading file:', error);
      alert('Failed to upload file. Please try again.');
    } finally {
      setIsUploading(false);
    }
  };

  const handleDownload = async (code: string) => {
    // Invite code format: "<port>-<token>"
    const dash = code.indexOf('-');
    const port = parseInt(code.slice(0, dash === -1 ? code.length : dash), 10);
    const token = dash === -1 ? '' : code.slice(dash + 1).trim();
    if (isNaN(port) || port <= 0 || port > 65535 || !token) {
      throw new Error('Invalid code. Expected format: port-token');
    }

    const url = `/api/download/${port}?token=${encodeURIComponent(token)}`;
    setIsDownloading(true);
    try {
      // Probe with an aborted fetch so auth/availability errors can be shown,
      // then hand the URL to the browser's native download manager. The file
      // streams straight to disk (no in-memory blob), so 40 GB files work,
      // and the browser can pause/resume thanks to backend Range support.
      const controller = new AbortController();
      const probe = await fetch(url, { signal: controller.signal });
      const status = probe.status;
      controller.abort();
      if (status === 401 || status === 403) {
        throw new Error('Invalid invite code.');
      }
      if (status >= 400) {
        throw new Error('Peer not reachable. The share may have ended.');
      }

      const link = document.createElement('a');
      link.href = url;
      link.download = '';
      document.body.appendChild(link);
      link.click();
      link.remove();
    } finally {
      setIsDownloading(false);
    }
  };

  const tabClass = (tab: 'send' | 'receive' | 'nearby' | 'transfers') =>
    `flex-1 py-2.5 text-sm transition-colors ${
      activeTab === tab
        ? 'border-b-2 border-neutral-900 text-neutral-900 font-medium'
        : 'border-b border-neutral-200 text-neutral-400 hover:text-neutral-600'
    }`;

  return (
    <div className="mx-auto max-w-md px-6 py-16">
      <header className="mb-10 text-center">
        <h1 className="text-2xl font-semibold tracking-tight text-neutral-900">PeerLink</h1>
        <p className="mt-1 text-sm text-neutral-500">Peer-to-peer file sharing</p>
      </header>

      <div className="mb-8 flex">
        <button className={tabClass('send')} onClick={() => setActiveTab('send')}>
          Send
        </button>
        <button className={tabClass('receive')} onClick={() => setActiveTab('receive')}>
          Receive
        </button>
        <button className={tabClass('nearby')} onClick={() => setActiveTab('nearby')}>
          Nearby
        </button>
        <button className={tabClass('transfers')} onClick={() => setActiveTab('transfers')}>
          Transfers
        </button>
      </div>

      {activeTab === 'send' && (
        <div className="space-y-4">
          <FileUpload onFileUpload={handleFileUpload} isUploading={isUploading} />

          {uploadedFile && (
            <p className="text-sm text-neutral-500">
              {uploadedFile.name}
              <span className="text-neutral-400"> · {formatSize(uploadedFile.size)}</span>
            </p>
          )}

          {isUploading && (
            <div>
              <div className="h-1 w-full overflow-hidden rounded bg-neutral-100">
                <div
                  className="h-full bg-neutral-900 transition-all"
                  style={{ width: `${uploadPercent}%` }}
                />
              </div>
              <p className="mt-2 text-xs text-neutral-400">Uploading… {uploadPercent}%</p>
            </div>
          )}

          <InviteCode share={share} />
        </div>
      )}
      {activeTab === 'receive' && (
        <FileDownload onDownload={handleDownload} isDownloading={isDownloading} />
      )}
      {activeTab === 'nearby' && <NearbyDevices />}
      {activeTab === 'transfers' && <TransfersPanel />}

      <IncomingOffers />

      <footer className="mt-16 text-center text-xs text-neutral-300">
        PeerLink © {new Date().getFullYear()}
      </footer>
    </div>
  );
}
