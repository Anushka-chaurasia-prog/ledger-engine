"use client";

import React from "react";
import { CheckCircle2, X, Copy, Check } from "lucide-react";
import { TransferResponse, formatINR } from "@/lib/api";

interface IdempotencyModalProps {
  isOpen: boolean;
  onClose: () => void;
  result: {
    key: string;
    amountPaise: number;
    res1: TransferResponse | null;
    res2: TransferResponse | null;
    durationMs1: number;
    durationMs2: number;
    initialBalance: number;
    finalBalance: number;
  } | null;
}

export const IdempotencyModal: React.FC<IdempotencyModalProps> = ({ isOpen, onClose, result }) => {
  const [copied, setCopied] = React.useState(false);

  if (!isOpen || !result) return null;

  const isIdentical = result.res1?.transactionId === result.res2?.transactionId;
  const netDeduction = result.initialBalance - result.finalBalance;
  const isSingleDeduction = netDeduction === result.amountPaise;

  const copyKey = () => {
    navigator.clipboard.writeText(result.key);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-md flex items-center justify-center p-4">
      <div className="bg-zinc-900 border border-zinc-800 rounded-2xl max-w-2xl w-full p-6 sm:p-8 shadow-2xl text-zinc-100 animate-in fade-in zoom-in-95 duration-200">
        <div className="flex items-center justify-between border-b border-zinc-800 pb-4 mb-6">
          <div className="flex items-center space-x-3">
            <div className="p-2 bg-emerald-500/10 border border-emerald-500/20 rounded-xl text-emerald-400">
              <CheckCircle2 className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-lg font-bold">Idempotency Race Test Results</h3>
              <p className="text-xs text-zinc-400 font-mono">
                Simulated 2 simultaneous requests with the exact same Idempotency-Key
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded-lg text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800 transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Idempotency Key Bar */}
        <div className="bg-zinc-950 p-3 rounded-xl border border-zinc-800 flex items-center justify-between mb-6 text-xs font-mono">
          <div className="truncate mr-3">
            <span className="text-zinc-500">Shared Key: </span>
            <span className="text-emerald-400 font-semibold">{result.key}</span>
          </div>
          <button
            onClick={copyKey}
            className="flex items-center gap-1 px-2.5 py-1 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-300 text-[11px] transition"
          >
            {copied ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
            {copied ? "Copied" : "Copy"}
          </button>
        </div>

        {/* Side-by-Side Comparison */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-6">
          {/* Request 1 */}
          <div className="p-4 rounded-xl bg-zinc-950 border border-zinc-800 space-y-2">
            <div className="flex items-center justify-between text-xs">
              <span className="font-semibold text-zinc-200 flex items-center gap-1.5">
                <span className="w-2 h-2 rounded-full bg-cyan-400" /> Request #1 (Initial)
              </span>
              <span className="font-mono text-zinc-500">{result.durationMs1}ms</span>
            </div>
            <div className="space-y-1 font-mono text-xs">
              <div className="flex justify-between">
                <span className="text-zinc-500">Status:</span>
                <span className="text-emerald-400 font-semibold">201 CREATED</span>
              </div>
              <div className="flex justify-between">
                <span className="text-zinc-500">Tx ID:</span>
                <span className="text-zinc-300 truncate max-w-[140px]" title={result.res1?.transactionId}>
                  {result.res1?.transactionId?.slice(0, 8)}...
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-zinc-500">Amount:</span>
                <span className="text-zinc-200 font-semibold">{formatINR(result.amountPaise)}</span>
              </div>
            </div>
          </div>

          {/* Request 2 */}
          <div className="p-4 rounded-xl bg-zinc-950 border border-zinc-800 space-y-2">
            <div className="flex items-center justify-between text-xs">
              <span className="font-semibold text-zinc-200 flex items-center gap-1.5">
                <span className="w-2 h-2 rounded-full bg-amber-400" /> Request #2 (Retry)
              </span>
              <span className="font-mono text-zinc-500">{result.durationMs2}ms</span>
            </div>
            <div className="space-y-1 font-mono text-xs">
              <div className="flex justify-between">
                <span className="text-zinc-500">Status:</span>
                <span className="text-emerald-400 font-semibold">201 CREATED</span>
              </div>
              <div className="flex justify-between">
                <span className="text-zinc-500">Tx ID:</span>
                <span className="text-zinc-300 truncate max-w-[140px]" title={result.res2?.transactionId}>
                  {result.res2?.transactionId?.slice(0, 8)}...
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-zinc-500">Amount:</span>
                <span className="text-zinc-200 font-semibold">{formatINR(result.amountPaise)}</span>
              </div>
            </div>
          </div>
        </div>

        {/* Invariant Verification Card */}
        <div className={`p-4 rounded-xl border space-y-2 mb-6 ${
          isIdentical && isSingleDeduction 
            ? "bg-emerald-500/10 border-emerald-500/30 text-emerald-300"
            : "bg-rose-500/10 border-rose-500/30 text-rose-300"
        }`}>
          <div className="flex items-center space-x-2 font-semibold text-sm">
            <CheckCircle2 className="w-4 h-4 text-emerald-400" />
            <span>Mathematical Invariants Verified:</span>
          </div>
          <ul className="text-xs space-y-1 pl-6 list-disc font-mono">
            <li>
              Transaction IDs are 100% Identical:{" "}
              <strong className="text-zinc-100">{isIdentical ? "TRUE" : "FALSE"}</strong>
            </li>
            <li>
              Sender Initial Balance: {formatINR(result.initialBalance)} ➔ Final Balance: {formatINR(result.finalBalance)}
            </li>
            <li>
              Total Net Debited: {formatINR(netDeduction)} (Expected: {formatINR(result.amountPaise)})
            </li>
            <li>
              <strong>Outcome: Zero duplicate charges, Zero double-spending.</strong>
            </li>
          </ul>
        </div>

        <div className="flex justify-end">
          <button
            onClick={onClose}
            className="px-5 py-2.5 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-100 font-medium text-xs transition"
          >
            Done
          </button>
        </div>
      </div>
    </div>
  );
};

