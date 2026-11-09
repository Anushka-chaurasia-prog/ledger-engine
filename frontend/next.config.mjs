/** @type {import('next').NextConfig} */
const nextConfig = {
  async rewrites() {
    const backendTarget = process.env.BACKEND_API_URL || process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';
    return [
      {
        source: '/api/:path*',
        destination: `${backendTarget}/api/:path*`,
      },
      {
        source: '/actuator/:path*',
        destination: `${backendTarget}/actuator/:path*`,
      },
    ];
  },
};

export default nextConfig;
