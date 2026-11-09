"use client";

import React from "react";
import { Shield, Lock, Layers, Zap, X, Database, ArrowRightLeft } from "lucide-react";

interface ArchitectureModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const ArchitectureModal: React.FC<ArchitectureModalProps> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-md flex items-center justify-center p-4 overflow-y-auto">
      <div className="bg-zinc-900 border border-zinc-800 rounded-2xl max-w-3xl w-full p-6 sm:p-8 shadow-2xl text-zinc-100 my-8 animate-in fade-in zoom-in-95 duration-200">
        <div className="flex items-center justify-between border-b border-zinc-800 pb-4 mb-6">
          <div className="flex items-center space-x-3">
            <div className="p-2 bg-emerald-500/10 border border-emerald-500/20 rounded-xl text-emerald-400">
              <Layers className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold tracking-tight">System Architecture Blueprint</h2>
              <p className="text-xs text-zinc-400">Core engineering decisions powering the Wallet & Ledger Engine</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded-lg text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800 transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {/* Pillar 1 */}
          <div className="p-4 rounded-xl bg-zinc-950 border border-zinc-800/80 space-y-2.5">
            <div className="flex items-center space-x-2 text-emerald-400 font-semibold text-sm">
              <Database className="w-4 h-4" />
              <span>1. Immutable Double-Entry Ledger</span>
            </div>
            <p className="text-xs text-zinc-400 leading-relaxed">
              No mutable <code className="text-emerald-300">balance</code> column exists. Balances are derived dynamically via <code className="text-emerald-300">SUM(amount)</code> over immutable entries. Every transfer generates balanced credit/debit legs constrained to sum zero by a PostgreSQL deferred commit trigger.
            </p>
            <div className="text-[11px] font-mono bg-zinc-900 p-2 rounded text-zinc-300 border border-zinc-850">
              Invariant: ∑(Entries per Tx) = 0
            </div>
          </div>

          {/* Pillar 2 */}
          <div className="p-4 rounded-xl bg-zinc-950 border border-zinc-800/80 space-y-2.5">
            <div className="flex items-center space-x-2 text-cyan-400 font-semibold text-sm">
              <Lock className="w-4 h-4" />
              <span>2. Deadlock-Free Pessimistic Locks</span>
            </div>
            <p className="text-xs text-zinc-400 leading-relaxed">
              Transactions acquire exclusive <code className="text-cyan-300">SELECT FOR UPDATE</code> locks in a globally deterministic order (sorting UUIDs lexicographically). This breaks Coffman&apos;s circular wait condition, guaranteeing zero deadlocks under concurrent cross-transfers (A→B and B→A).
            </p>
            <div className="text-[11px] font-mono bg-zinc-900 p-2 rounded text-zinc-300 border border-zinc-850">
              Order: Lock min(A,B) then max(A,B)
            </div>
          </div>

          {/* Pillar 3 */}
          <div className="p-4 rounded-xl bg-zinc-950 border border-zinc-800/80 space-y-2.5">
            <div className="flex items-center space-x-2 text-amber-400 font-semibold text-sm">
              <Shield className="w-4 h-4" />
              <span>3. Two-Layer Idempotency & Race Recovery</span>
            </div>
            <p className="text-xs text-zinc-400 leading-relaxed">
              Fast-path cache check backed by a PostgreSQL <code className="text-amber-300">UNIQUE</code> constraint on <code className="text-amber-300">idempotency_key</code>. If concurrent retries collide, the controller catches the exception outside the rolled-back transaction and safely returns the winning thread&apos;s receipt.
            </p>
            <div className="text-[11px] font-mono bg-zinc-900 p-2 rounded text-zinc-300 border border-zinc-850">
              Guarantee: 10 concurrent retries = 1 write
            </div>
          </div>

          {/* Pillar 4 */}
          <div className="p-4 rounded-xl bg-zinc-950 border border-zinc-800/80 space-y-2.5">
            <div className="flex items-center space-x-2 text-purple-400 font-semibold text-sm">
              <Zap className="w-4 h-4" />
              <span>4. Transactional Outbox Pattern</span>
            </div>
            <p className="text-xs text-zinc-400 leading-relaxed">
              Domain events (<code className="text-purple-300">DEPOSIT_COMPLETED</code>, <code className="text-purple-300">TRANSFER_COMPLETED</code>) are saved atomically to <code className="text-purple-300">outbox_events</code> in the same database transaction. A decoupled poller streams them to Apache Kafka with <code className="text-purple-300">acks=all</code> for at-least-once delivery.
            </p>
            <div className="text-[11px] font-mono bg-zinc-900 p-2 rounded text-zinc-300 border border-zinc-850">
              Dual-write elimination: DB + Event atomic
            </div>
          </div>
        </div>

        <div className="mt-6 pt-4 border-t border-zinc-800 flex justify-between items-center text-xs text-zinc-400">
          <div className="flex items-center space-x-2 font-mono">
            <ArrowRightLeft className="w-3.5 h-3.5 text-emerald-400" />
            <span>Tested with 78 integration tests on real PostgreSQL</span>
          </div>
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-zinc-100 font-medium transition"
          >
            Close Blueprint
          </button>
        </div>
      </div>
    </div>
  );
};

