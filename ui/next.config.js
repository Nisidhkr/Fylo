/** @type {import('next').NextConfig} */

// Override with BACKEND_URL if the Java server runs on a non-default port,
// e.g. `BACKEND_URL=http://localhost:8080 npm run dev`.
const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:9090';

const nextConfig = {
  reactStrictMode: true,
  swcMinify: true,
  async rewrites() {
    return [
      {
        source: '/api/upload',
        destination: `${backendUrl}/upload`,
      },
      {
        source: '/api/download/:port',
        destination: `${backendUrl}/download/:port`,
      },
      {
        source: '/api/lan/:path*',
        destination: `${backendUrl}/lan/:path*`,
      },
      {
        source: '/api/transfers',
        destination: `${backendUrl}/transfers`,
      },
      {
        source: '/api/transfers/:path*',
        destination: `${backendUrl}/transfers/:path*`,
      },
    ];
  },
}

module.exports = nextConfig
