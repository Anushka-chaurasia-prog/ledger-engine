import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "LedgerEngine — Distributed Financial Ledger & Wallet Dashboard",
  description:
    "Live interactive playground for the High-Throughput Wallet & Ledger Engine. Built with Java 21, Spring Boot 3.3, and PostgreSQL. Demonstrates immutable double-entry bookkeeping, deadlock-free pessimistic locking, and two-layer idempotency.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className="dark">
      <body className="bg-zinc-950 text-zinc-100 antialiased font-sans">
        {children}
      </body>
    </html>
  );
}
