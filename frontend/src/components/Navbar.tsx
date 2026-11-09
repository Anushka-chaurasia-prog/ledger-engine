"use client";

import React, { useState } from "react";
import { ShieldCheck, Database, GitBranch, Settings, Info } from "lucide-react";

interface NavbarProps {
  healthStatus: "UP" | "DOWN" | "CHECKING";
  dbType: string;
  apiUrl: string;
  onUpdateApiUrl: (newUrl: string) => void;
  onOpenArchModal: () => void;
}

export const Navbar: React.FC<NavbarProps> = ({
  healthStatus,
  dbType,
  apiUrl,
  onUpdateApiUrl,
  onOpenArchModal,
}) => {
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [tempUrl, setTempUrl] = useState(apiUrl);

  const handleSaveUrl = (e: React.FormEvent) => {
    e.preventDefault();
    onUpdateApiUrl(tempUrl);
    setIsSettingsOpen(false);
  };

  return (
    <>
      <header className="border-b border-zinc-800 bg-zinc-950/70 backdrop-blur-md sticky top-0 z-40">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          {/* Logo & Title */}
          <div className="flex items-center space-x-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-emerald-500 to-cyan-500 p-0.5 shadow-lg shadow-emerald-500/20">
              <div className="w-full h-full bg-zinc-950 rounded-[10px] flex items-center justify-center">
                <ShieldCheck className="w-5 h-5 text-emerald-400" />
              </div>
            </div>
            <div>
              <div className="flex items-center space-x-2">
                <h1 className="font-semibold text-zinc-100 text-base tracking-tight">
                  LedgerEngine
                </h1>
                <span className="text-[10px] font-mono uppercase bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 px-1.5 py-0.5 rounded">
                  v1.0 Live
                </span>
              </div>
              <p className="text-xs text-zinc-400 hidden sm:block">
                Distributed Immutable Double-Entry Financial Engine
              </p>
            </div>
          </div>

          {/* Center Badges */}
          <div className="hidden md:flex items-center space-x-2 text-xs font-mono">
            <span className="px-2.5 py-1 rounded-full bg-zinc-900 border border-zinc-800 text-zinc-300 flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-orange-400" /> Java 21 & Spring Boot 3.3
            </span>
            <span className="px-2.5 py-1 rounded-full bg-zinc-900 border border-zinc-800 text-zinc-300 flex items-center gap-1.5">
              <Database className="w-3.5 h-3.5 text-blue-400" /> PostgreSQL 17
            </span>
          </div>

          {/* Right Controls */}
          <div className="flex items-center space-x-3">
            {/* Health Indicator Button */}
            <button
              onClick={() => setIsSettingsOpen(true)}
              className="flex items-center space-x-2 px-3 py-1.5 rounded-lg bg-zinc-900 hover:bg-zinc-850 border border-zinc-800 text-xs transition"
              title="Click to configure backend API URL"
            >
              <span className="relative flex h-2 w-2">
                {healthStatus === "UP" ? (
                  <>
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                    <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500"></span>
                  </>
                ) : (
                  <span className="relative inline-flex rounded-full h-2 w-2 bg-rose-500"></span>
                )}
              </span>
              <span className="font-mono text-zinc-300 hidden sm:inline">
                {healthStatus === "UP" ? "Backend Online" : "Backend Disconnected"}
              </span>
              <Settings className="w-3.5 h-3.5 text-zinc-400 ml-1" />
            </button>

            {/* Architecture Modal Trigger */}
            <button
              onClick={onOpenArchModal}
              className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-emerald-500/10 hover:bg-emerald-500/20 border border-emerald-500/30 text-emerald-400 text-xs font-medium transition"
            >
              <Info className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">System Blueprint</span>
            </button>

            {/* GitHub Link */}
            <a
              href="https://github.com/Anushka-chaurasia-prog/ledger-engine"
              target="_blank"
              rel="noopener noreferrer"
              className="p-1.5 text-zinc-400 hover:text-zinc-100 transition rounded-lg hover:bg-zinc-800"
              title="View Source on GitHub"
            >
              <GitBranch className="w-4 h-4" />
            </a>
          </div>
        </div>
      </header>

      {/* API Config Modal */}
      {isSettingsOpen && (
        <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-zinc-900 border border-zinc-800 rounded-2xl max-w-md w-full p-6 shadow-2xl text-zinc-100 animate-in fade-in zoom-in-95 duration-150">
            <div className="flex items-center justify-between mb-4">
              <h3 className="font-semibold text-lg flex items-center gap-2">
                <Settings className="w-5 h-5 text-emerald-400" /> Backend Connection
              </h3>
              <button
                onClick={() => setIsSettingsOpen(false)}
                className="text-zinc-400 hover:text-zinc-100 text-sm"
              >
                ✕
              </button>
            </div>

            <p className="text-xs text-zinc-400 mb-4 leading-relaxed">
              Point this frontend to your live Render backend deployment or your local Spring Boot instance.
            </p>

            <form onSubmit={handleSaveUrl} className="space-y-4">
              <div>
                <label htmlFor="backend-url-input" className="block text-xs font-mono uppercase tracking-wider text-zinc-400 mb-1.5">
                  Spring Boot Base URL
                </label>
                <input
                  id="backend-url-input"
                  name="backendUrl"
                  type="url"
                  value={tempUrl}
                  onChange={(e) => setTempUrl(e.target.value)}
                  placeholder="https://ledger-engine.onrender.com"
                  className="w-full bg-zinc-950 border border-zinc-700 rounded-lg px-3 py-2 text-sm text-zinc-100 font-mono focus:outline-none focus:border-emerald-500"
                  required
                />
              </div>

              <div className="bg-zinc-950 p-3 rounded-lg border border-zinc-800 text-xs space-y-1.5 font-mono">
                <div className="flex justify-between">
                  <span className="text-zinc-500">Live Health:</span>
                  <span className={healthStatus === "UP" ? "text-emerald-400 font-semibold" : "text-rose-400 font-semibold"}>
                    {healthStatus}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-zinc-500">Database Engine:</span>
                  <span className="text-zinc-300">{dbType || "PostgreSQL"}</span>
                </div>
              </div>

              <div className="flex justify-end space-x-2 pt-2">
                <button
                  type="button"
                  onClick={() => setIsSettingsOpen(false)}
                  className="px-4 py-2 text-xs font-medium text-zinc-400 hover:text-zinc-200 transition"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 text-xs font-semibold rounded-lg bg-emerald-500 hover:bg-emerald-400 text-zinc-950 transition shadow-lg shadow-emerald-500/20"
                >
                  Save & Reconnect
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  );
};

